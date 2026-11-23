use crate::sniff::sni::is_valid_hostname;

const HTTP_METHODS: [&[u8]; 9] = [
    b"GET ",
    b"POST ",
    b"PUT ",
    b"DELETE ",
    b"HEAD ",
    b"OPTIONS ",
    b"PATCH ",
    b"CONNECT ",
    b"TRACE ",
];

/// Extracts the `Host` header value from a plaintext HTTP/1.x request payload.
pub fn extract_http_host(data: &[u8]) -> Option<String> {
    if !HTTP_METHODS.iter().any(|prefix| data.starts_with(prefix)) {
        return None;
    }

    let text = std::str::from_utf8(data).ok()?;
    for line in text.lines().skip(1) {
        let trimmed = line.trim_end_matches('\r');
        if trimmed.is_empty() {
            break;
        }
        if let Some((name, value)) = trimmed.split_once(':') {
            if name.trim().eq_ignore_ascii_case("host") {
                let raw = value.trim();
                if raw.is_empty() {
                    return None;
                }
                let host_only = if raw.starts_with('[') {
                    // IPv6 host like [::1]:8080
                    raw.split(']').next().unwrap_or(raw).trim_start_matches('[')
                } else {
                    raw.split(':').next().unwrap_or(raw)
                };
                let normalized = host_only.trim_end_matches('.').to_ascii_lowercase();
                if is_valid_hostname(&normalized) {
                    return Some(normalized);
                }
            }
        }
    }

    None
}
