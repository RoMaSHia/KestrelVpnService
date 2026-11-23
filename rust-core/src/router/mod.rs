pub mod domain;
pub mod ip_trie;

use std::collections::HashMap;
use std::net::IpAddr;

use ipnet::IpNet;

use crate::config::{RoutingConfig, RuleAction, RuleMatchType};
use crate::router::domain::{DomainMatcher, DomainRuleEntry};
use crate::router::ip_trie::IpRadixTrie;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RouteDecision {
    pub action: RuleAction,
    pub rule_matched: String,
}

struct PortRuleEntry {
    priority: usize,
    action: RuleAction,
    label: String,
}

const LAN_CIDRS: &[&str] = &[
    "10.0.0.0/8",
    "172.16.0.0/12",
    "192.168.0.0/16",
    "127.0.0.0/8",
    "169.254.0.0/16",
    "100.64.0.0/10",
    "::1/128",
    "fc00::/7",
    "fe80::/10",
];

/// Fast dual-index routing engine (domain hash/suffix matcher + IPv4/IPv6 radix trie + port map).
pub struct Router {
    domains: DomainMatcher,
    ip_trie: IpRadixTrie,
    ports: HashMap<u16, PortRuleEntry>,
    lan_trie: IpRadixTrie,
    bypass_lan: bool,
    default_action: RuleAction,
}

impl Router {
    pub fn from_config(cfg: &RoutingConfig) -> Self {
        let mut domains = DomainMatcher::new();
        let mut ip_trie = IpRadixTrie::new();
        let mut ports: HashMap<u16, PortRuleEntry> = HashMap::new();

        for (priority, rule) in cfg.rules.iter().enumerate() {
            if !rule.enabled {
                continue;
            }
            let label = if rule.name.trim().is_empty() {
                rule.value.clone()
            } else {
                rule.name.clone()
            };

            match rule.match_type {
                RuleMatchType::DomainFull => {
                    domains.insert_full(
                        &rule.value,
                        DomainRuleEntry {
                            priority,
                            action: rule.action,
                            label,
                        },
                    );
                }
                RuleMatchType::DomainSuffix => {
                    domains.insert_suffix(
                        &rule.value,
                        DomainRuleEntry {
                            priority,
                            action: rule.action,
                            label,
                        },
                    );
                }
                RuleMatchType::DomainKeyword => {
                    domains.insert_keyword(
                        &rule.value,
                        DomainRuleEntry {
                            priority,
                            action: rule.action,
                            label,
                        },
                    );
                }
                RuleMatchType::IpCidr => {
                    if let Some(net) = parse_ip_or_cidr(&rule.value) {
                        ip_trie.insert(net, priority, rule.action, label);
                    }
                }
                RuleMatchType::Port => {
                    if let Ok(port) = rule.value.trim().parse::<u16>() {
                        ports.entry(port).or_insert(PortRuleEntry {
                            priority,
                            action: rule.action,
                            label,
                        });
                    }
                }
            }
        }

        let mut lan_trie = IpRadixTrie::new();
        for (idx, cidr) in LAN_CIDRS.iter().enumerate() {
            if let Ok(net) = cidr.parse::<IpNet>() {
                lan_trie.insert(net, idx, RuleAction::Direct, "LAN Bypass".to_string());
            }
        }

        Self {
            domains,
            ip_trie,
            ports,
            lan_trie,
            bypass_lan: cfg.bypass_lan,
            default_action: cfg.default_action,
        }
    }

    pub fn decide(&self, domain: Option<&str>, dst_ip: IpAddr, dst_port: u16) -> RouteDecision {
        let mut best: Option<(usize, RuleAction, &str)> = None;

        if let Some(dom) = domain {
            if let Some(hit) = self.domains.lookup(dom) {
                best = Some((hit.priority, hit.action, hit.label.as_str()));
            }
        }

        if let Some(hit) = self.ip_trie.lookup(dst_ip) {
            if best.map(|b| hit.priority < b.0).unwrap_or(true) {
                best = Some((hit.priority, hit.action, hit.label.as_str()));
            }
        }

        if let Some(hit) = self.ports.get(&dst_port) {
            if best.map(|b| hit.priority < b.0).unwrap_or(true) {
                best = Some((hit.priority, hit.action, hit.label.as_str()));
            }
        }

        if let Some((_, action, label)) = best {
            return RouteDecision {
                action,
                rule_matched: label.to_string(),
            };
        }

        if self.bypass_lan && self.lan_trie.lookup(dst_ip).is_some() {
            return RouteDecision {
                action: RuleAction::Direct,
                rule_matched: "LAN Bypass".to_string(),
            };
        }

        RouteDecision {
            action: self.default_action,
            rule_matched: "По умолчанию".to_string(),
        }
    }
}

fn parse_ip_or_cidr(raw: &str) -> Option<IpNet> {
    let trimmed = raw.trim();
    if let Ok(net) = trimmed.parse::<IpNet>() {
        return Some(net);
    }
    if let Ok(ip) = trimmed.parse::<IpAddr>() {
        return Some(IpNet::from(ip));
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::RoutingRuleConfig;
    use std::net::{Ipv4Addr, Ipv6Addr};

    #[test]
    fn respects_domain_suffix_boundaries_and_priority() {
        let cfg = RoutingConfig {
            default_action: RuleAction::Proxy,
            bypass_lan: true,
            rules: vec![
                RoutingRuleConfig {
                    id: "1".into(),
                    name: "Block trackers".into(),
                    match_type: RuleMatchType::DomainKeyword,
                    value: "tracker".into(),
                    action: RuleAction::Block,
                    enabled: true,
                },
                RoutingRuleConfig {
                    id: "2".into(),
                    name: "Exact API".into(),
                    match_type: RuleMatchType::DomainFull,
                    value: "api.example.com".into(),
                    action: RuleAction::Direct,
                    enabled: true,
                },
                RoutingRuleConfig {
                    id: "3".into(),
                    name: "Example suffix".into(),
                    match_type: RuleMatchType::DomainSuffix,
                    value: "example.com".into(),
                    action: RuleAction::Direct,
                    enabled: true,
                },
            ],
        };

        let router = Router::from_config(&cfg);
        let public_ip = IpAddr::V4(Ipv4Addr::new(93, 184, 216, 34));

        // Priority 0 keyword beats priority 2 suffix
        let d1 = router.decide(Some("tracker.example.com"), public_ip, 443);
        assert_eq!(d1.action, RuleAction::Block);

        // Exact match
        let d2 = router.decide(Some("api.example.com"), public_ip, 443);
        assert_eq!(d2.action, RuleAction::Direct);

        // Suffix match on subdomain
        let d3 = router.decide(Some("cdn.sub.example.com"), public_ip, 443);
        assert_eq!(d3.action, RuleAction::Direct);

        // Must NOT match "notexample.com"
        let d4 = router.decide(Some("notexample.com"), public_ip, 443);
        assert_eq!(d4.action, RuleAction::Proxy);
    }

    #[test]
    fn handles_cidr_ipv4_ipv6_lan_bypass_and_ports() {
        let cfg = RoutingConfig {
            default_action: RuleAction::Proxy,
            bypass_lan: true,
            rules: vec![
                RoutingRuleConfig {
                    id: "cidr4".into(),
                    name: "CF Direct".into(),
                    match_type: RuleMatchType::IpCidr,
                    value: "1.1.1.0/24".into(),
                    action: RuleAction::Direct,
                    enabled: true,
                },
                RoutingRuleConfig {
                    id: "cidr6".into(),
                    name: "Block v6 net".into(),
                    match_type: RuleMatchType::IpCidr,
                    value: "2001:db8::/32".into(),
                    action: RuleAction::Block,
                    enabled: true,
                },
                RoutingRuleConfig {
                    id: "port".into(),
                    name: "Block QUIC".into(),
                    match_type: RuleMatchType::Port,
                    value: "853".into(),
                    action: RuleAction::Block,
                    enabled: true,
                },
            ],
        };

        let router = Router::from_config(&cfg);
        assert_eq!(
            router
                .decide(None, IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), 443)
                .action,
            RuleAction::Direct
        );
        assert_eq!(
            router
                .decide(
                    None,
                    IpAddr::V6("2001:db8::1".parse::<Ipv6Addr>().unwrap()),
                    443
                )
                .action,
            RuleAction::Block
        );
        assert_eq!(
            router
                .decide(None, IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)), 853)
                .action,
            RuleAction::Block
        );
        assert_eq!(
            router
                .decide(None, IpAddr::V4(Ipv4Addr::new(192, 168, 1, 1)), 80)
                .action,
            RuleAction::Direct
        );
        assert_eq!(
            router
                .decide(None, IpAddr::V4(Ipv4Addr::new(8, 8, 4, 4)), 443)
                .action,
            RuleAction::Proxy
        );
    }
}
