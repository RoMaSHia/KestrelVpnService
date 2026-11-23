use std::io;

use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::config::ProxyConfig;
use crate::proxy::socket::{connect_proxy_endpoint, SocketProtector, TargetAddr};

const MAX_HTTP_HEADER_BYTES: usize = 8192;

/// Connects to `target` via HTTP/HTTPS `CONNECT` tunnel (with optional Basic Auth).
pub async fn connect_http_proxy(
    proxy: &ProxyConfig,
    target: &TargetAddr,
    dns_upstream: &str,
    protector: &dyn SocketProtector,
) -> io::Result<TcpStream> {
    let (mut stream, _) =
        connect_proxy_endpoint(&proxy.host, proxy.port, dns_upstream, protector).await?;

    let authority = target.format_host_port();
    let mut request = format!(
        "CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\nProxy-Connection: Keep-Alive\r\n"
    );

    let username = proxy.username.as_deref().unwrap_or("");
    let password = proxy.password.as_deref().unwrap_or("");
    if !username.is_empty() || !password.is_empty() {
        let token = STANDARD.encode(format!("{username}:{password}"));
        request.push_str(&format!("Proxy-Authorization: Basic {token}\r\n"));
    }
    request.push_str("\r\n");

    stream.write_all(request.as_bytes()).await?;

    // Read byte-by-byte until CRLFCRLF so we never over-read encrypted TLS payload bytes.
    let mut header_buf = Vec::with_capacity(256);
    let mut byte = [0u8; 1];
    loop {
        if header_buf.len() >= MAX_HTTP_HEADER_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "HTTP CONNECT response header exceeded 8 KB",
            ));
        }
        let n = stream.read(&mut byte).await?;
        if n == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "HTTP proxy closed connection during CONNECT",
            ));
        }
        header_buf.push(byte[0]);
        if header_buf.ends_with(b"\r\n\r\n") {
            break;
        }
    }

    let header_str = String::from_utf8_lossy(&header_buf);
    let status_line = header_str.lines().next().unwrap_or("");
    let status_code = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|code| code.parse::<u16>().ok())
        .ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                format!("Malformed HTTP CONNECT status line: {status_line}"),
            )
        })?;

    if !(200..300).contains(&status_code) {
        return Err(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            format!("HTTP CONNECT rejected with status {status_code} ({status_line})"),
        ));
    }

    Ok(stream)
}
