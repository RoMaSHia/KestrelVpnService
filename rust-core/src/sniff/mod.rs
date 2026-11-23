pub mod http_host;
pub mod sni;

/// Attempts to sniff the target domain name from the initial outbound TCP payload.
pub fn sniff_domain(data: &[u8]) -> Option<String> {
    sni::extract_tls_sni(data).or_else(|| http_host::extract_http_host(data))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn build_test_client_hello(sni: &str) -> Vec<u8> {
        let name_bytes = sni.as_bytes();
        let entry_len = 1 + 2 + name_bytes.len();
        let list_len = entry_len;
        let sni_ext_data_len = 2 + list_len;
        let extensions_len = 4 + sni_ext_data_len;

        let mut ch = Vec::new();
        // client_version (TLS 1.2) + 32-byte random
        ch.extend_from_slice(&[0x03, 0x03]);
        ch.extend_from_slice(&[0xAA; 32]);
        // session_id_len = 0
        ch.push(0);
        // cipher_suites_len = 2, TLS_AES_128_GCM_SHA256
        ch.extend_from_slice(&[0x00, 0x02, 0x13, 0x01]);
        // compression_methods_len = 1, null
        ch.extend_from_slice(&[0x01, 0x00]);
        // extensions_len
        ch.extend_from_slice(&(extensions_len as u16).to_be_bytes());
        // SNI extension (0x0000)
        ch.extend_from_slice(&0x0000u16.to_be_bytes());
        ch.extend_from_slice(&(sni_ext_data_len as u16).to_be_bytes());
        ch.extend_from_slice(&(list_len as u16).to_be_bytes());
        ch.push(0x00); // host_name
        ch.extend_from_slice(&(name_bytes.len() as u16).to_be_bytes());
        ch.extend_from_slice(name_bytes);

        let hs_len = ch.len();
        let mut hs = Vec::new();
        hs.push(0x01); // ClientHello
        hs.push(((hs_len >> 16) & 0xFF) as u8);
        hs.push(((hs_len >> 8) & 0xFF) as u8);
        hs.push((hs_len & 0xFF) as u8);
        hs.extend_from_slice(&ch);

        let mut record = Vec::new();
        record.extend_from_slice(&[0x16, 0x03, 0x01]);
        record.extend_from_slice(&(hs.len() as u16).to_be_bytes());
        record.extend_from_slice(&hs);
        record
    }

    #[test]
    fn extracts_tls_sni_from_client_hello() {
        let pkt = build_test_client_hello("API.Example.COM");
        assert_eq!(sniff_domain(&pkt).as_deref(), Some("api.example.com"));
    }

    #[test]
    fn extracts_http_host_header() {
        let req = b"GET /index.html HTTP/1.1\r\nHost: News.Example.Org:8080\r\nUser-Agent: Kestrel\r\n\r\n";
        assert_eq!(sniff_domain(req).as_deref(), Some("news.example.org"));
    }

    #[test]
    fn ignores_non_tls_non_http_bytes() {
        assert_eq!(sniff_domain(b"\x00\x01\x02\x03\x04\x05"), None);
    }
}
