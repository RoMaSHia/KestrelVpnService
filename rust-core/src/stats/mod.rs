use std::collections::VecDeque;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::config::RuleAction;

const MAX_LOG_ENTRIES: usize = 500;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ConnectionLogEntry {
    pub id: u64,
    pub timestamp_ms: u64,
    pub protocol: String,
    pub host: String,
    pub dst_ip: String,
    pub dst_port: u16,
    pub action: RuleAction,
    pub rule_matched: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct TrafficStats {
    pub tx_bytes: u64,
    pub rx_bytes: u64,
    pub tx_speed_bps: u64,
    pub rx_speed_bps: u64,
    pub active_connections: u64,
    pub uptime_ms: u64,
}

struct SpeedSample {
    at: Instant,
    tx_bytes: u64,
    rx_bytes: u64,
    last_tx_bps: u64,
    last_rx_bps: u64,
}

pub struct Telemetry {
    tx_bytes: AtomicU64,
    rx_bytes: AtomicU64,
    active_connections: AtomicU64,
    next_log_id: AtomicU64,
    started_at: Instant,
    speed_sample: Mutex<SpeedSample>,
    logs: Mutex<VecDeque<ConnectionLogEntry>>,
}

impl Telemetry {
    pub fn new() -> Self {
        let now = Instant::now();
        Self {
            tx_bytes: AtomicU64::new(0),
            rx_bytes: AtomicU64::new(0),
            active_connections: AtomicU64::new(0),
            next_log_id: AtomicU64::new(1),
            started_at: now,
            speed_sample: Mutex::new(SpeedSample {
                at: now,
                tx_bytes: 0,
                rx_bytes: 0,
                last_tx_bps: 0,
                last_rx_bps: 0,
            }),
            logs: Mutex::new(VecDeque::with_capacity(MAX_LOG_ENTRIES)),
        }
    }

    pub fn record_tx(&self, bytes: u64) {
        if bytes > 0 {
            self.tx_bytes.fetch_add(bytes, Ordering::Relaxed);
        }
    }

    pub fn record_rx(&self, bytes: u64) {
        if bytes > 0 {
            self.rx_bytes.fetch_add(bytes, Ordering::Relaxed);
        }
    }

    pub fn inc_connections(&self) {
        self.active_connections.fetch_add(1, Ordering::Relaxed);
    }

    pub fn dec_connections(&self) {
        let _ = self
            .active_connections
            .try_update(Ordering::Relaxed, Ordering::Relaxed, |cur| {
                Some(cur.saturating_sub(1))
            });
    }

    pub fn push_log(
        &self,
        protocol: &str,
        host: String,
        dst_ip: String,
        dst_port: u16,
        action: RuleAction,
        rule_matched: String,
    ) {
        let id = self.next_log_id.fetch_add(1, Ordering::Relaxed);
        let timestamp_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);

        let entry = ConnectionLogEntry {
            id,
            timestamp_ms,
            protocol: protocol.to_string(),
            host,
            dst_ip,
            dst_port,
            action,
            rule_matched,
        };

        if let Ok(mut guard) = self.logs.lock() {
            if guard.len() >= MAX_LOG_ENTRIES {
                guard.pop_front();
            }
            guard.push_back(entry);
        }
    }

    pub fn snapshot(&self) -> TrafficStats {
        let tx = self.tx_bytes.load(Ordering::Relaxed);
        let rx = self.rx_bytes.load(Ordering::Relaxed);
        let active = self.active_connections.load(Ordering::Relaxed);
        let uptime_ms = self.started_at.elapsed().as_millis() as u64;

        let (tx_speed_bps, rx_speed_bps) = if let Ok(mut sample) = self.speed_sample.lock() {
            let elapsed_ms = sample.at.elapsed().as_millis() as u64;
            if elapsed_ms >= 200 {
                let d_tx = tx.saturating_sub(sample.tx_bytes);
                let d_rx = rx.saturating_sub(sample.rx_bytes);
                let tx_bps = (d_tx.saturating_mul(1000)) / elapsed_ms;
                let rx_bps = (d_rx.saturating_mul(1000)) / elapsed_ms;
                sample.at = Instant::now();
                sample.tx_bytes = tx;
                sample.rx_bytes = rx;
                sample.last_tx_bps = tx_bps;
                sample.last_rx_bps = rx_bps;
                (tx_bps, rx_bps)
            } else {
                (sample.last_tx_bps, sample.last_rx_bps)
            }
        } else {
            (0, 0)
        };

        TrafficStats {
            tx_bytes: tx,
            rx_bytes: rx,
            tx_speed_bps,
            rx_speed_bps,
            active_connections: active,
            uptime_ms,
        }
    }

    pub fn poll_logs(&self, since_id: u64, limit: usize) -> Vec<ConnectionLogEntry> {
        let cap = limit.clamp(1, MAX_LOG_ENTRIES);
        match self.logs.lock() {
            Ok(guard) => guard
                .iter()
                .filter(|entry| entry.id > since_id)
                .take(cap)
                .cloned()
                .collect(),
            Err(_) => Vec::new(),
        }
    }
}

impl Default for Telemetry {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ring_buffer_bounds_capacity_and_filters_since_id() {
        let telemetry = Telemetry::new();
        for idx in 0..(MAX_LOG_ENTRIES + 25) {
            telemetry.push_log(
                "TCP",
                format!("host-{idx}.example.com"),
                "1.1.1.1".to_string(),
                443,
                RuleAction::Proxy,
                "Default".to_string(),
            );
        }

        let all = telemetry.poll_logs(0, MAX_LOG_ENTRIES);
        assert_eq!(all.len(), MAX_LOG_ENTRIES);
        assert_eq!(all.first().unwrap().id, 26);
        assert_eq!(all.last().unwrap().id, (MAX_LOG_ENTRIES + 25) as u64);

        let delta = telemetry.poll_logs(520, 50);
        assert_eq!(delta.len(), 5);
        assert_eq!(delta[0].id, 521);
    }

    #[test]
    fn counters_track_bytes_and_connections() {
        let telemetry = Telemetry::new();
        telemetry.record_tx(1024);
        telemetry.record_rx(4096);
        telemetry.inc_connections();
        telemetry.inc_connections();
        telemetry.dec_connections();

        let snap = telemetry.snapshot();
        assert_eq!(snap.tx_bytes, 1024);
        assert_eq!(snap.rx_bytes, 4096);
        assert_eq!(snap.active_connections, 1);
    }
}
