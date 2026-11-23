use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::os::unix::io::{AsRawFd, RawFd};
use std::time::Duration;

use socket2::{Domain, Protocol, Socket, Type};
use tokio::net::{TcpStream, UdpSocket};

pub const DEFAULT_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

/// Callback abstraction used to mark outbound sockets via Android `VpnService.protect(fd)`.
pub trait SocketProtector: Send + Sync {
    fn protect(&self, fd: RawFd) -> bool;
}

/// No-op protector used in host unit tests.
pub struct NoopSocketProtector;

impl SocketProtector for NoopSocketProtector {
    fn protect(&self, _fd: RawFd) -> bool {
        true
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TargetAddr {
    Domain(String, u16),
    Ip(SocketAddr),
}

impl TargetAddr {
    pub fn port(&self) -> u16 {
        match self {
            Self::Domain(_, p) => *p,
            Self::Ip(addr) => addr.port(),
        }
    }

    pub fn host_string(&self) -> String {
        match self {
            Self::Domain(host, _) => host.clone(),
            Self::Ip(addr) => addr.ip().to_string(),
        }
    }

    pub fn format_host_port(&self) -> String {
        match self {
            Self::Domain(host, port) => format!("{host}:{port}"),
            Self::Ip(SocketAddr::V4(v4)) => format!("{}:{}", v4.ip(), v4.port()),
            Self::Ip(SocketAddr::V6(v6)) => format!("[{}]:{}", v6.ip(), v6.port()),
        }
    }
}

/// Connects a non-blocking TCP socket after invoking `VpnService.protect(fd)` before `connect()`.
pub async fn connect_tcp_protected(
    addr: SocketAddr,
    protector: &dyn SocketProtector,
    timeout: Duration,
) -> io::Result<TcpStream> {
    let domain = if addr.is_ipv4() {
        Domain::IPV4
    } else {
        Domain::IPV6
    };

    let sock = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    sock.set_nonblocking(true)?;

    if !protector.protect(sock.as_raw_fd()) {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "VpnService.protect() rejected outbound TCP socket",
        ));
    }

    match sock.connect(&addr.into()) {
        Ok(()) => {}
        Err(err)
            if err.kind() == io::ErrorKind::WouldBlock
                || err.raw_os_error() == Some(libc::EINPROGRESS) => {}
        Err(err) => return Err(err),
    }

    let std_stream: std::net::TcpStream = sock.into();
    let stream = TcpStream::from_std(std_stream)?;

    tokio::time::timeout(timeout, stream.writable())
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "TCP connect timed out"))??;

    if let Some(err) = stream.take_error()? {
        return Err(err);
    }

    let _ = stream.set_nodelay(true);
    Ok(stream)
}

/// Binds an ephemeral UDP socket protected via `VpnService.protect(fd)`.
pub fn bind_udp_protected(
    is_ipv6: bool,
    protector: &dyn SocketProtector,
) -> io::Result<UdpSocket> {
    let (domain, bind_addr) = if is_ipv6 {
        (Domain::IPV6, SocketAddr::from(([0u16; 8], 0)))
    } else {
        (Domain::IPV4, SocketAddr::from(([0u8; 4], 0)))
    };

    let sock = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    sock.set_nonblocking(true)?;

    if !protector.protect(sock.as_raw_fd()) {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "VpnService.protect() rejected outbound UDP socket",
        ));
    }

    sock.bind(&bind_addr.into())?;
    let std_udp: std::net::UdpSocket = sock.into();
    UdpSocket::from_std(std_udp)
}

/// Resolves a host:port for establishing an outbound connection to a proxy server or direct target.
/// IP literals bypass DNS completely; hostnames are resolved via protected UDP DNS queries
/// (to prevent VPN routing loops) with system lookup fallback.
pub async fn resolve_host_protected(
    host: &str,
    port: u16,
    dns_upstream: &str,
    protector: &dyn SocketProtector,
) -> io::Result<Vec<SocketAddr>> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(vec![SocketAddr::new(ip, port)]);
    }

    if host.eq_ignore_ascii_case("localhost") {
        return Ok(vec![SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port)]);
    }

    if let Ok(dns_addr) = dns_upstream.parse::<SocketAddr>() {
        if let Ok(ips) = crate::dns::resolve_host_udp_protected(host, dns_addr, protector).await {
            if !ips.is_empty() {
                return Ok(ips
                    .into_iter()
                    .map(|ip| SocketAddr::new(ip, port))
                    .collect());
            }
        }
    }

    let addrs: Vec<SocketAddr> = tokio::net::lookup_host((host, port)).await?.collect();
    if addrs.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::AddrNotAvailable,
            format!("Failed to resolve host: {host}"),
        ));
    }
    Ok(addrs)
}

/// Connects to the proxy server itself, trying resolved addresses sequentially.
pub async fn connect_proxy_endpoint(
    host: &str,
    port: u16,
    dns_upstream: &str,
    protector: &dyn SocketProtector,
) -> io::Result<(TcpStream, SocketAddr)> {
    let addrs = resolve_host_protected(host, port, dns_upstream, protector).await?;
    let mut last_err = io::Error::new(io::ErrorKind::NotConnected, "No addresses resolved");
    for addr in addrs {
        match connect_tcp_protected(addr, protector, DEFAULT_CONNECT_TIMEOUT).await {
            Ok(stream) => return Ok((stream, addr)),
            Err(err) => last_err = err,
        }
    }
    Err(last_err)
}
