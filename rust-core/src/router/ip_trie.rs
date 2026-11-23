use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

use ipnet::IpNet;

use crate::config::RuleAction;

#[derive(Debug, Clone)]
pub struct TrieRule {
    pub priority: usize,
    pub action: RuleAction,
    pub label: String,
}

#[derive(Default)]
struct TrieNode {
    children: [Option<usize>; 2],
    rule: Option<TrieRule>,
}

/// Binary radix-trie supporting O(prefix_len) IPv4 and IPv6 CIDR routing lookups
/// while respecting rule priority order.
pub struct IpRadixTrie {
    v4_nodes: Vec<TrieNode>,
    v6_nodes: Vec<TrieNode>,
}

impl IpRadixTrie {
    pub fn new() -> Self {
        Self {
            v4_nodes: vec![TrieNode::default()],
            v6_nodes: vec![TrieNode::default()],
        }
    }

    pub fn insert(&mut self, net: IpNet, priority: usize, action: RuleAction, label: String) {
        let entry = TrieRule {
            priority,
            action,
            label,
        };
        match net {
            IpNet::V4(v4) => {
                let addr = u32::from(v4.network());
                let prefix_len = v4.prefix_len();
                let mut idx = 0usize;
                for depth in 0..prefix_len {
                    let bit = ((addr >> (31 - depth)) & 1) as usize;
                    if self.v4_nodes[idx].children[bit].is_none() {
                        let next_idx = self.v4_nodes.len();
                        self.v4_nodes.push(TrieNode::default());
                        self.v4_nodes[idx].children[bit] = Some(next_idx);
                    }
                    idx = self.v4_nodes[idx].children[bit].unwrap();
                }
                let replace = self.v4_nodes[idx]
                    .rule
                    .as_ref()
                    .map(|existing| entry.priority < existing.priority)
                    .unwrap_or(true);
                if replace {
                    self.v4_nodes[idx].rule = Some(entry);
                }
            }
            IpNet::V6(v6) => {
                let addr = u128::from(v6.network());
                let prefix_len = v6.prefix_len();
                let mut idx = 0usize;
                for depth in 0..prefix_len {
                    let bit = ((addr >> (127 - depth)) & 1) as usize;
                    if self.v6_nodes[idx].children[bit].is_none() {
                        let next_idx = self.v6_nodes.len();
                        self.v6_nodes.push(TrieNode::default());
                        self.v6_nodes[idx].children[bit] = Some(next_idx);
                    }
                    idx = self.v6_nodes[idx].children[bit].unwrap();
                }
                let replace = self.v6_nodes[idx]
                    .rule
                    .as_ref()
                    .map(|existing| entry.priority < existing.priority)
                    .unwrap_or(true);
                if replace {
                    self.v6_nodes[idx].rule = Some(entry);
                }
            }
        }
    }

    pub fn lookup(&self, ip: IpAddr) -> Option<&TrieRule> {
        match ip {
            IpAddr::V4(v4) => self.lookup_v4(v4),
            IpAddr::V6(v6) => self.lookup_v6(v6),
        }
    }

    fn lookup_v4(&self, ip: Ipv4Addr) -> Option<&TrieRule> {
        let addr = u32::from(ip);
        let mut idx = 0usize;
        let mut best: Option<&TrieRule> = self.v4_nodes[0].rule.as_ref();

        for depth in 0..32 {
            let bit = ((addr >> (31 - depth)) & 1) as usize;
            let Some(next) = self.v4_nodes[idx].children[bit] else {
                break;
            };
            idx = next;
            if let Some(candidate) = self.v4_nodes[idx].rule.as_ref() {
                if best.map(|b| candidate.priority < b.priority).unwrap_or(true) {
                    best = Some(candidate);
                }
            }
        }
        best
    }

    fn lookup_v6(&self, ip: Ipv6Addr) -> Option<&TrieRule> {
        let addr = u128::from(ip);
        let mut idx = 0usize;
        let mut best: Option<&TrieRule> = self.v6_nodes[0].rule.as_ref();

        for depth in 0..128 {
            let bit = ((addr >> (127 - depth)) & 1) as usize;
            let Some(next) = self.v6_nodes[idx].children[bit] else {
                break;
            };
            idx = next;
            if let Some(candidate) = self.v6_nodes[idx].rule.as_ref() {
                if best.map(|b| candidate.priority < b.priority).unwrap_or(true) {
                    best = Some(candidate);
                }
            }
        }
        best
    }
}

impl Default for IpRadixTrie {
    fn default() -> Self {
        Self::new()
    }
}
