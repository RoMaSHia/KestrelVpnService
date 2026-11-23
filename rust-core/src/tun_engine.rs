use std::collections::{HashMap, VecDeque};
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::os::unix::io::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant};

use smoltcp::iface::{Config as SmolConfig, Interface, SocketHandle, SocketSet};
use smoltcp::phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken};
use smoltcp::socket::tcp::{Socket as TcpSocket, SocketBuffer as TcpSocketBuffer, State as TcpState};
use smoltcp::time::Instant as SmolInstant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr, IpListenEndpoint, Ipv4Address, Ipv6Address};
use tokio::io::unix::AsyncFd;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::{mpsc, watch, Notify};

use crate::config::{CoreConfig, ProxyProtocol, RuleAction};
use crate::dns::{forward_dns_query, parse_dns_query, parse_dns_response_ips, DnsCache};
use crate::packet::{build_udp_ip_packet, parse_ip_packet, TransportProto};
use crate::proxy::connect_outbound_tcp;
use crate::proxy::socket::{bind_udp_protected, SocketProtector, TargetAddr};
use crate::proxy::socks5::Socks5UdpSession;
use crate::router::Router;
use crate::sniff::sniff_domain;
use crate::stats::Telemetry;

const TCP_BUFFER_SIZE: usize = 128 * 1024;
const SNIFF_WAIT_MS: u64 = 250;
const UDP_SESSION_IDLE: Duration = Duration::from_secs(60);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct FourTuple {
    src: SocketAddr,
    dst: SocketAddr,
}

struct RuntimeState {
    config: CoreConfig,
    router: Router,
}

/// Active userspace `tun2socks` engine running inside a compact Tokio runtime.
pub struct RunningEngine {
    state: Arc<RwLock<RuntimeState>>,
    telemetry: Arc<Telemetry>,
    shutdown_tx: watch::Sender<bool>,
    worker_thread: Option<std::thread::JoinHandle<()>>,
    _runtime: tokio::runtime::Runtime,
}

impl RunningEngine {
    pub fn start(
        tun_fd: RawFd,
        config: CoreConfig,
        protector: Arc<dyn SocketProtector>,
    ) -> io::Result<Self> {
        // Set TUN fd to non-blocking mode for Tokio AsyncFd
        let flags = unsafe { libc::fcntl(tun_fd, libc::F_GETFL) };
        if flags < 0 {
            return Err(io::Error::last_os_error());
        }
        let res = unsafe { libc::fcntl(tun_fd, libc::F_SETFL, flags | libc::O_NONBLOCK) };
        if res < 0 {
            return Err(io::Error::last_os_error());
        }
        let owned_fd = unsafe { OwnedFd::from_raw_fd(tun_fd) };

        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .max_blocking_threads(4)
            .thread_name("kestrel-net")
            .enable_all()
            .build()?;

        let router = Router::from_config(&config.routing);
        let state = Arc::new(RwLock::new(RuntimeState { config, router }));
        let telemetry = Arc::new(Telemetry::new());
        let dns_cache = Arc::new(DnsCache::new());
        let (shutdown_tx, shutdown_rx) = watch::channel(false);

        let rt_handle = runtime.handle().clone();
        let state_clone = Arc::clone(&state);
        let telemetry_clone = Arc::clone(&telemetry);

        let worker_thread = std::thread::Builder::new()
            .name("kestrel-tun-loop".to_string())
            .spawn(move || {
                rt_handle.block_on(async move {
                    if let Err(err) = run_tun_event_loop(
                        owned_fd,
                        state_clone,
                        telemetry_clone,
                        dns_cache,
                        protector,
                        shutdown_rx,
                    )
                    .await
                    {
                        log::error!("TUN event loop terminated with error: {err}");
                    }
                });
            })?;

        Ok(Self {
            state,
            telemetry,
            shutdown_tx,
            worker_thread: Some(worker_thread),
            _runtime: runtime,
        })
    }

    pub fn update_config(&self, config: CoreConfig) {
        let router = Router::from_config(&config.routing);
        if let Ok(mut guard) = self.state.write() {
            guard.config = config;
            guard.router = router;
        }
    }

    pub fn stats_json(&self) -> String {
        let snap = self.telemetry.snapshot();
        serde_json::to_string(&snap).unwrap_or_else(|_| "{}".to_string())
    }

    pub fn poll_logs_json(&self, since_id: u64, limit: usize) -> String {
        let logs = self.telemetry.poll_logs(since_id, limit);
        serde_json::to_string(&logs).unwrap_or_else(|_| "[]".to_string())
    }

    pub fn stop(mut self) {
        let _ = self.shutdown_tx.send(true);
        if let Some(handle) = self.worker_thread.take() {
            let _ = handle.join();
        }
    }
}

impl Drop for RunningEngine {
    fn drop(&mut self) {
        let _ = self.shutdown_tx.send(true);
        if let Some(handle) = self.worker_thread.take() {
            let _ = handle.join();
        }
    }
}

struct VirtRxToken(Vec<u8>);

impl RxToken for VirtRxToken {
    fn consume<R, F>(mut self, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        f(&mut self.0)
    }
}

struct VirtTxToken<'a> {
    tx_queue: &'a mut VecDeque<Vec<u8>>,
}

impl<'a> TxToken for VirtTxToken<'a> {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut buf = vec![0u8; len];
        let result = f(&mut buf);
        self.tx_queue.push_back(buf);
        result
    }
}

struct VirtTunDevice {
    mtu: usize,
    rx_queue: VecDeque<Vec<u8>>,
    tx_queue: VecDeque<Vec<u8>>,
}

impl VirtTunDevice {
    fn new(mtu: usize) -> Self {
        Self {
            mtu: mtu.max(576),
            rx_queue: VecDeque::with_capacity(64),
            tx_queue: VecDeque::with_capacity(64),
        }
    }
}

impl Device for VirtTunDevice {
    type RxToken<'a>
        = VirtRxToken
    where
        Self: 'a;
    type TxToken<'a>
        = VirtTxToken<'a>
    where
        Self: 'a;

    fn receive(
        &mut self,
        _timestamp: SmolInstant,
    ) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        self.rx_queue.pop_front().map(|pkt| {
            (
                VirtRxToken(pkt),
                VirtTxToken {
                    tx_queue: &mut self.tx_queue,
                },
            )
        })
    }

    fn transmit(&mut self, _timestamp: SmolInstant) -> Option<Self::TxToken<'_>> {
        Some(VirtTxToken {
            tx_queue: &mut self.tx_queue,
        })
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        caps.max_transmission_unit = self.mtu;
        caps
    }
}

enum ProxyToTun {
    Data(Vec<u8>),
    Close,
    Abort,
}

struct TcpSession {
    handle: SocketHandle,
    to_proxy_tx: Option<mpsc::Sender<Vec<u8>>>,
    from_proxy_rx: mpsc::Receiver<ProxyToTun>,
    pending_to_app: VecDeque<u8>,
    close_after_drain: bool,
    last_activity: Instant,
}

fn to_smol_ip(ip: IpAddr) -> IpAddress {
    match ip {
        IpAddr::V4(v4) => IpAddress::Ipv4(Ipv4Address::from(v4)),
        IpAddr::V6(v6) => IpAddress::Ipv6(Ipv6Address::from(v6)),
    }
}

fn read_tun_packet(fd: RawFd, buf: &mut [u8]) -> io::Result<usize> {
    let n = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
    if n < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(n as usize)
    }
}

fn write_tun_packet(fd: RawFd, data: &[u8]) -> io::Result<usize> {
    let n = unsafe { libc::write(fd, data.as_ptr() as *const libc::c_void, data.len()) };
    if n < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(n as usize)
    }
}

async fn run_tun_event_loop(
    owned_fd: OwnedFd,
    state: Arc<RwLock<RuntimeState>>,
    telemetry: Arc<Telemetry>,
    dns_cache: Arc<DnsCache>,
    protector: Arc<dyn SocketProtector>,
    mut shutdown_rx: watch::Receiver<bool>,
) -> io::Result<()> {
    let initial_mtu = state
        .read()
        .map(|s| s.config.mtu as usize)
        .unwrap_or(1500)
        .max(576);

    let tun_async = {
        #[allow(deprecated)]
        AsyncFd::new(owned_fd)?
    };
    let start_instant = Instant::now();
    let now_smol = SmolInstant::from_millis(0);

    let mut device = VirtTunDevice::new(initial_mtu);
    let mut iface_cfg = SmolConfig::new(HardwareAddress::Ip);
    iface_cfg.random_seed = 0x4B45_5354_5245_4C01;
    let mut iface = Interface::new(iface_cfg, &mut device, now_smol);
    iface.update_ip_addrs(|addrs| {
        let _ = addrs.push(IpCidr::new(IpAddress::v4(10, 10, 0, 1), 24));
        let _ = addrs.push(IpCidr::new(
            IpAddress::v6(0xfd00, 0x10, 0, 0, 0, 0, 0, 1),
            64,
        ));
    });
    let _ = iface
        .routes_mut()
        .add_default_ipv4_route(Ipv4Address::new(10, 10, 0, 1));
    let _ = iface
        .routes_mut()
        .add_default_ipv6_route(Ipv6Address::new(0xfd00, 0x10, 0, 0, 0, 0, 0, 1));
    iface.set_any_ip(true);

    let mut sockets = SocketSet::new(vec![]);
    let mut tcp_sessions: HashMap<FourTuple, TcpSession> = HashMap::new();
    let mut udp_flows: HashMap<FourTuple, mpsc::Sender<Vec<u8>>> = HashMap::new();
    let (udp_reply_tx, mut udp_reply_rx) = mpsc::channel::<Vec<u8>>(512);
    let mut tun_write_queue: VecDeque<Vec<u8>> = VecDeque::with_capacity(128);
    let wake_notify = Arc::new(Notify::new());

    let mut read_buf = vec![0u8; initial_mtu.max(4096)];
    let mut last_gc = Instant::now();

    loop {
        if *shutdown_rx.borrow() {
            break;
        }

        // Flush smoltcp tx_queue into tun_write_queue
        while let Some(pkt) = device.tx_queue.pop_front() {
            tun_write_queue.push_back(pkt);
        }
        while let Ok(pkt) = udp_reply_rx.try_recv() {
            tun_write_queue.push_back(pkt);
        }

        // Try non-blocking writes immediately before waiting
        let raw_fd = tun_async.as_raw_fd();
        while let Some(front) = tun_write_queue.front() {
            match write_tun_packet(raw_fd, front) {
                Ok(_) => {
                    tun_write_queue.pop_front();
                }
                Err(err) if err.kind() == io::ErrorKind::WouldBlock => break,
                Err(err) => {
                    log::debug!("TUN write error: {err}");
                    tun_write_queue.pop_front();
                }
            }
        }

        let smol_now = SmolInstant::from_millis(start_instant.elapsed().as_millis() as i64);
        let poll_delay = iface
            .poll_delay(smol_now, &sockets)
            .map(|d| Duration::from_millis(d.total_millis().max(1)))
            .unwrap_or(Duration::from_millis(250))
            .min(Duration::from_millis(250));

        let need_write = !tun_write_queue.is_empty();

        tokio::select! {
            biased;
            _ = shutdown_rx.changed() => {
                if *shutdown_rx.borrow() {
                    break;
                }
            }
            res = tun_async.writable(), if need_write => {
                if let Ok(mut guard) = res {
                    let mut would_block = false;
                    while let Some(front) = tun_write_queue.front() {
                        match write_tun_packet(raw_fd, front) {
                            Ok(_) => {
                                tun_write_queue.pop_front();
                            }
                            Err(err) if err.kind() == io::ErrorKind::WouldBlock => {
                                would_block = true;
                                break;
                            }
                            Err(_) => {
                                tun_write_queue.pop_front();
                            }
                        }
                    }
                    if would_block {
                        guard.clear_ready();
                    }
                }
            }
            res = tun_async.readable() => {
                if let Ok(mut guard) = res {
                    let mut handled = 0usize;
                    loop {
                        if handled >= 64 {
                            break;
                        }
                        match read_tun_packet(raw_fd, &mut read_buf) {
                            Ok(n) if n > 0 => {
                                handled += 1;
                                let pkt = read_buf[..n].to_vec();
                                handle_incoming_tun_packet(
                                    pkt,
                                    &mut device,
                                    &mut iface,
                                    &mut sockets,
                                    &mut tcp_sessions,
                                    &mut udp_flows,
                                    &udp_reply_tx,
                                    &state,
                                    &telemetry,
                                    &dns_cache,
                                    &protector,
                                    &wake_notify,
                                    start_instant,
                                );
                            }
                            Ok(_) => break,
                            Err(err) if err.kind() == io::ErrorKind::WouldBlock => {
                                guard.clear_ready();
                                break;
                            }
                            Err(err) => {
                                log::warn!("TUN read error: {err}");
                                guard.clear_ready();
                                break;
                            }
                        }
                    }
                }
            }
            Some(pkt) = udp_reply_rx.recv() => {
                tun_write_queue.push_back(pkt);
            }
            _ = wake_notify.notified() => {}
            _ = tokio::time::sleep(poll_delay) => {}
        }

        // Pump active TCP sessions (smoltcp <-> async proxy tasks)
        pump_tcp_sessions(&mut sockets, &mut tcp_sessions);

        let now_ms = start_instant.elapsed().as_millis() as u64;
        iface.poll(
            SmolInstant::from_millis(now_ms as i64),
            &mut device,
            &mut sockets,
        );

        // Second pump pass in case poll freed tx/rx buffer space
        if !tcp_sessions.is_empty() {
            pump_tcp_sessions(&mut sockets, &mut tcp_sessions);
            iface.poll(
                SmolInstant::from_millis(start_instant.elapsed().as_millis() as i64),
                &mut device,
                &mut sockets,
            );
        }

        if last_gc.elapsed() >= Duration::from_secs(5) {
            last_gc = Instant::now();
            cleanup_closed_sessions(&mut sockets, &mut tcp_sessions, &mut udp_flows);
        }
    }

    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn handle_incoming_tun_packet(
    pkt: Vec<u8>,
    device: &mut VirtTunDevice,
    iface: &mut Interface,
    sockets: &mut SocketSet<'_>,
    tcp_sessions: &mut HashMap<FourTuple, TcpSession>,
    udp_flows: &mut HashMap<FourTuple, mpsc::Sender<Vec<u8>>>,
    udp_reply_tx: &mpsc::Sender<Vec<u8>>,
    state: &Arc<RwLock<RuntimeState>>,
    telemetry: &Arc<Telemetry>,
    dns_cache: &Arc<DnsCache>,
    protector: &Arc<dyn SocketProtector>,
    wake_notify: &Arc<Notify>,
    start_instant: Instant,
) {
    let Some(parsed) = parse_ip_packet(&pkt) else {
        return;
    };

    let tuple = FourTuple {
        src: SocketAddr::new(parsed.src_ip, parsed.src_port),
        dst: SocketAddr::new(parsed.dst_ip, parsed.dst_port),
    };

    match parsed.proto {
        TransportProto::Tcp => {
            let is_syn_only = (parsed.tcp_flags & 0x02) != 0 && (parsed.tcp_flags & 0x10) == 0;
            if is_syn_only {
                if let Some(existing) = tcp_sessions.get(&tuple) {
                    let existing_state = sockets.get::<TcpSocket>(existing.handle).state();
                    if matches!(
                        existing_state,
                        TcpState::Closed
                            | TcpState::TimeWait
                            | TcpState::Closing
                            | TcpState::LastAck
                    ) {
                        if let Some(stale) = tcp_sessions.remove(&tuple) {
                            sockets.remove(stale.handle);
                        }
                    }
                }
            }

            if is_syn_only && !tcp_sessions.contains_key(&tuple) {
                if !device.rx_queue.is_empty() {
                    let now_smol =
                        SmolInstant::from_millis(start_instant.elapsed().as_millis() as i64);
                    iface.poll(now_smol, device, sockets);
                }

                let rx_buf = TcpSocketBuffer::new(vec![0u8; TCP_BUFFER_SIZE]);
                let tx_buf = TcpSocketBuffer::new(vec![0u8; TCP_BUFFER_SIZE]);
                let mut sock = TcpSocket::new(rx_buf, tx_buf);
                sock.set_ack_delay(None);
                sock.set_nagle_enabled(false);
                sock.set_keep_alive(Some(smoltcp::time::Duration::from_secs(30)));
                sock.set_timeout(Some(smoltcp::time::Duration::from_secs(120)));

                let endpoint = IpListenEndpoint {
                    addr: Some(to_smol_ip(tuple.dst.ip())),
                    port: tuple.dst.port(),
                };
                if sock.listen(endpoint).is_ok() {
                    let handle = sockets.add(sock);
                    device.rx_queue.push_back(pkt);
                    let now_smol =
                        SmolInstant::from_millis(start_instant.elapsed().as_millis() as i64);
                    iface.poll(now_smol, device, sockets);

                    if sockets.get::<TcpSocket>(handle).state() == TcpState::Listen {
                        sockets.remove(handle);
                        return;
                    }

                    let (to_proxy_tx, from_tun_rx) = mpsc::channel::<Vec<u8>>(32);
                    let (to_tun_tx, from_proxy_rx) = mpsc::channel::<ProxyToTun>(32);

                    tcp_sessions.insert(
                        tuple,
                        TcpSession {
                            handle,
                            to_proxy_tx: Some(to_proxy_tx),
                            from_proxy_rx,
                            pending_to_app: VecDeque::new(),
                            close_after_drain: false,
                            last_activity: Instant::now(),
                        },
                    );

                    let state_c = Arc::clone(state);
                    let telemetry_c = Arc::clone(telemetry);
                    let dns_c = Arc::clone(dns_cache);
                    let protector_c = Arc::clone(protector);
                    let wake_c = Arc::clone(wake_notify);
                    tokio::spawn(async move {
                        run_tcp_relay(
                            tuple.dst,
                            from_tun_rx,
                            to_tun_tx,
                            state_c,
                            telemetry_c,
                            dns_c,
                            protector_c,
                            wake_c,
                        )
                        .await;
                    });
                }
            } else if tcp_sessions.contains_key(&tuple) {
                device.rx_queue.push_back(pkt);
            }
        }
        TransportProto::Udp => {
            let intercept_dns = state
                .read()
                .map(|s| s.config.dns.intercept_dns)
                .unwrap_or(true);
            if parsed.dst_port == 53 && intercept_dns {
                let query_bytes = parsed.payload.to_vec();
                let reply_tx = udp_reply_tx.clone();
                let state_c = Arc::clone(state);
                let telemetry_c = Arc::clone(telemetry);
                let dns_c = Arc::clone(dns_cache);
                let protector_c = Arc::clone(protector);
                tokio::spawn(async move {
                    handle_dns_packet(
                        tuple,
                        query_bytes,
                        reply_tx,
                        state_c,
                        telemetry_c,
                        dns_c,
                        protector_c,
                    )
                    .await;
                });
                return;
            }

            let mut payload = parsed.payload.to_vec();
            if let Some(tx) = udp_flows.get(&tuple) {
                match tx.try_send(payload) {
                    Ok(()) => return,
                    Err(err) => {
                        payload = err.into_inner();
                        udp_flows.remove(&tuple);
                    }
                }
            }

            let (flow_tx, flow_rx) = mpsc::channel::<Vec<u8>>(32);
            if flow_tx.try_send(payload).is_ok() {
                udp_flows.insert(tuple, flow_tx);
                let reply_tx = udp_reply_tx.clone();
                let state_c = Arc::clone(state);
                let telemetry_c = Arc::clone(telemetry);
                let dns_c = Arc::clone(dns_cache);
                let protector_c = Arc::clone(protector);
                tokio::spawn(async move {
                    run_udp_flow(
                        tuple,
                        flow_rx,
                        reply_tx,
                        state_c,
                        telemetry_c,
                        dns_c,
                        protector_c,
                    )
                    .await;
                });
            }
        }
        TransportProto::Other(_) => {}
    }
}

fn pump_tcp_sessions(
    sockets: &mut SocketSet<'_>,
    tcp_sessions: &mut HashMap<FourTuple, TcpSession>,
) {
    let mut temp_buf = [0u8; 16 * 1024];

    for session in tcp_sessions.values_mut() {
        let sock = sockets.get_mut::<TcpSocket>(session.handle);

        // 1. Drain outbound data from smoltcp -> proxy task
        if let Some(tx) = session.to_proxy_tx.as_ref() {
            while sock.can_recv() {
                match tx.try_reserve() {
                    Ok(permit) => {
                        let read_n = sock.recv(|buf| {
                            let take = buf.len().min(temp_buf.len());
                            temp_buf[..take].copy_from_slice(&buf[..take]);
                            (take, take)
                        });
                        match read_n {
                            Ok(n) if n > 0 => {
                                session.last_activity = Instant::now();
                                permit.send(temp_buf[..n].to_vec());
                            }
                            _ => break,
                        }
                    }
                    Err(_) => break,
                }
            }
            // Only treat the client's send side as closed once TCP actually enters a FIN/closed state,
            // NOT while still in SynReceived during the 3-way handshake!
            let peer_closed = matches!(
                sock.state(),
                TcpState::CloseWait
                    | TcpState::LastAck
                    | TcpState::Closing
                    | TcpState::TimeWait
                    | TcpState::Closed
            );
            if peer_closed && sock.recv_queue() == 0 {
                session.to_proxy_tx = None;
            }
        }

        // 2. Drain inbound data from proxy task -> smoltcp
        loop {
            match session.from_proxy_rx.try_recv() {
                Ok(ProxyToTun::Data(chunk)) => {
                    session.last_activity = Instant::now();
                    session.pending_to_app.extend(chunk);
                }
                Ok(ProxyToTun::Close) => {
                    session.close_after_drain = true;
                    break;
                }
                Ok(ProxyToTun::Abort) => {
                    session.pending_to_app.clear();
                    sock.abort();
                    break;
                }
                Err(mpsc::error::TryRecvError::Disconnected) => {
                    session.close_after_drain = true;
                    break;
                }
                Err(mpsc::error::TryRecvError::Empty) => break,
            }
        }

        while sock.can_send() && !session.pending_to_app.is_empty() {
            let (first, _) = session.pending_to_app.as_slices();
            if first.is_empty() {
                session.pending_to_app.make_contiguous();
                continue;
            }
            match sock.send_slice(first) {
                Ok(sent) if sent > 0 => {
                    session.last_activity = Instant::now();
                    session.pending_to_app.drain(..sent);
                }
                _ => break,
            }
        }

        if session.close_after_drain
            && session.pending_to_app.is_empty()
            && matches!(sock.state(), TcpState::Established | TcpState::CloseWait)
        {
            sock.close();
        }
    }
}

fn cleanup_closed_sessions(
    sockets: &mut SocketSet<'_>,
    tcp_sessions: &mut HashMap<FourTuple, TcpSession>,
    udp_flows: &mut HashMap<FourTuple, mpsc::Sender<Vec<u8>>>,
) {
    let mut to_remove = Vec::new();
    for (tuple, session) in tcp_sessions.iter() {
        let sock = sockets.get::<TcpSocket>(session.handle);
        let idle_expired = session.last_activity.elapsed() > Duration::from_secs(180);
        if matches!(sock.state(), TcpState::Closed | TcpState::TimeWait) || idle_expired {
            to_remove.push(*tuple);
        }
    }
    for tuple in to_remove {
        if let Some(session) = tcp_sessions.remove(&tuple) {
            sockets.remove(session.handle);
        }
    }
    udp_flows.retain(|_, tx| !tx.is_closed());
}

#[allow(clippy::too_many_arguments)]
async fn run_tcp_relay(
    dst_addr: SocketAddr,
    mut from_tun_rx: mpsc::Receiver<Vec<u8>>,
    to_tun_tx: mpsc::Sender<ProxyToTun>,
    state: Arc<RwLock<RuntimeState>>,
    telemetry: Arc<Telemetry>,
    dns_cache: Arc<DnsCache>,
    protector: Arc<dyn SocketProtector>,
    wake_notify: Arc<Notify>,
) {
    telemetry.inc_connections();
    struct ConnGuard(Arc<Telemetry>);
    impl Drop for ConnGuard {
        fn drop(&mut self) {
            self.0.dec_connections();
        }
    }
    let _conn_guard = ConnGuard(Arc::clone(&telemetry));

    // Wait briefly for initial payload to sniff TLS SNI or HTTP Host
    let first_chunk = match tokio::time::timeout(
        Duration::from_millis(SNIFF_WAIT_MS),
        from_tun_rx.recv(),
    )
    .await
    {
        Ok(Some(chunk)) => Some(chunk),
        Ok(None) => return,
        Err(_) => None,
    };

    let sniffed_domain = first_chunk.as_deref().and_then(sniff_domain);
    let cached_domain = dns_cache.lookup(&dst_addr.ip());
    let domain = sniffed_domain.or(cached_domain);

    let (decision, proxy_cfg, dns_upstream) = {
        let Ok(guard) = state.read() else {
            return;
        };
        let dec = guard
            .router
            .decide(domain.as_deref(), dst_addr.ip(), dst_addr.port());
        (
            dec,
            guard.config.proxy.clone(),
            guard.config.dns.upstream.clone(),
        )
    };

    let display_host = domain
        .clone()
        .unwrap_or_else(|| dst_addr.ip().to_string());
    telemetry.push_log(
        "TCP",
        display_host.clone(),
        dst_addr.ip().to_string(),
        dst_addr.port(),
        decision.action,
        decision.rule_matched,
    );

    if decision.action == RuleAction::Block {
        let _ = to_tun_tx.send(ProxyToTun::Abort).await;
        wake_notify.notify_one();
        return;
    }

    let target = match (decision.action, domain) {
        (RuleAction::Proxy, Some(dom)) => TargetAddr::Domain(dom, dst_addr.port()),
        _ => TargetAddr::Ip(dst_addr),
    };

    let mut stream = match connect_outbound_tcp(
        decision.action,
        proxy_cfg.as_ref(),
        &target,
        &dns_upstream,
        protector.as_ref(),
    )
    .await
    {
        Ok(s) => s,
        Err(err) => {
            log::warn!(
                "Outbound TCP connect to {display_host}:{} failed: {err}",
                dst_addr.port()
            );
            telemetry.push_log(
                "TCP",
                display_host,
                dst_addr.ip().to_string(),
                dst_addr.port(),
                RuleAction::Block,
                format!("Ошибка: {err}"),
            );
            let _ = to_tun_tx.send(ProxyToTun::Abort).await;
            wake_notify.notify_one();
            return;
        }
    };

    if let Some(chunk) = first_chunk {
        if stream.write_all(&chunk).await.is_err() {
            let _ = to_tun_tx.send(ProxyToTun::Abort).await;
            wake_notify.notify_one();
            return;
        }
        telemetry.record_tx(chunk.len() as u64);
    }

    let (mut remote_rd, mut remote_wr) = stream.into_split();
    let telemetry_tx = Arc::clone(&telemetry);
    let telemetry_rx = Arc::clone(&telemetry);
    let wake_rx = Arc::clone(&wake_notify);
    let to_tun_tx_clone = to_tun_tx.clone();

    let upload_task = async move {
        while let Some(chunk) = from_tun_rx.recv().await {
            if remote_wr.write_all(&chunk).await.is_err() {
                break;
            }
            telemetry_tx.record_tx(chunk.len() as u64);
        }
        let _ = remote_wr.shutdown().await;
    };

    let download_task = async move {
        let mut buf = vec![0u8; 16 * 1024];
        loop {
            match remote_rd.read(&mut buf).await {
                Ok(0) => {
                    let _ = to_tun_tx_clone.send(ProxyToTun::Close).await;
                    wake_rx.notify_one();
                    break;
                }
                Ok(n) => {
                    telemetry_rx.record_rx(n as u64);
                    if to_tun_tx_clone
                        .send(ProxyToTun::Data(buf[..n].to_vec()))
                        .await
                        .is_err()
                    {
                        break;
                    }
                    wake_rx.notify_one();
                }
                Err(_) => {
                    let _ = to_tun_tx_clone.send(ProxyToTun::Abort).await;
                    wake_rx.notify_one();
                    break;
                }
            }
        }
    };

    tokio::join!(upload_task, download_task);
}

async fn handle_dns_packet(
    tuple: FourTuple,
    query_bytes: Vec<u8>,
    reply_tx: mpsc::Sender<Vec<u8>>,
    state: Arc<RwLock<RuntimeState>>,
    telemetry: Arc<Telemetry>,
    dns_cache: Arc<DnsCache>,
    protector: Arc<dyn SocketProtector>,
) {
    let parsed_query = parse_dns_query(&query_bytes);
    let qname = parsed_query
        .as_ref()
        .map(|q| q.qname.clone())
        .unwrap_or_else(|| "unknown".to_string());

    let (cfg, decision) = {
        let Ok(guard) = state.read() else {
            return;
        };
        let dec = if guard.config.dns.route_dns {
            guard
                .router
                .decide(parsed_query.as_ref().map(|q| q.qname.as_str()), tuple.dst.ip(), 53)
        } else {
            guard.router.decide(None, tuple.dst.ip(), 53)
        };
        (guard.config.clone(), dec)
    };

    telemetry.record_tx(query_bytes.len() as u64);
    telemetry.push_log(
        "DNS",
        qname.clone(),
        tuple.dst.ip().to_string(),
        53,
        decision.action,
        decision.rule_matched,
    );

    if let Ok(resp) = forward_dns_query(&query_bytes, decision.action, &cfg, protector.as_ref()).await
    {
        telemetry.record_rx(resp.len() as u64);
        if parsed_query.is_some() {
            for ip in parse_dns_response_ips(&resp) {
                dns_cache.insert(ip, qname.clone());
            }
        }
        if let Some(ip_pkt) = build_udp_ip_packet(tuple.dst, tuple.src, &resp) {
            let _ = reply_tx.send(ip_pkt).await;
        }
    }
}

async fn run_udp_flow(
    tuple: FourTuple,
    mut flow_rx: mpsc::Receiver<Vec<u8>>,
    reply_tx: mpsc::Sender<Vec<u8>>,
    state: Arc<RwLock<RuntimeState>>,
    telemetry: Arc<Telemetry>,
    dns_cache: Arc<DnsCache>,
    protector: Arc<dyn SocketProtector>,
) {
    let domain = dns_cache.lookup(&tuple.dst.ip());
    let (decision, proxy_cfg, dns_upstream) = {
        let Ok(guard) = state.read() else {
            return;
        };
        let dec = guard
            .router
            .decide(domain.as_deref(), tuple.dst.ip(), tuple.dst.port());
        (
            dec,
            guard.config.proxy.clone(),
            guard.config.dns.upstream.clone(),
        )
    };

    let display_host = domain
        .clone()
        .unwrap_or_else(|| tuple.dst.ip().to_string());
    telemetry.push_log(
        "UDP",
        display_host,
        tuple.dst.ip().to_string(),
        tuple.dst.port(),
        decision.action,
        decision.rule_matched,
    );

    match decision.action {
        RuleAction::Block => {
            // Drain quickly and exit
        }
        RuleAction::Direct => {
            let Ok(udp_sock) = bind_udp_protected(tuple.dst.is_ipv6(), protector.as_ref()) else {
                return;
            };
            if udp_sock.connect(tuple.dst).await.is_err() {
                return;
            }
            let mut recv_buf = vec![0u8; 4096];
            loop {
                tokio::select! {
                    maybe_pkt = tokio::time::timeout(UDP_SESSION_IDLE, flow_rx.recv()) => {
                        match maybe_pkt {
                            Ok(Some(pkt)) => {
                                if udp_sock.send(&pkt).await.is_ok() {
                                    telemetry.record_tx(pkt.len() as u64);
                                }
                            }
                            _ => break,
                        }
                    }
                    res = udp_sock.recv(&mut recv_buf) => {
                        match res {
                            Ok(n) if n > 0 => {
                                telemetry.record_rx(n as u64);
                                if let Some(ip_pkt) = build_udp_ip_packet(tuple.dst, tuple.src, &recv_buf[..n]) {
                                    if reply_tx.send(ip_pkt).await.is_err() {
                                        break;
                                    }
                                }
                            }
                            _ => break,
                        }
                    }
                }
            }
        }
        RuleAction::Proxy => {
            let Some(proxy) = proxy_cfg else {
                return;
            };
            if proxy.protocol != ProxyProtocol::Socks5 {
                // HTTP/SOCKS4 only tunnel TCP; dropping UDP (e.g. QUIC/443) forces clean TCP fallback.
                return;
            }
            let Ok(session) =
                Socks5UdpSession::associate(&proxy, &dns_upstream, protector.as_ref()).await
            else {
                return;
            };
            let target = match domain {
                Some(dom) => TargetAddr::Domain(dom, tuple.dst.port()),
                None => TargetAddr::Ip(tuple.dst),
            };
            let mut recv_buf = vec![0u8; 4096];
            loop {
                tokio::select! {
                    maybe_pkt = tokio::time::timeout(UDP_SESSION_IDLE, flow_rx.recv()) => {
                        match maybe_pkt {
                            Ok(Some(pkt)) => {
                                if session.send_to(&target, &pkt).await.is_ok() {
                                    telemetry.record_tx(pkt.len() as u64);
                                }
                            }
                            _ => break,
                        }
                    }
                    res = session.recv(&mut recv_buf) => {
                        match res {
                            Ok((_, payload)) if !payload.is_empty() => {
                                telemetry.record_rx(payload.len() as u64);
                                if let Some(ip_pkt) = build_udp_ip_packet(tuple.dst, tuple.src, &payload) {
                                    if reply_tx.send(ip_pkt).await.is_err() {
                                        break;
                                    }
                                }
                            }
                            _ => break,
                        }
                    }
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{
        DnsConfig, ProxyConfig, ProxyProtocol, RoutingConfig, RoutingRuleConfig, RuleMatchType,
    };
    use crate::proxy::socket::NoopSocketProtector;
    use smoltcp::wire::IpEndpoint;
    use tokio::net::TcpListener;

    struct SocketPairClientDevice {
        fd: RawFd,
        rx_buf: Vec<u8>,
    }

    impl SocketPairClientDevice {
        fn new(fd: RawFd) -> Self {
            Self {
                fd,
                rx_buf: vec![0u8; 4096],
            }
        }
    }

    struct ClientRxToken(Vec<u8>);
    impl RxToken for ClientRxToken {
        fn consume<R, F>(mut self, f: F) -> R
        where
            F: FnOnce(&mut [u8]) -> R,
        {
            f(&mut self.0)
        }
    }

    struct ClientTxToken {
        fd: RawFd,
    }
    impl TxToken for ClientTxToken {
        fn consume<R, F>(self, len: usize, f: F) -> R
        where
            F: FnOnce(&mut [u8]) -> R,
        {
            let mut buf = vec![0u8; len];
            let res = f(&mut buf);
            let _ = write_tun_packet(self.fd, &buf);
            res
        }
    }

    impl Device for SocketPairClientDevice {
        type RxToken<'a>
            = ClientRxToken
        where
            Self: 'a;
        type TxToken<'a>
            = ClientTxToken
        where
            Self: 'a;

        fn receive(
            &mut self,
            _timestamp: SmolInstant,
        ) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
            match read_tun_packet(self.fd, &mut self.rx_buf) {
                Ok(n) if n > 0 => Some((
                    ClientRxToken(self.rx_buf[..n].to_vec()),
                    ClientTxToken { fd: self.fd },
                )),
                _ => None,
            }
        }

        fn transmit(&mut self, _timestamp: SmolInstant) -> Option<Self::TxToken<'_>> {
            Some(ClientTxToken { fd: self.fd })
        }

        fn capabilities(&self) -> DeviceCapabilities {
            let mut caps = DeviceCapabilities::default();
            caps.medium = Medium::Ip;
            caps.max_transmission_unit = 1500;
            caps
        }
    }

    async fn spawn_mock_socks5_redirect(target_addr: SocketAddr) -> SocketAddr {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let proxy_addr = listener.local_addr().unwrap();

        tokio::spawn(async move {
            while let Ok((mut client, _)) = listener.accept().await {
                tokio::spawn(async move {
                    let mut greet = [0u8; 3];
                    if client.read_exact(&mut greet).await.is_err() {
                        return;
                    }
                    if client.write_all(&[0x05, 0x00]).await.is_err() {
                        return;
                    }
                    let mut req_hdr = [0u8; 4];
                    if client.read_exact(&mut req_hdr).await.is_err() {
                        return;
                    }
                    match req_hdr[3] {
                        0x01 => {
                            let mut rest = [0u8; 6];
                            if client.read_exact(&mut rest).await.is_err() {
                                return;
                            }
                        }
                        0x03 => {
                            let mut len = [0u8; 1];
                            if client.read_exact(&mut len).await.is_err() {
                                return;
                            }
                            let mut rest = vec![0u8; len[0] as usize + 2];
                            if client.read_exact(&mut rest).await.is_err() {
                                return;
                            }
                        }
                        _ => return,
                    }
                    if client
                        .write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x1F, 0x90])
                        .await
                        .is_err()
                    {
                        return;
                    }
                    if let Ok(mut upstream) = tokio::net::TcpStream::connect(target_addr).await {
                        let _ = tokio::io::copy_bidirectional(&mut client, &mut upstream).await;
                    }
                });
            }
        });

        proxy_addr
    }

    fn drive_client_tcp_exchange(
        client_fd: RawFd,
        dst_ip: Ipv4Address,
        dst_port: u16,
        src_port: u16,
        payload_to_send: &[u8],
        expect_reply: bool,
    ) -> (Vec<u8>, bool) {
        let mut device = SocketPairClientDevice::new(client_fd);
        let start = Instant::now();
        let mut cfg = SmolConfig::new(HardwareAddress::Ip);
        cfg.random_seed = 0x1122_3344_5566_7788;
        let mut iface = Interface::new(cfg, &mut device, SmolInstant::from_millis(0));
        iface.update_ip_addrs(|addrs| {
            let _ = addrs.push(IpCidr::new(IpAddress::v4(10, 10, 0, 2), 24));
        });
        let _ = iface
            .routes_mut()
            .add_default_ipv4_route(Ipv4Address::new(10, 10, 0, 1));

        let mut sockets = SocketSet::new(vec![]);
        let rx_buf = TcpSocketBuffer::new(vec![0u8; 64 * 1024]);
        let tx_buf = TcpSocketBuffer::new(vec![0u8; 64 * 1024]);
        let sock = TcpSocket::new(rx_buf, tx_buf);
        let handle = sockets.add(sock);

        let remote = IpEndpoint::new(IpAddress::Ipv4(dst_ip), dst_port);
        sockets
            .get_mut::<TcpSocket>(handle)
            .connect(iface.context(), remote, src_port)
            .unwrap();

        let mut sent = false;
        let mut received = Vec::new();
        let mut reset_or_closed = false;

        let deadline = Instant::now() + Duration::from_secs(4);
        while Instant::now() < deadline {
            let now = SmolInstant::from_millis(start.elapsed().as_millis() as i64);
            iface.poll(now, &mut device, &mut sockets);

            let sock = sockets.get_mut::<TcpSocket>(handle);
            if !sent && sock.can_send() && !payload_to_send.is_empty() {
                if let Ok(n) = sock.send_slice(payload_to_send) {
                    if n == payload_to_send.len() {
                        sent = true;
                    }
                }
            }
            if sock.can_recv() {
                let _ = sock.recv(|data| {
                    received.extend_from_slice(data);
                    (data.len(), ())
                });
                if expect_reply && !received.is_empty() && !sock.may_recv() {
                    break;
                }
            }
            if matches!(sock.state(), TcpState::Closed | TcpState::TimeWait) {
                reset_or_closed = true;
                break;
            }
            if expect_reply && !received.is_empty() && sock.state() == TcpState::CloseWait {
                sock.close();
            }

            std::thread::sleep(Duration::from_millis(5));
        }

        (received, reset_or_closed)
    }

    #[test]
    fn tun_engine_relays_http_and_binary_tcp_through_socks5_and_blocks_rules() {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .unwrap();

        let (proxy_addr, _server_handle) = rt.block_on(async {
            let target_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let target_addr = target_listener.local_addr().unwrap();

            let server_handle = tokio::spawn(async move {
                while let Ok((mut conn, _)) = target_listener.accept().await {
                    tokio::spawn(async move {
                        let mut buf = vec![0u8; 4096];
                        let Ok(n) = conn.read(&mut buf).await else {
                            return;
                        };
                        if buf[..n].starts_with(b"GET ") {
                            let _ = conn
                                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 7\r\n\r\nKESTREL")
                                .await;
                        } else {
                            let _ = conn.write_all(&buf[..n]).await;
                        }
                        let _ = conn.shutdown().await;
                    });
                }
            });

            let proxy_addr = spawn_mock_socks5_redirect(target_addr).await;
            (proxy_addr, server_handle)
        });

        let mut fds = [0i32; 2];
        let rc = unsafe { libc::socketpair(libc::AF_UNIX, libc::SOCK_DGRAM, 0, fds.as_mut_ptr()) };
        assert_eq!(rc, 0);
        let tun_fd = fds[0];
        let client_fd = fds[1];
        let flags = unsafe { libc::fcntl(client_fd, libc::F_GETFL) };
        assert_eq!(
            unsafe { libc::fcntl(client_fd, libc::F_SETFL, flags | libc::O_NONBLOCK) },
            0
        );

        let config = CoreConfig {
            proxy: Some(ProxyConfig {
                protocol: ProxyProtocol::Socks5,
                host: "127.0.0.1".to_string(),
                port: proxy_addr.port(),
                username: None,
                password: None,
            }),
            dns: DnsConfig::default(),
            routing: RoutingConfig {
                default_action: RuleAction::Proxy,
                bypass_lan: true,
                rules: vec![RoutingRuleConfig {
                    id: "blk".to_string(),
                    name: "Block tracker".to_string(),
                    match_type: RuleMatchType::DomainSuffix,
                    value: "blocked.test".to_string(),
                    action: RuleAction::Block,
                    enabled: true,
                }],
            },
            mtu: 1500,
        };

        let engine = RunningEngine::start(tun_fd, config, Arc::new(NoopSocketProtector)).unwrap();

        // 1. HTTP GET via TUN -> smoltcp -> SOCKS5 -> target server
        let http_req = b"GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n";
        let (http_resp, _) = drive_client_tcp_exchange(
            client_fd,
            Ipv4Address::new(93, 184, 216, 34),
            80,
            41001,
            http_req,
            true,
        );
        let http_resp_str = String::from_utf8_lossy(&http_resp);
        assert!(
            http_resp_str.ends_with("KESTREL"),
            "Unexpected HTTP response over TUN: {http_resp_str}"
        );

        // 2. Binary MTProto-like payload (non-HTTP/non-TLS) echoed back via SOCKS5
        let binary_payload = b"\xEF\x01\x02\x03KESTREL_BINARY_FRAME\x99\x00";
        let (bin_resp, _) = drive_client_tcp_exchange(
            client_fd,
            Ipv4Address::new(149, 154, 167, 51),
            443,
            41002,
            binary_payload,
            true,
        );
        assert_eq!(bin_resp.as_slice(), binary_payload);

        // 3. BLOCK rule immediately resets/closes the client TCP socket
        let blocked_http = b"GET / HTTP/1.1\r\nHost: ads.blocked.test\r\n\r\n";
        let (blocked_resp, blocked_closed) = drive_client_tcp_exchange(
            client_fd,
            Ipv4Address::new(8, 8, 8, 8),
            80,
            41003,
            blocked_http,
            false,
        );
        assert!(blocked_resp.is_empty());
        assert!(blocked_closed);

        let stats_json = engine.stats_json();
        let stats: crate::stats::TrafficStats = serde_json::from_str(&stats_json).unwrap();
        assert!(stats.tx_bytes > 0);
        assert!(stats.rx_bytes > 0);

        let logs_json = engine.poll_logs_json(0, 50);
        let logs: Vec<crate::stats::ConnectionLogEntry> = serde_json::from_str(&logs_json).unwrap();
        assert!(logs
            .iter()
            .any(|l| l.host == "example.com" && l.action == RuleAction::Proxy));
        assert!(logs
            .iter()
            .any(|l| l.host == "ads.blocked.test" && l.action == RuleAction::Block));

        unsafe {
            libc::close(client_fd);
        }
        engine.stop();
    }
}
