/// Extracts the server hostname from a TLS ClientHello SNI extension (`0x0000`).
pub fn extract_tls_sni(data: &[u8]) -> Option<String> {
    if data.len() < 5 {
        return None;
    }
    // TLS record type 0x16 (Handshake), version 0x03xx
    if data[0] != 0x16 || data[1] != 0x03 {
        return None;
    }

    let record_len = u16::from_be_bytes([data[3], data[4]]) as usize;
    let record_end = (5 + record_len).min(data.len());
    let mut pos = 5;

    if pos + 4 > record_end {
        return None;
    }
    // Handshake type 0x01 (ClientHello)
    if data[pos] != 0x01 {
        return None;
    }
    pos += 4; // skip type (1) + handshake length (3)

    // Skip client_version (2) + random (32)
    if pos + 34 > record_end {
        return None;
    }
    pos += 34;

    // Session ID
    if pos + 1 > record_end {
        return None;
    }
    let session_id_len = data[pos] as usize;
    pos += 1 + session_id_len;
    if pos + 2 > record_end {
        return None;
    }

    // Cipher suites
    let cipher_suites_len = u16::from_be_bytes([data[pos], data[pos + 1]]) as usize;
    pos += 2 + cipher_suites_len;
    if pos + 1 > record_end {
        return None;
    }

    // Compression methods
    let comp_len = data[pos] as usize;
    pos += 1 + comp_len;
    if pos + 2 > record_end {
        return None;
    }

    // Extensions length
    let ext_total_len = u16::from_be_bytes([data[pos], data[pos + 1]]) as usize;
    pos += 2;
    let ext_end = (pos + ext_total_len).min(record_end);

    while pos + 4 <= ext_end {
        let ext_type = u16::from_be_bytes([data[pos], data[pos + 1]]);
        let ext_len = u16::from_be_bytes([data[pos + 2], data[pos + 3]]) as usize;
        pos += 4;

        let this_ext_end = (pos + ext_len).min(ext_end);
        if ext_type == 0x0000 {
            if pos + 2 > this_ext_end {
                return None;
            }
            let list_len = u16::from_be_bytes([data[pos], data[pos + 1]]) as usize;
            pos += 2;
            let list_end = (pos + list_len).min(this_ext_end);

            while pos + 3 <= list_end {
                let name_type = data[pos];
                let name_len = u16::from_be_bytes([data[pos + 1], data[pos + 2]]) as usize;
                pos += 3;
                if pos + name_len > list_end {
                    return None;
                }
                if name_type == 0x00 && name_len > 0 && name_len <= 253 {
                    if let Ok(raw) = std::str::from_utf8(&data[pos..pos + name_len]) {
                        let host = raw.trim().trim_end_matches('.').to_ascii_lowercase();
                        if is_valid_hostname(&host) {
                            return Some(host);
                        }
                    }
                }
                pos += name_len;
            }
            return None;
        }
        pos = this_ext_end;
    }

    None
}

pub(crate) fn is_valid_hostname(host: &str) -> bool {
    !host.is_empty()
        && host.len() <= 253
        && host.bytes().all(|b| {
            b.is_ascii_alphanumeric() || b == b'.' || b == b'-' || b == b'_'
        })
}
