use std::io;
use std::net::{IpAddr, SocketAddr};

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::config::ProxyConfig;
use crate::proxy::socket::{
    connect_proxy_endpoint, resolve_host_protected, SocketProtector, TargetAddr,
};

const SOCKS4_VERSION: u8 = 0x04;
const CMD_CONNECT: u8 = 0x01;
const REPLY_GRANTED: u8 = 0x5A;

/// Connects to `target` over SOCKS4 or SOCKS4a using a protected outbound socket.
pub async fn connect_socks4_tcp(
    proxy: &ProxyConfig,
    target: &TargetAddr,
    dns_upstream: &str,
    protector: &dyn SocketProtector,
    allow_socks4a: bool,
) -> io::Result<TcpStream> {
    let (mut stream, _) =
        connect_proxy_endpoint(&proxy.host, proxy.port, dns_upstream, protector).await?;

    let user_id = proxy.username.as_deref().unwrap_or("").as_bytes();
    let mut req = Vec::with_capacity(64);
    req.push(SOCKS4_VERSION);
    req.push(CMD_CONNECT);

    match target {
        TargetAddr::Ip(SocketAddr::V4(v4)) => {
            req.extend_from_slice(&v4.port().to_be_bytes());
            req.extend_from_slice(&v4.ip().octets());
            req.extend_from_slice(user_id);
            req.push(0x00);
        }
        TargetAddr::Ip(SocketAddr::V6(_)) => {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "SOCKS4/4a does not support raw IPv6 targets",
            ));
        }
        TargetAddr::Domain(host, port) => {
            if allow_socks4a {
                req.extend_from_slice(&port.to_be_bytes());
                req.extend_from_slice(&[0, 0, 0, 1]);
                req.extend_from_slice(user_id);
                req.push(0x00);
                req.extend_from_slice(host.as_bytes());
                req.push(0x00);
            } else {
                let resolved =
                    resolve_host_protected(host, *port, dns_upstream, protector).await?;
                let v4 = resolved
                    .into_iter()
                    .find_map(|addr| match addr.ip() {
                        IpAddr::V4(ip) => Some(ip),
                        IpAddr::V6(_) => None,
                    })
                    .ok_or_else(|| {
                        io::Error::new(
                            io::ErrorKind::Unsupported,
                            "SOCKS4 requires an IPv4 address for target",
                        )
                    })?;
                req.extend_from_slice(&port.to_be_bytes());
                req.extend_from_slice(&v4.octets());
                req.extend_from_slice(user_id);
                req.push(0x00);
            }
        }
    }

    stream.write_all(&req).await?;

    let mut resp = [0u8; 8];
    stream.read_exact(&mut resp).await?;
    if resp[1] != REPLY_GRANTED {
        return Err(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            format!("SOCKS4/4a connection rejected with code 0x{:02x}", resp[1]),
        ));
    }

    Ok(stream)
}
