//! Per-device request quota.
//!
//! The service pays for every upstream token, so one misbehaving handset — or
//! one leaked redeem code — must not be able to drain the budget. This is a
//! deliberately simple counter rather than a token bucket: the thing being
//! protected is a daily spend, not a burst rate.
//!
//! Counters live in memory only. A restart resets everyone's usage, which is the
//! right trade: persisting a counter on every chat request would mean a disk
//! write per request, and the failure mode of the simple version (a handful of
//! extra requests after a deploy) is much cheaper than the failure mode of the
//! complicated one.

use std::collections::HashMap;
use std::sync::Arc;

use chrono::{DateTime, NaiveDate, Utc};
use tokio::sync::Mutex;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Usage {
    pub used: u32,
    pub limit: u32,
}

impl Usage {
    pub fn remaining(&self) -> u32 {
        self.limit.saturating_sub(self.used)
    }
}

#[derive(Clone)]
pub struct Quota {
    limit: u32,
    counters: Arc<Mutex<HashMap<String, DayCount>>>,
}

#[derive(Clone, Copy)]
struct DayCount {
    day: NaiveDate,
    count: u32,
}

impl Quota {
    pub fn new(limit: u32) -> Self {
        Self {
            limit,
            counters: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    /// Records one request against `device_id`.
    ///
    /// Returns `Ok(usage)` when the request is allowed, `Err(usage)` when the
    /// device is over its daily limit. The counter is only incremented when the
    /// request is allowed, so a blocked device does not dig itself deeper.
    pub async fn try_consume(&self, device_id: &str, now: DateTime<Utc>) -> Result<Usage, Usage> {
        // A limit of zero means "no limit", so an operator can turn this off
        // without recompiling.
        if self.limit == 0 {
            return Ok(Usage { used: 0, limit: 0 });
        }

        let today = now.date_naive();
        let mut counters = self.counters.lock().await;

        let entry = counters.entry(device_id.to_string()).or_insert(DayCount {
            day: today,
            count: 0,
        });

        if entry.day != today {
            entry.day = today;
            entry.count = 0;
        }

        if entry.count >= self.limit {
            return Err(Usage {
                used: entry.count,
                limit: self.limit,
            });
        }

        entry.count += 1;
        Ok(Usage {
            used: entry.count,
            limit: self.limit,
        })
    }

    /// Drops counters for days that have passed. Cheap, and keeps the map from
    /// growing without bound as devices come and go.
    pub async fn prune(&self, now: DateTime<Utc>) -> usize {
        let today = now.date_naive();
        let mut counters = self.counters.lock().await;
        let before = counters.len();
        counters.retain(|_, c| c.day == today);
        before - counters.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::Duration;

    fn at(day: &str) -> DateTime<Utc> {
        DateTime::parse_from_rfc3339(day).unwrap().with_timezone(&Utc)
    }

    #[tokio::test]
    async fn requests_are_allowed_up_to_the_limit() {
        let quota = Quota::new(3);
        let now = at("2026-09-20T10:00:00Z");

        for expected in 1..=3 {
            let usage = quota.try_consume("dev-1", now).await.unwrap();
            assert_eq!(usage.used, expected);
        }

        let over = quota.try_consume("dev-1", now).await.unwrap_err();
        assert_eq!(over.used, 3);
        assert_eq!(over.remaining(), 0);
    }

    #[tokio::test]
    async fn a_blocked_device_does_not_keep_incrementing() {
        let quota = Quota::new(1);
        let now = at("2026-09-20T10:00:00Z");

        quota.try_consume("dev-1", now).await.unwrap();
        quota.try_consume("dev-1", now).await.unwrap_err();
        let second = quota.try_consume("dev-1", now).await.unwrap_err();

        assert_eq!(second.used, 1, "a rejected request must not count");
    }

    #[tokio::test]
    async fn devices_are_counted_separately() {
        let quota = Quota::new(1);
        let now = at("2026-09-20T10:00:00Z");

        quota.try_consume("dev-1", now).await.unwrap();
        // A different handset is unaffected by the first one's usage.
        quota.try_consume("dev-2", now).await.unwrap();
    }

    #[tokio::test]
    async fn the_count_resets_the_next_day() {
        let quota = Quota::new(1);
        let today = at("2026-09-20T23:59:00Z");

        quota.try_consume("dev-1", today).await.unwrap();
        quota.try_consume("dev-1", today).await.unwrap_err();

        let tomorrow = today + Duration::minutes(2);
        let usage = quota.try_consume("dev-1", tomorrow).await.unwrap();
        assert_eq!(usage.used, 1);
    }

    #[tokio::test]
    async fn a_zero_limit_means_unlimited() {
        let quota = Quota::new(0);
        let now = at("2026-09-20T10:00:00Z");

        for _ in 0..100 {
            quota.try_consume("dev-1", now).await.unwrap();
        }
    }

    #[tokio::test]
    async fn pruning_drops_stale_days() {
        let quota = Quota::new(5);
        let today = at("2026-09-20T10:00:00Z");
        quota.try_consume("dev-1", today).await.unwrap();

        let tomorrow = at("2026-09-21T10:00:00Z");
        assert_eq!(quota.prune(tomorrow).await, 1);
    }
}
