use serde::{Deserialize, Serialize};

fn default_mtu() -> u16 {
    1500
}

fn default_true() -> bool {
    true
}

fn default_dns_upstream() -> String {
    "1.1.1.1:53".to_string()
}

fn default_dns_fallback() -> String {
    "8.8.8.8:53".to_string()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ProxyProtocol {
    Socks5,
    Socks4,
    Socks4a,
    Http,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProxyConfig {
    pub protocol: ProxyProtocol,
    pub host: String,
    pub port: u16,
    #[serde(default)]
    pub username: Option<String>,
    #[serde(default)]
    pub password: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DnsConfig {
    #[serde(default = "default_dns_upstream")]
    pub upstream: String,
    #[serde(default = "default_dns_fallback")]
    pub fallback: String,
    #[serde(default = "default_true")]
    pub intercept_dns: bool,
    #[serde(default = "default_true")]
    pub route_dns: bool,
}

impl Default for DnsConfig {
    fn default() -> Self {
        Self {
            upstream: default_dns_upstream(),
            fallback: default_dns_fallback(),
            intercept_dns: true,
            route_dns: true,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum RuleAction {
    Proxy,
    Direct,
    Block,
}

impl Default for RuleAction {
    fn default() -> Self {
        Self::Proxy
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RuleMatchType {
    DomainFull,
    DomainSuffix,
    DomainKeyword,
    IpCidr,
    Port,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RoutingRuleConfig {
    pub id: String,
    #[serde(default)]
    pub name: String,
    pub match_type: RuleMatchType,
    pub value: String,
    pub action: RuleAction,
    #[serde(default = "default_true")]
    pub enabled: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RoutingConfig {
    #[serde(default)]
    pub default_action: RuleAction,
    #[serde(default = "default_true")]
    pub bypass_lan: bool,
    #[serde(default)]
    pub rules: Vec<RoutingRuleConfig>,
}

impl Default for RoutingConfig {
    fn default() -> Self {
        Self {
            default_action: RuleAction::Proxy,
            bypass_lan: true,
            rules: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CoreConfig {
    #[serde(default)]
    pub proxy: Option<ProxyConfig>,
    #[serde(default)]
    pub dns: DnsConfig,
    #[serde(default)]
    pub routing: RoutingConfig,
    #[serde(default = "default_mtu")]
    pub mtu: u16,
}

impl Default for CoreConfig {
    fn default() -> Self {
        Self {
            proxy: None,
            dns: DnsConfig::default(),
            routing: RoutingConfig::default(),
            mtu: default_mtu(),
        }
    }
}
