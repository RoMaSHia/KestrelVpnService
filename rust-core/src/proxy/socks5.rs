use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};

use crate::config::ProxyConfig;
use crate::proxy::socket::{
    bind_udp_protected, connect_proxy_endpoint, SocketProtector, TargetAddr,
};

const SOCKS5_VERSION: u8 = 0x05;
const AUTH_NONE: u8 = 0x00;
const AUTH_USERPASS: u8 = 0x02;
const AUTH_NO_ACCEPTABLE: u8 = 0xFF;

const CMD_CONNECT: u8 = 0x01;
const CMD_UDP_ASSOCIATE: u8 = 0x03;

const ATYP_IPV4: u8 = 0x01;
const ATYP_DOMAIN: u8 = 0x03;
const ATYP_IPV6: u8 = 0x04;

async fn negotiate_socks5(
    stream: &mut TcpStream,
    proxy: &ProxyConfig,
) -> io::Result<()> {
    let username = proxy.username.as_deref().unwrap_or("");
    let password = proxy.password.as_deref().unwrap_or("");
    let has_auth = !username.is_empty() || !password.is_empty();

    let greeting: &[u8] = if has_auth {
        &[SOCKS5_VERSION, 2, AUTH_NONE, AUTH_USERPASS]
    } else {
        &[SOCKS5_VERSION, 1, AUTH_NONE]
    };
    stream.write_all(greeting).await?;

    let mut method_resp = [0u8; 2];
    stream.read_exact(&mut method_resp).await?;
    if method_resp[0] != SOCKS5_VERSION {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Invalid SOCKS5 version in greeting response",
        ));
    }

    match method_resp[1] {
        AUTH_NONE => Ok(()),
        AUTH_USERPASS => {
            if !has_auth {
                return Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "SOCKS5 proxy requires authentication",
                ));
            }
            let u_bytes = username.as_bytes();
            let p_bytes = password.as_bytes();
            if u_bytes.len() > 255 || p_bytes.len() > 255 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "SOCKS5 credentials exceed 255 bytes",
                ));
            }

            let mut auth_req = Vec::with_capacity(3 + u_bytes.len() + p_bytes.len());
            auth_req.push(0x01); // RFC 1929 subnegotiation version
            auth_req.push(u_bytes.len() as u8);
            auth_req.extend_from_slice(u_bytes);
            auth_req.push(p_bytes.len() as u8);
            auth_req.extend_from_slice(p_bytes);
            stream.write_all(&auth_req).await?;

            let mut auth_resp = [0u8; 2];
            stream.read_exact(&mut auth_resp).await?;
            if auth_resp[1] != 0x00 {
                return Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "SOCKS5 authentication rejected",
                ));
            }
            Ok(())
        }
        AUTH_NO_ACCEPTABLE => Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "SOCKS5 proxy rejected authentication methods",
        )),
        other => Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("Unsupported SOCKS5 auth method: {other}"),
        )),
    }
}

fn push_target_addr(buf: &mut Vec<u8>, target: &TargetAddr) -> io::Result<()> {
    match target {
        TargetAddr::Ip(SocketAddr::V4(v4)) => {
            buf.push(ATYP_IPV4);
            buf.extend_from_slice(&v4.ip().octets());
            buf.extend_from_slice(&v4.port().to_be_bytes());
        }
        TargetAddr::Ip(SocketAddr::V6(v6)) => {
            buf.push(ATYP_IPV6);
            buf.extend_from_slice(&v6.ip().octets());
            buf.extend_from_slice(&v6.port().to_be_bytes());
        }
        TargetAddr::Domain(host, port) => {
            let domain_bytes = host.as_bytes();
            if domain_bytes.len() > 255 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "Domain exceeds 255 bytes for SOCKS5",
                ));
            }
            buf.push(ATYP_DOMAIN);
            buf.push(domain_bytes.len() as u8);
            buf.extend_from_slice(domain_bytes);
            buf.extend_from_slice(&port.to_be_bytes());
        }
    }
    Ok(())
}

async fn read_socks5_reply_addr(stream: &mut TcpStream) -> io::Result<SocketAddr> {
    let mut header = [0u8; 4];
    stream.read_exact(&mut header).await?;
    if header[0] != SOCKS5_VERSION {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Invalid SOCKS5 reply version",
        ));
    }
    if header[1] != 0x00 {
        return Err(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            format!("SOCKS5 command failed with status 0x{:02x}", header[1]),
        ));
    }

    let ip = match header[3] {
        ATYP_IPV4 => {
            let mut octets = [0u8; 4];
            stream.read_exact(&mut octets).await?;
            IpAddr::V4(Ipv4Addr::from(octets))
        }
        ATYP_IPV6 => {
            let mut octets = [0u8; 16];
            stream.read_exact(&mut octets).await?;
            IpAddr::V6(Ipv6Addr::from(octets))
        }
        ATYP_DOMAIN => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).await?;
            let mut domain = vec![0u8; len[0] as usize];
            stream.read_exact(&mut domain).await?;
            IpAddr::V4(Ipv4Addr::UNSPECIFIED)
        }
        other => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("Unknown SOCKS5 address type: {other}"),
            ));
        }
    };

    let mut port_buf = [0u8; 2];
    stream.read_exact(&mut port_buf).await?;
    let port = u16::from_be_bytes(port_buf);
    Ok(SocketAddr::new(ip, port))
}

/// Connects to `target` through a SOCKS5 proxy using a protected outbound socket.
pub async fn connect_socks5_tcp(
    proxy: &ProxyConfig,
    target: &TargetAddr,
    dns_upstream: &str,
    protector: &dyn SocketProtector,
) -> io::Result<TcpStream> {
    let (mut stream, _) =
        connect_proxy_endpoint(&proxy.host, proxy.port, dns_upstream, protector).await?;
    negotiate_socks5(&mut stream, proxy).await?;

    let mut req = Vec::with_capacity(64);
    req.extend_from_slice(&[SOCKS5_VERSION, CMD_CONNECT, 0x00]);
    push_target_addr(&mut req, target)?;
    stream.write_all(&req).await?;

    read_socks5_reply_addr(&mut stream).await?;
    Ok(stream)
}

/// Active SOCKS5 UDP Associate session maintaining the TCP control channel and protected UDP socket.
pub struct Socks5UdpSession {
    _control_stream: TcpStream,
    udp_socket: UdpSocket,
    relay_addr: SocketAddr,
}

impl Socks5UdpSession {
    pub async fn associate(
        proxy: &ProxyConfig,
        dns_upstream: &str,
        protector: &dyn SocketProtector,
    ) -> io::Result<Self> {
        let (mut control, proxy_peer) =
            connect_proxy_endpoint(&proxy.host, proxy.port, dns_upstream, protector).await?;
        negotiate_socks5(&mut control, proxy).await?;

        let zero_target = TargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0));
        let mut req = Vec::with_capacity(16);
        req.extend_from_slice(&[SOCKS5_VERSION, CMD_UDP_ASSOCIATE, 0x00]);
        push_target_addr(&mut req, &zero_target)?;
        control.write_all(&req).await?;

        let mut relay_addr = read_socks5_reply_addr(&mut control).await?;
        if relay_addr.ip().is_unspecified() {
            relay_addr.set_ip(proxy_peer.ip());
        }
        if relay_addr.port() == 0 {
            return Err(io::Error::new(
                io::ErrorKind::AddrNotAvailable,
                "SOCKS5 UDP relay returned port 0",
            ));
        }

        let udp_socket = bind_udp_protected(relay_addr.is_ipv6(), protector)?;
        udp_socket.connect(relay_addr).await?;

        Ok(Self {
            _control_stream: control,
            udp_socket,
            relay_addr,
        })
    }

    pub fn relay_addr(&self) -> SocketAddr {
        self.relay_addr
    }

    pub async fn send_to(&self, target: &TargetAddr, payload: &[u8]) -> io::Result<usize> {
        let packet = encode_socks5_udp_datagram(target, payload)?;
        self.udp_socket.send(&packet).await
    }

    pub async fn recv(&self, buf: &mut [u8]) -> io::Result<(SocketAddr, Vec<u8>)> {
        let n = self.udp_socket.recv(buf).await?;
        let (src_addr, payload) = decode_socks5_udp_datagram(&buf[..n])?;
        Ok((src_addr, payload.to_vec()))
    }
}

/// Encodes a SOCKS5 UDP request header (`RSV=0, FRAG=0, ATYP, DST.ADDR, DST.PORT, DATA`).
pub fn encode_socks5_udp_datagram(target: &TargetAddr, payload: &[u8]) -> io::Result<Vec<u8>> {
    let mut out = Vec::with_capacity(16 + payload.len());
    out.extend_from_slice(&[0x00, 0x00, 0x00]); // RSV (2) + FRAG (1)
    push_target_addr(&mut out, target)?;
    out.extend_from_slice(payload);
    Ok(out)
}

/// Decodes a SOCKS5 UDP datagram header and returns `(source_addr, payload_slice)`.
pub fn decode_socks5_udp_datagram(packet: &[u8]) -> io::Result<(SocketAddr, &[u8])> {
    if packet.len() < 4 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "SOCKS5 UDP packet too short",
        ));
    }
    if packet[2] != 0x00 {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "Fragmented SOCKS5 UDP packets are not supported",
        ));
    }

    let (ip, offset) = match packet[3] {
        ATYP_IPV4 => {
            if packet.len() < 10 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "Truncated IPv4 SOCKS5 UDP header",
                ));
            }
            let octets: [u8; 4] = packet[4..8].try_into().unwrap();
            (IpAddr::V4(Ipv4Addr::from(octets)), 8)
        }
        ATYP_IPV6 => {
            if packet.len() < 22 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "Truncated IPv6 SOCKS5 UDP header",
                ));
            }
            let octets: [u8; 16] = packet[4..20].try_into().unwrap();
            (IpAddr::V6(Ipv6Addr::from(octets)), 20)
        }
        ATYP_DOMAIN => {
            if packet.len() < 5 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "Truncated domain SOCKS5 UDP header",
                ));
            }
            let len = packet[4] as usize;
            if packet.len() < 5 + len + 2 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "Truncated domain SOCKS5 UDP packet",
                ));
            }
            (IpAddr::V4(Ipv4Addr::UNSPECIFIED), 5 + len)
        }
        other => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("Unsupported SOCKS5 UDP ATYP: {other}"),
            ));
        }
    };

    let port = u16::from_be_bytes([packet[offset], packet[offset + 1]]);
    Ok((SocketAddr::new(ip, port), &packet[offset + 2..]))
}
