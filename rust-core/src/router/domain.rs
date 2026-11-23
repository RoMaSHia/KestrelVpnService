use std::collections::HashMap;

use crate::config::RuleAction;

#[derive(Debug, Clone)]
pub struct DomainRuleEntry {
    pub priority: usize,
    pub action: RuleAction,
    pub label: String,
}

#[derive(Default)]
pub struct DomainMatcher {
    full: HashMap<String, DomainRuleEntry>,
    suffix: HashMap<String, DomainRuleEntry>,
    keywords: Vec<(String, DomainRuleEntry)>,
}

impl DomainMatcher {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn insert_full(&mut self, domain: &str, entry: DomainRuleEntry) {
        let key = normalize_domain(domain);
        if key.is_empty() {
            return;
        }
        insert_if_higher_priority(&mut self.full, key, entry);
    }

    pub fn insert_suffix(&mut self, domain: &str, entry: DomainRuleEntry) {
        let key = normalize_domain(domain).trim_start_matches('.').to_string();
        if key.is_empty() {
            return;
        }
        insert_if_higher_priority(&mut self.suffix, key, entry);
    }

    pub fn insert_keyword(&mut self, keyword: &str, entry: DomainRuleEntry) {
        let key = keyword.trim().to_ascii_lowercase();
        if key.is_empty() {
            return;
        }
        self.keywords.push((key, entry));
    }

    pub fn lookup(&self, domain: &str) -> Option<&DomainRuleEntry> {
        let normalized = normalize_domain(domain);
        if normalized.is_empty() {
            return None;
        }

        let mut best: Option<&DomainRuleEntry> = None;

        if let Some(hit) = self.full.get(&normalized) {
            best = pick_better(best, hit);
        }

        // Walk label boundaries without allocating per suffix check
        let mut cursor = normalized.as_str();
        loop {
            if let Some(hit) = self.suffix.get(cursor) {
                best = pick_better(best, hit);
            }
            match cursor.find('.') {
                Some(idx) => cursor = &cursor[idx + 1..],
                None => break,
            }
        }

        for (kw, hit) in &self.keywords {
            if normalized.contains(kw) {
                best = pick_better(best, hit);
            }
        }

        best
    }
}

fn normalize_domain(raw: &str) -> String {
    raw.trim().trim_end_matches('.').to_ascii_lowercase()
}

fn insert_if_higher_priority(
    map: &mut HashMap<String, DomainRuleEntry>,
    key: String,
    entry: DomainRuleEntry,
) {
    let replace = map
        .get(&key)
        .map(|existing| entry.priority < existing.priority)
        .unwrap_or(true);
    if replace {
        map.insert(key, entry);
    }
}

fn pick_better<'a>(
    current: Option<&'a DomainRuleEntry>,
    candidate: &'a DomainRuleEntry,
) -> Option<&'a DomainRuleEntry> {
    match current {
        Some(cur) if cur.priority <= candidate.priority => Some(cur),
        _ => Some(candidate),
    }
}
