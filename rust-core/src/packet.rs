use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportProto {
    Tcp,
    Udp,
    Other(u8),
}

#[derive(Debug)]
pub struct ParsedIpPacket<'a> {
    pub src_ip: IpAddr,
    pub dst_ip: IpAddr,
    pub proto: TransportProto,
    pub src_port: u16,
    pub dst_port: u16,
    pub tcp_flags: u8,
    pub payload: &'a [u8],
}

/// Parses a raw Layer-3 IPv4 or IPv6 packet from the Android TUN device.
pub fn parse_ip_packet(raw: &[u8]) -> Option<ParsedIpPacket<'_>> {
    if raw.is_empty() {
        return None;
    }
    match raw[0] >> 4 {
        4 => parse_ipv4(raw),
        6 => parse_ipv6(raw),
        _ => None,
    }
}

fn parse_ipv4(raw: &[u8]) -> Option<ParsedIpPacket<'_>> {
    if raw.len() < 20 {
        return None;
    }
    let ihl = ((raw[0] & 0x0F) as usize) * 4;
    let total_len = u16::from_be_bytes([raw[2], raw[3]]) as usize;
    if ihl < 20 || total_len < ihl || total_len > raw.len() {
        return None;
    }
    let frag_offset = u16::from_be_bytes([raw[6], raw[7]]) & 0x1FFF;
    if frag_offset != 0 {
        return None;
    }

    let src_ip = IpAddr::V4(Ipv4Addr::new(raw[12], raw[13], raw[14], raw[15]));
    let dst_ip = IpAddr::V4(Ipv4Addr::new(raw[16], raw[17], raw[18], raw[19]));
    let proto_num = raw[9];
    let l4 = &raw[ihl..total_len];

    parse_l4(src_ip, dst_ip, proto_num, l4)
}

fn parse_ipv6(raw: &[u8]) -> Option<ParsedIpPacket<'_>> {
    if raw.len() < 40 {
        return None;
    }
    let payload_len = u16::from_be_bytes([raw[4], raw[5]]) as usize;
    let total_len = (40 + payload_len).min(raw.len());
    let src_octets: [u8; 16] = raw[8..24].try_into().ok()?;
    let dst_octets: [u8; 16] = raw[24..40].try_into().ok()?;
    let src_ip = IpAddr::V6(Ipv6Addr::from(src_octets));
    let dst_ip = IpAddr::V6(Ipv6Addr::from(dst_octets));

    let mut next_hdr = raw[6];
    let mut offset = 40usize;
    let mut hops = 0usize;
    while matches!(next_hdr, 0 | 43 | 60) {
        if offset + 2 > total_len || hops >= 8 {
            return None;
        }
        next_hdr = raw[offset];
        let ext_len = ((raw[offset + 1] as usize) + 1) * 8;
        offset += ext_len;
        hops += 1;
    }
    if offset > total_len {
        return None;
    }

    parse_l4(src_ip, dst_ip, next_hdr, &raw[offset..total_len])
}

fn parse_l4<'a>(
    src_ip: IpAddr,
    dst_ip: IpAddr,
    proto_num: u8,
    l4: &'a [u8],
) -> Option<ParsedIpPacket<'a>> {
    match proto_num {
        6 => {
            if l4.len() < 20 {
                return None;
            }
            let src_port = u16::from_be_bytes([l4[0], l4[1]]);
            let dst_port = u16::from_be_bytes([l4[2], l4[3]]);
            let data_offset = ((l4[12] >> 4) as usize) * 4;
            if data_offset < 20 || data_offset > l4.len() {
                return None;
            }
            let tcp_flags = l4[13];
            Some(ParsedIpPacket {
                src_ip,
                dst_ip,
                proto: TransportProto::Tcp,
                src_port,
                dst_port,
                tcp_flags,
                payload: &l4[data_offset..],
            })
        }
        17 => {
            if l4.len() < 8 {
                return None;
            }
            let src_port = u16::from_be_bytes([l4[0], l4[1]]);
            let dst_port = u16::from_be_bytes([l4[2], l4[3]]);
            let udp_len = u16::from_be_bytes([l4[4], l4[5]]) as usize;
            let end = udp_len.clamp(8, l4.len());
            Some(ParsedIpPacket {
                src_ip,
                dst_ip,
                proto: TransportProto::Udp,
                src_port,
                dst_port,
                tcp_flags: 0,
                payload: &l4[8..end],
            })
        }
        other => Some(ParsedIpPacket {
            src_ip,
            dst_ip,
            proto: TransportProto::Other(other),
            src_port: 0,
            dst_port: 0,
            tcp_flags: 0,
            payload: l4,
        }),
    }
}

fn checksum_accumulate(mut sum: u32, data: &[u8]) -> u32 {
    let mut chunks = data.chunks_exact(2);
    for chunk in &mut chunks {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
    }
    if let Some(&rem) = chunks.remainder().first() {
        sum += (rem as u32) << 8;
    }
    sum
}

fn checksum_finalize(mut sum: u32) -> u16 {
    while (sum >> 16) != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !(sum as u16)
}

/// Builds a complete IPv4 or IPv6 + UDP packet with valid header and UDP checksums.
pub fn build_udp_ip_packet(src: SocketAddr, dst: SocketAddr, payload: &[u8]) -> Option<Vec<u8>> {
    match (src, dst) {
        (SocketAddr::V4(s4), SocketAddr::V4(d4)) => {
            let udp_len = 8usize.checked_add(payload.len())?;
            let total_len = 20usize.checked_add(udp_len)?;
            if total_len > u16::MAX as usize {
                return None;
            }

            let mut pkt = vec![0u8; total_len];
            // IPv4 header
            pkt[0] = 0x45;
            pkt[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
            pkt[6..8].copy_from_slice(&0x4000u16.to_be_bytes()); // DF
            pkt[8] = 64; // TTL
            pkt[9] = 17; // UDP
            pkt[12..16].copy_from_slice(&s4.ip().octets());
            pkt[16..20].copy_from_slice(&d4.ip().octets());

            let ip_sum = checksum_finalize(checksum_accumulate(0, &pkt[..20]));
            pkt[10..12].copy_from_slice(&ip_sum.to_be_bytes());

            // UDP header
            pkt[20..22].copy_from_slice(&s4.port().to_be_bytes());
            pkt[22..24].copy_from_slice(&d4.port().to_be_bytes());
            pkt[24..26].copy_from_slice(&(udp_len as u16).to_be_bytes());
            pkt[28..].copy_from_slice(payload);

            // UDP IPv4 pseudo-header + UDP segment
            let mut sum = 0u32;
            sum = checksum_accumulate(sum, &s4.ip().octets());
            sum = checksum_accumulate(sum, &d4.ip().octets());
            sum += 17u32;
            sum += udp_len as u32;
            sum = checksum_accumulate(sum, &pkt[20..]);
            let mut udp_sum = checksum_finalize(sum);
            if udp_sum == 0 {
                udp_sum = 0xFFFF;
            }
            pkt[26..28].copy_from_slice(&udp_sum.to_be_bytes());
            Some(pkt)
        }
        (SocketAddr::V6(s6), SocketAddr::V6(d6)) => {
            let udp_len = 8usize.checked_add(payload.len())?;
            let total_len = 40usize.checked_add(udp_len)?;
            if udp_len > u16::MAX as usize {
                return None;
            }

            let mut pkt = vec![0u8; total_len];
            // IPv6 header
            pkt[0] = 0x60;
            pkt[4..6].copy_from_slice(&(udp_len as u16).to_be_bytes());
            pkt[6] = 17; // UDP
            pkt[7] = 64; // Hop Limit
            pkt[8..24].copy_from_slice(&s6.ip().octets());
            pkt[24..40].copy_from_slice(&d6.ip().octets());

            // UDP header
            pkt[40..42].copy_from_slice(&s6.port().to_be_bytes());
            pkt[42..44].copy_from_slice(&d6.port().to_be_bytes());
            pkt[44..46].copy_from_slice(&(udp_len as u16).to_be_bytes());
            pkt[48..].copy_from_slice(payload);

            let mut sum = 0u32;
            sum = checksum_accumulate(sum, &s6.ip().octets());
            sum = checksum_accumulate(sum, &d6.ip().octets());
            sum += udp_len as u32;
            sum += 17u32;
            sum = checksum_accumulate(sum, &pkt[40..]);
            let mut udp_sum = checksum_finalize(sum);
            if udp_sum == 0 {
                udp_sum = 0xFFFF;
            }
            pkt[46..48].copy_from_slice(&udp_sum.to_be_bytes());
            Some(pkt)
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_and_parses_ipv4_and_ipv6_udp_packets() {
        let s4 = SocketAddr::from((Ipv4Addr::new(1, 1, 1, 1), 53));
        let d4 = SocketAddr::from((Ipv4Addr::new(10, 10, 0, 2), 54321));
        let pkt4 = build_udp_ip_packet(s4, d4, b"hello-dns").unwrap();
        let parsed4 = parse_ip_packet(&pkt4).unwrap();
        assert_eq!(parsed4.src_ip, IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)));
        assert_eq!(parsed4.dst_ip, IpAddr::V4(Ipv4Addr::new(10, 10, 0, 2)));
        assert_eq!(parsed4.proto, TransportProto::Udp);
        assert_eq!(parsed4.src_port, 53);
        assert_eq!(parsed4.dst_port, 54321);
        assert_eq!(parsed4.payload, b"hello-dns");

        let s6 = SocketAddr::from(("2606:4700:4700::1111".parse::<Ipv6Addr>().unwrap(), 53));
        let d6 = SocketAddr::from(("fd00:10::2".parse::<Ipv6Addr>().unwrap(), 40000));
        let pkt6 = build_udp_ip_packet(s6, d6, b"v6-dns").unwrap();
        let parsed6 = parse_ip_packet(&pkt6).unwrap();
        assert_eq!(parsed6.proto, TransportProto::Udp);
        assert_eq!(parsed6.src_port, 53);
        assert_eq!(parsed6.dst_port, 40000);
        assert_eq!(parsed6.payload, b"v6-dns");
    }
}
