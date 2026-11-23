pub mod config;
pub mod dns;
pub mod jni_bridge;
pub mod packet;
pub mod proxy;
pub mod router;
pub mod sniff;
pub mod stats;
pub mod tun_engine;

use serde::{Deserialize, Serialize};

/// Metadata describing the compiled native proxy core.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CoreInfo {
    pub name: String,
    pub version: String,
    pub arch: String,
    pub profile: String,
    pub status: String,
}

/// Returns static build and runtime capabilities of the Rust core.
pub fn build_core_info() -> CoreInfo {
    let profile = if cfg!(debug_assertions) {
        "debug"
    } else {
        "release"
    };

    CoreInfo {
        name: "Kestrel Core".to_string(),
        version: env!("CARGO_PKG_VERSION").to_string(),
        arch: std::env::consts::ARCH.to_string(),
        profile: profile.to_string(),
        status: "ready".to_string(),
    }
}

/// Serializes [`CoreInfo`] to a JSON string for transfer across JNI.
pub fn build_core_info_json() -> String {
    let info = build_core_info();
    serde_json::to_string(&info)
        .unwrap_or_else(|_| r#"{"name":"Kestrel Core","status":"error"}"#.to_string())
}

/// Formats a test ping response from the native Rust core.
pub fn format_ping_reply(message: &str) -> String {
    let trimmed = message.trim();
    let profile = if cfg!(debug_assertions) {
        "debug"
    } else {
        "release"
    };

    if trimmed.is_empty() {
        format!(
            "Pong from Rust Core v{} ({}, {})",
            env!("CARGO_PKG_VERSION"),
            std::env::consts::ARCH,
            profile
        )
    } else {
        format!(
            "Pong from Rust Core v{} ({}, {}): {}",
            env!("CARGO_PKG_VERSION"),
            std::env::consts::ARCH,
            profile,
            trimmed
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn core_info_serializes_expected_fields() {
        let json = build_core_info_json();
        let parsed: CoreInfo = serde_json::from_str(&json).expect("valid CoreInfo JSON");
        assert_eq!(parsed.name, "Kestrel Core");
        assert_eq!(parsed.version, env!("CARGO_PKG_VERSION"));
        assert_eq!(parsed.arch, std::env::consts::ARCH);
        assert_eq!(parsed.status, "ready");
        assert!(parsed.profile == "debug" || parsed.profile == "release");
    }

    #[test]
    fn ping_reply_formats_message_and_handles_blank() {
        let with_msg = format_ping_reply("  Привет из Kotlin!  ");
        assert!(with_msg.contains("Pong from Rust Core"));
        assert!(with_msg.contains("Привет из Kotlin!"));

        let empty_msg = format_ping_reply("   ");
        assert!(empty_msg.contains("Pong from Rust Core"));
        assert!(!empty_msg.ends_with(": "));
    }
}
