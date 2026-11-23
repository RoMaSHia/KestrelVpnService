pub mod http_connect;
pub mod socket;
pub mod socks4;
pub mod socks5;

use std::io;

use tokio::net::TcpStream;

use crate::config::{ProxyConfig, ProxyProtocol, RuleAction};
use crate::proxy::http_connect::connect_http_proxy;
use crate::proxy::socket::{
    connect_tcp_protected, resolve_host_protected, SocketProtector, TargetAddr,
    DEFAULT_CONNECT_TIMEOUT,
};
use crate::proxy::socks4::connect_socks4_tcp;
use crate::proxy::socks5::connect_socks5_tcp;

/// Establishes an outbound TCP stream either directly or through the configured proxy.
pub async fn connect_outbound_tcp(
    action: RuleAction,
    proxy: Option<&ProxyConfig>,
    target: &TargetAddr,
    dns_upstream: &str,
    protector: &dyn SocketProtector,
) -> io::Result<TcpStream> {
    match action {
        RuleAction::Block => Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "Connection blocked by routing rule",
        )),
        RuleAction::Direct => connect_direct_tcp(target, dns_upstream, protector).await,
        RuleAction::Proxy => {
            let proxy_cfg = proxy.ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::NotConnected,
                    "No active proxy profile configured",
                )
            })?;

            match proxy_cfg.protocol {
                ProxyProtocol::Socks5 => {
                    connect_socks5_tcp(proxy_cfg, target, dns_upstream, protector).await
                }
                ProxyProtocol::Socks4 => {
                    connect_socks4_tcp(proxy_cfg, target, dns_upstream, protector, false).await
                }
                ProxyProtocol::Socks4a => {
                    connect_socks4_tcp(proxy_cfg, target, dns_upstream, protector, true).await
                }
                ProxyProtocol::Http => {
                    connect_http_proxy(proxy_cfg, target, dns_upstream, protector).await
                }
            }
        }
    }
}

async fn connect_direct_tcp(
    target: &TargetAddr,
    dns_upstream: &str,
    protector: &dyn SocketProtector,
) -> io::Result<TcpStream> {
    match target {
        TargetAddr::Ip(addr) => {
            connect_tcp_protected(*addr, protector, DEFAULT_CONNECT_TIMEOUT).await
        }
        TargetAddr::Domain(host, port) => {
            let addrs = resolve_host_protected(host, *port, dns_upstream, protector).await?;
            let mut last_err = io::Error::new(io::ErrorKind::NotConnected, "No direct targets");
            for addr in addrs {
                match connect_tcp_protected(addr, protector, DEFAULT_CONNECT_TIMEOUT).await {
                    Ok(s) => return Ok(s),
                    Err(e) => last_err = e,
                }
            }
            Err(last_err)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proxy::socket::NoopSocketProtector;
    use crate::proxy::socks5::{decode_socks5_udp_datagram, encode_socks5_udp_datagram};
    use std::net::{Ipv4Addr, SocketAddr};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    #[tokio::test]
    async fn socks5_auth_and_connect_roundtrip() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        let server = tokio::spawn(async move {
            let (mut sock, _) = listener.accept().await.unwrap();
            let mut greet = [0u8; 4];
            sock.read_exact(&mut greet).await.unwrap();
            assert_eq!(greet, [0x05, 0x02, 0x00, 0x02]);
            sock.write_all(&[0x05, 0x02]).await.unwrap();

            let mut auth_hdr = [0u8; 2];
            sock.read_exact(&mut auth_hdr).await.unwrap();
            assert_eq!(auth_hdr[0], 0x01);
            let mut u = vec![0u8; auth_hdr[1] as usize];
            sock.read_exact(&mut u).await.unwrap();
            let mut plen = [0u8; 1];
            sock.read_exact(&mut plen).await.unwrap();
            let mut p = vec![0u8; plen[0] as usize];
            sock.read_exact(&mut p).await.unwrap();
            assert_eq!(u, b"alice");
            assert_eq!(p, b"secret");
            sock.write_all(&[0x01, 0x00]).await.unwrap();

            let mut req_hdr = [0u8; 5];
            sock.read_exact(&mut req_hdr).await.unwrap();
            assert_eq!(req_hdr[0..4], [0x05, 0x01, 0x00, 0x03]);
            let mut rest = vec![0u8; req_hdr[4] as usize + 2];
            sock.read_exact(&mut rest).await.unwrap();
            assert_eq!(&rest[..req_hdr[4] as usize], b"example.com");
            sock.write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x01, 0xbb])
                .await
                .unwrap();

            sock.write_all(b"HELLO").await.unwrap();
        });

        let cfg = ProxyConfig {
            protocol: ProxyProtocol::Socks5,
            host: "127.0.0.1".to_string(),
            port: addr.port(),
            username: Some("alice".to_string()),
            password: Some("secret".to_string()),
        };

        let mut client = connect_outbound_tcp(
            RuleAction::Proxy,
            Some(&cfg),
            &TargetAddr::Domain("example.com".to_string(), 443),
            "127.0.0.1:53",
            &NoopSocketProtector,
        )
        .await
        .unwrap();

        let mut buf = [0u8; 5];
        client.read_exact(&mut buf).await.unwrap();
        assert_eq!(&buf, b"HELLO");
        server.await.unwrap();
    }

    #[tokio::test]
    async fn socks4a_connect_roundtrip() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        let server = tokio::spawn(async move {
            let (mut sock, _) = listener.accept().await.unwrap();
            let mut buf = [0u8; 64];
            let n = sock.read(&mut buf).await.unwrap();
            assert_eq!(buf[0], 0x04);
            assert_eq!(buf[1], 0x01);
            assert_eq!(&buf[4..8], &[0, 0, 0, 1]);
            assert!(buf[..n].windows(b"test.org".len()).any(|w| w == b"test.org"));
            sock.write_all(&[0x00, 0x5A, 0, 0, 0, 0, 0, 0])
                .await
                .unwrap();
            sock.write_all(b"OK4A").await.unwrap();
        });

        let cfg = ProxyConfig {
            protocol: ProxyProtocol::Socks4a,
            host: "127.0.0.1".to_string(),
            port: addr.port(),
            username: Some("user".to_string()),
            password: None,
        };

        let mut client = connect_outbound_tcp(
            RuleAction::Proxy,
            Some(&cfg),
            &TargetAddr::Domain("test.org".to_string(), 80),
            "127.0.0.1:53",
            &NoopSocketProtector,
        )
        .await
        .unwrap();

        let mut out = [0u8; 4];
        client.read_exact(&mut out).await.unwrap();
        assert_eq!(&out, b"OK4A");
        server.await.unwrap();
    }

    #[tokio::test]
    async fn http_connect_basic_auth_preserves_pipelined_bytes() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        let server = tokio::spawn(async move {
            let (mut sock, _) = listener.accept().await.unwrap();
            let mut buf = [0u8; 512];
            let n = sock.read(&mut buf).await.unwrap();
            let req = String::from_utf8_lossy(&buf[..n]);
            assert!(req.starts_with("CONNECT example.org:443 HTTP/1.1"));
            assert!(req.contains("Proxy-Authorization: Basic "));
            sock.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\nTLS_BYTES")
                .await
                .unwrap();
        });

        let cfg = ProxyConfig {
            protocol: ProxyProtocol::Http,
            host: "127.0.0.1".to_string(),
            port: addr.port(),
            username: Some("u1".to_string()),
            password: Some("p1".to_string()),
        };

        let mut client = connect_outbound_tcp(
            RuleAction::Proxy,
            Some(&cfg),
            &TargetAddr::Domain("example.org".to_string(), 443),
            "127.0.0.1:53",
            &NoopSocketProtector,
        )
        .await
        .unwrap();

        let mut out = [0u8; 9];
        client.read_exact(&mut out).await.unwrap();
        assert_eq!(&out, b"TLS_BYTES");
        server.await.unwrap();
    }

    #[test]
    fn socks5_udp_datagram_encode_decode() {
        let target = TargetAddr::Ip(SocketAddr::from((Ipv4Addr::new(1, 1, 1, 1), 53)));
        let pkt = encode_socks5_udp_datagram(&target, b"dns-payload").unwrap();
        let (decoded_addr, payload) = decode_socks5_udp_datagram(&pkt).unwrap();
        assert_eq!(decoded_addr, SocketAddr::from((Ipv4Addr::new(1, 1, 1, 1), 53)));
        assert_eq!(payload, b"dns-payload");
    }
}
