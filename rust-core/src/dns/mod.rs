use std::collections::{HashMap, VecDeque};
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::config::{CoreConfig, RuleAction};
use crate::proxy::connect_outbound_tcp;
use crate::proxy::socket::{bind_udp_protected, SocketProtector, TargetAddr};

const MAX_CACHE_ENTRIES: usize = 4096;
const CACHE_TTL: Duration = Duration::from_secs(30 * 60);
const DNS_TIMEOUT: Duration = Duration::from_secs(3);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DnsQuery {
    pub tx_id: u16,
    pub qname: String,
    pub qtype: u16,
    pub qclass: u16,
}

struct CachedDomain {
    domain: String,
    expires_at: Instant,
}

struct DnsCacheInner {
    map: HashMap<IpAddr, CachedDomain>,
    order: VecDeque<IpAddr>,
}

/// Thread-safe IP-to-domain cache populated by DNS responses to aid routing on raw IP connections.
pub struct DnsCache {
    inner: Mutex<DnsCacheInner>,
}

impl DnsCache {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(DnsCacheInner {
                map: HashMap::with_capacity(256),
                order: VecDeque::with_capacity(256),
            }),
        }
    }

    pub fn insert(&self, ip: IpAddr, domain: String) {
        if domain.is_empty() {
            return;
        }
        let normalized = domain.trim_end_matches('.').to_ascii_lowercase();
        let expires_at = Instant::now() + CACHE_TTL;

        if let Ok(mut guard) = self.inner.lock() {
            if !guard.map.contains_key(&ip) {
                while guard.map.len() >= MAX_CACHE_ENTRIES {
                    if let Some(oldest) = guard.order.pop_front() {
                        guard.map.remove(&oldest);
                    } else {
                        break;
                    }
                }
                guard.order.push_back(ip);
            }
            guard.map.insert(
                ip,
                CachedDomain {
                    domain: normalized,
                    expires_at,
                },
            );
        }
    }

    pub fn lookup(&self, ip: &IpAddr) -> Option<String> {
        let mut guard = self.inner.lock().ok()?;
        let now = Instant::now();
        if let Some(entry) = guard.map.get(ip) {
            if entry.expires_at > now {
                return Some(entry.domain.clone());
            }
        }
        guard.map.remove(ip);
        None
    }
}

impl Default for DnsCache {
    fn default() -> Self {
        Self::new()
    }
}

fn read_dns_name(packet: &[u8], start: usize) -> Option<(String, usize)> {
    let mut labels = Vec::new();
    let mut pos = start;
    let mut end_pos = None;
    let mut jumps = 0;

    while pos < packet.len() {
        let len = packet[pos];
        if len == 0 {
            if end_pos.is_none() {
                end_pos = Some(pos + 1);
            }
            break;
        }
        if (len & 0xC0) == 0xC0 {
            if pos + 1 >= packet.len() {
                return None;
            }
            if end_pos.is_none() {
                end_pos = Some(pos + 2);
            }
            let ptr = (((len & 0x3F) as usize) << 8) | (packet[pos + 1] as usize);
            if ptr >= packet.len() {
                return None;
            }
            pos = ptr;
            jumps += 1;
            if jumps > 16 {
                return None;
            }
            continue;
        }
        if (len & 0xC0) != 0 {
            return None;
        }
        let label_len = len as usize;
        pos += 1;
        if pos + label_len > packet.len() {
            return None;
        }
        let label = std::str::from_utf8(&packet[pos..pos + label_len]).ok()?;
        labels.push(label.to_ascii_lowercase());
        pos += label_len;
    }

    end_pos.map(|end| (labels.join("."), end))
}

fn skip_dns_name(packet: &[u8], start: usize) -> Option<usize> {
    let mut pos = start;
    let mut jumps = 0;
    while pos < packet.len() {
        let len = packet[pos];
        if len == 0 {
            return Some(pos + 1);
        }
        if (len & 0xC0) == 0xC0 {
            if pos + 1 >= packet.len() {
                return None;
            }
            return Some(pos + 2);
        }
        if (len & 0xC0) != 0 {
            return None;
        }
        pos += 1 + len as usize;
        jumps += 1;
        if jumps > 64 {
            return None;
        }
    }
    None
}

/// Parses an RFC 1035 DNS query packet and extracts the first question record.
pub fn parse_dns_query(packet: &[u8]) -> Option<DnsQuery> {
    if packet.len() < 12 {
        return None;
    }
    let tx_id = u16::from_be_bytes([packet[0], packet[1]]);
    let flags = u16::from_be_bytes([packet[2], packet[3]]);
    let qdcount = u16::from_be_bytes([packet[4], packet[5]]);

    // Must be a standard query (QR == 0) with at least one question
    if (flags & 0x8000) != 0 || qdcount == 0 {
        return None;
    }

    let (qname, q_end) = read_dns_name(packet, 12)?;
    if q_end + 4 > packet.len() {
        return None;
    }
    let qtype = u16::from_be_bytes([packet[q_end], packet[q_end + 1]]);
    let qclass = u16::from_be_bytes([packet[q_end + 2], packet[q_end + 3]]);

    Some(DnsQuery {
        tx_id,
        qname,
        qtype,
        qclass,
    })
}

/// Extracts resolved A (IPv4) and AAAA (IPv6) addresses from an RFC 1035 DNS response.
pub fn parse_dns_response_ips(packet: &[u8]) -> Vec<IpAddr> {
    let mut out = Vec::new();
    if packet.len() < 12 {
        return out;
    }

    let qdcount = u16::from_be_bytes([packet[4], packet[5]]) as usize;
    let ancount = u16::from_be_bytes([packet[6], packet[7]]) as usize;
    let mut pos = 12;

    for _ in 0..qdcount {
        let Some(next) = skip_dns_name(packet, pos) else {
            return out;
        };
        if next + 4 > packet.len() {
            return out;
        }
        pos = next + 4;
    }

    for _ in 0..ancount {
        let Some(next) = skip_dns_name(packet, pos) else {
            return out;
        };
        if next + 10 > packet.len() {
            return out;
        }
        let rtype = u16::from_be_bytes([packet[next], packet[next + 1]]);
        let rdlength = u16::from_be_bytes([packet[next + 8], packet[next + 9]]) as usize;
        let rdata_start = next + 10;
        let rdata_end = rdata_start + rdlength;
        if rdata_end > packet.len() {
            return out;
        }

        if rtype == 1 && rdlength == 4 {
            let octets: [u8; 4] = packet[rdata_start..rdata_end].try_into().unwrap();
            out.push(IpAddr::V4(Ipv4Addr::from(octets)));
        } else if rtype == 28 && rdlength == 16 {
            let octets: [u8; 16] = packet[rdata_start..rdata_end].try_into().unwrap();
            out.push(IpAddr::V6(Ipv6Addr::from(octets)));
        }
        pos = rdata_end;
    }

    out
}

/// Synthesizes an immediate NXDOMAIN DNS response for blocked domains.
pub fn build_nxdomain_response(query: &[u8]) -> Option<Vec<u8>> {
    if query.len() < 12 {
        return None;
    }
    let name_end = skip_dns_name(query, 12)?;
    let q_end = name_end + 4;
    if q_end > query.len() {
        return None;
    }

    let mut resp = query[..q_end].to_vec();
    resp[2] = (resp[2] | 0x80) & !0x02; // QR=1, TC=0
    resp[3] = (resp[3] & 0x70) | 0x80 | 0x03; // RA=1, RCODE=3 (NXDOMAIN)
    resp[4..6].copy_from_slice(&1u16.to_be_bytes());
    resp[6..12].fill(0);
    Some(resp)
}

/// Builds a minimal RFC 1035 A-record query packet for internal host resolution.
pub fn build_a_query(tx_id: u16, host: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(64);
    out.extend_from_slice(&tx_id.to_be_bytes());
    out.extend_from_slice(&[0x01, 0x00]); // RD=1
    out.extend_from_slice(&1u16.to_be_bytes()); // QDCOUNT=1
    out.extend_from_slice(&[0u8; 6]);

    for label in host.trim_end_matches('.').split('.') {
        if label.is_empty() || label.len() > 63 {
            return None;
        }
        out.push(label.len() as u8);
        out.extend_from_slice(label.as_bytes());
    }
    out.push(0);
    out.extend_from_slice(&1u16.to_be_bytes()); // QTYPE A
    out.extend_from_slice(&1u16.to_be_bytes()); // QCLASS IN
    Some(out)
}

/// Resolves a hostname to IP addresses via a protected UDP socket directly to `dns_addr`.
pub async fn resolve_host_udp_protected(
    host: &str,
    dns_addr: SocketAddr,
    protector: &dyn SocketProtector,
) -> io::Result<Vec<IpAddr>> {
    let query = build_a_query(0x4B53, host).ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, "Invalid DNS hostname")
    })?;
    let resp = send_udp_dns(&query, dns_addr, protector).await?;
    Ok(parse_dns_response_ips(&resp))
}

async fn send_udp_dns(
    query: &[u8],
    server: SocketAddr,
    protector: &dyn SocketProtector,
) -> io::Result<Vec<u8>> {
    let sock = bind_udp_protected(server.is_ipv6(), protector)?;
    sock.connect(server).await?;
    sock.send(query).await?;

    let mut buf = vec![0u8; 2048];
    let n = tokio::time::timeout(DNS_TIMEOUT, sock.recv(&mut buf))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "UDP DNS request timed out"))??;
    buf.truncate(n);
    Ok(buf)
}

async fn send_tcp_dns_via_proxy(
    query: &[u8],
    server: SocketAddr,
    cfg: &CoreConfig,
    protector: &dyn SocketProtector,
) -> io::Result<Vec<u8>> {
    let mut stream = tokio::time::timeout(
        DNS_TIMEOUT,
        connect_outbound_tcp(
            RuleAction::Proxy,
            cfg.proxy.as_ref(),
            &TargetAddr::Ip(server),
            &cfg.dns.upstream,
            protector,
        ),
    )
    .await
    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "Proxy DNS connect timed out"))??;

    let len_prefix = (query.len() as u16).to_be_bytes();
    stream.write_all(&len_prefix).await?;
    stream.write_all(query).await?;

    let mut resp_len_buf = [0u8; 2];
    tokio::time::timeout(DNS_TIMEOUT, stream.read_exact(&mut resp_len_buf))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "Proxy DNS read timed out"))??;
    let resp_len = u16::from_be_bytes(resp_len_buf) as usize;
    if resp_len == 0 || resp_len > 4096 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Invalid DNS-over-TCP response length",
        ));
    }

    let mut resp = vec![0u8; resp_len];
    tokio::time::timeout(DNS_TIMEOUT, stream.read_exact(&mut resp))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "Proxy DNS payload timed out"))??;
    Ok(resp)
}

fn parse_dns_addr(raw: &str) -> SocketAddr {
    if let Ok(addr) = raw.parse::<SocketAddr>() {
        return addr;
    }
    if let Ok(ip) = raw.parse::<IpAddr>() {
        return SocketAddr::new(ip, 53);
    }
    SocketAddr::from((Ipv4Addr::new(1, 1, 1, 1), 53))
}

/// Forwards an intercepted DNS query according to the routing decision (`BLOCK`, `DIRECT`, or `PROXY`),
/// avoiding DNS leaks when routed via `PROXY` by using DNS-over-TCP inside the proxy tunnel.
pub async fn forward_dns_query(
    query_packet: &[u8],
    action: RuleAction,
    cfg: &CoreConfig,
    protector: &dyn SocketProtector,
) -> io::Result<Vec<u8>> {
    if action == RuleAction::Block {
        return build_nxdomain_response(query_packet).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "Malformed DNS query for NXDOMAIN")
        });
    }

    let primary = parse_dns_addr(&cfg.dns.upstream);
    let fallback = parse_dns_addr(&cfg.dns.fallback);

    let effective_action = if action == RuleAction::Proxy && (!cfg.dns.route_dns || cfg.proxy.is_none()) {
        RuleAction::Direct
    } else {
        action
    };

    match effective_action {
        RuleAction::Direct => match send_udp_dns(query_packet, primary, protector).await {
            Ok(resp) => Ok(resp),
            Err(_) if fallback != primary => send_udp_dns(query_packet, fallback, protector).await,
            Err(err) => Err(err),
        },
        RuleAction::Proxy => match send_tcp_dns_via_proxy(query_packet, primary, cfg, protector).await {
            Ok(resp) => Ok(resp),
            Err(err) => {
                log::debug!("Proxy DNS-over-TCP to {primary} failed ({err}), trying fallback");
                if fallback != primary {
                    if let Ok(resp) =
                        send_tcp_dns_via_proxy(query_packet, fallback, cfg, protector).await
                    {
                        return Ok(resp);
                    }
                }
                // Fallback to protected direct UDP DNS when the proxy blocks outbound port 53
                match send_udp_dns(query_packet, primary, protector).await {
                    Ok(resp) => Ok(resp),
                    Err(_) if fallback != primary => {
                        send_udp_dns(query_packet, fallback, protector).await
                    }
                    Err(udp_err) => Err(udp_err),
                }
            }
        },
        RuleAction::Block => unreachable!(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_dns_query_and_builds_nxdomain() {
        let query = build_a_query(0x1234, "Api.Example.COM").unwrap();
        let parsed = parse_dns_query(&query).unwrap();
        assert_eq!(parsed.tx_id, 0x1234);
        assert_eq!(parsed.qname, "api.example.com");
        assert_eq!(parsed.qtype, 1);

        let nx = build_nxdomain_response(&query).unwrap();
        assert_eq!(nx[0..2], [0x12, 0x34]);
        assert_ne!(nx[2] & 0x80, 0); // QR=1
        assert_eq!(nx[3] & 0x0F, 3); // NXDOMAIN
    }

    #[test]
    fn extracts_a_and_aaaa_answers_and_updates_cache() {
        let mut resp = build_a_query(0x9999, "cdn.example.org").unwrap();
        resp[2] = 0x81;
        resp[3] = 0x80;
        resp[6..8].copy_from_slice(&2u16.to_be_bytes()); // ANCOUNT=2
        // Answer 1: compressed name 0xC00C, type A (1), class IN (1), TTL 60, len 4, 93.184.216.34
        resp.extend_from_slice(&[
            0xC0, 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3C, 0x00, 0x04, 93, 184, 216,
            34,
        ]);
        // Answer 2: compressed name 0xC00C, type AAAA (28), class IN (1), TTL 60, len 16
        resp.extend_from_slice(&[
            0xC0, 0x0C, 0x00, 0x1C, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3C, 0x00, 0x10, 0x26, 0x06,
            0x28, 0x00, 0x02, 0x20, 0x00, 0x01, 0x02, 0x48, 0x18, 0x93, 0x25, 0xc8, 0x19, 0x46,
        ]);

        let ips = parse_dns_response_ips(&resp);
        assert_eq!(ips.len(), 2);
        assert_eq!(ips[0], IpAddr::V4(Ipv4Addr::new(93, 184, 216, 34)));

        let cache = DnsCache::new();
        for ip in ips {
            cache.insert(ip, "cdn.example.org".to_string());
        }
        assert_eq!(
            cache.lookup(&IpAddr::V4(Ipv4Addr::new(93, 184, 216, 34))).as_deref(),
            Some("cdn.example.org")
        );
    }
}
