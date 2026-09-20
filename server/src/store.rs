//! Device and subscription state.
//!
//! The subscription is bound to the **device id**. There are no accounts, no
//! passwords and no email addresses anywhere in this service: an elderly user
//! should be able to install the app and get help without ever registering
//! anything. The handset registers itself once, keeps a token, and that token is
//! the whole identity.
//!
//! The cost of that choice is that a subscription does not automatically follow
//! the user to a new phone, so [`Store::transfer_subscription`] exists to move
//! the remaining days across when they replace the handset.

use std::collections::HashMap;
use std::sync::Arc;

use chrono::{DateTime, Duration, Utc};

use crate::codes::{self, RedeemCode};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::sync::RwLock;

pub use error::{Error, Result};

/// A paid (or trial) entitlement, owned by one device.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Subscription {
    /// `none`, `trial` or `monthly`.
    pub plan: String,
    pub expires_at: DateTime<Utc>,
    #[serde(default)]
    pub auto_renew: bool,
    pub updated_at: DateTime<Utc>,
}

impl Subscription {
    pub fn none() -> Self {
        Self {
            plan: "none".to_string(),
            expires_at: DateTime::<Utc>::MIN_UTC,
            auto_renew: false,
            updated_at: Utc::now(),
        }
    }

    pub fn trial(days: i64) -> Self {
        let now = Utc::now();
        Self {
            plan: if days > 0 { "trial" } else { "none" }.to_string(),
            expires_at: now + Duration::days(days.max(0)),
            auto_renew: false,
            updated_at: now,
        }
    }

    pub fn is_active(&self, now: DateTime<Utc>) -> bool {
        self.expires_at > now
    }

    pub fn days_remaining(&self, now: DateTime<Utc>) -> i64 {
        (self.expires_at - now).num_days().max(0)
    }

    /// Adds whole months (30 days each) starting from whichever is later: now, or
    /// the end of the current period. Renewing early must never lose paid days.
    pub fn extend(&mut self, months: i64, plan: &str) {
        let now = Utc::now();
        let base = if self.expires_at > now {
            self.expires_at
        } else {
            now
        };
        self.expires_at = base + Duration::days(30 * months);
        self.plan = plan.to_string();
        self.updated_at = now;
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Device {
    pub device_id: String,
    /// Only the hash is stored, so a leaked data file does not leak live tokens.
    token_hash: String,
    /// Free text, set by the app, e.g. the phone model. Never a personal name we
    /// asked for -- it exists so a family member can tell two handsets apart.
    pub label: String,
    pub platform: String,
    pub created_at: DateTime<Utc>,
    pub last_seen_at: DateTime<Utc>,
    pub subscription: Subscription,
}

impl Device {
    pub fn is_entitled(&self, now: DateTime<Utc>) -> bool {
        self.subscription.is_active(now)
    }
}

#[derive(Default, Serialize, Deserialize)]
struct Snapshot {
    #[serde(default)]
    devices: Vec<Device>,
    #[serde(default)]
    codes: Vec<RedeemCode>,
}

/// A JSON-file-backed store. Deliberately not a database: the record count is
/// small and this keeps deployment to a single binary plus one file. The
/// interface is async and keyed by id so swapping in Postgres later touches only
/// this file.
#[derive(Clone)]
pub struct Store {
    path: Arc<String>,
    devices: Arc<RwLock<HashMap<String, Device>>>,
    /// Keyed by code hash. Never holds a plaintext code.
    codes: Arc<RwLock<HashMap<String, RedeemCode>>>,
}

impl Store {
    pub async fn load(path: &str) -> Result<Self> {
        let snapshot: Snapshot = match tokio::fs::read(path).await {
            Ok(bytes) => serde_json::from_slice(&bytes)
                .map_err(|e| Error(format!("corrupt data file {path}: {e}")))?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Snapshot::default(),
            Err(e) => return Err(Error(format!("cannot read {path}: {e}"))),
        };

        let devices = snapshot
            .devices
            .into_iter()
            .map(|d| (d.device_id.clone(), d))
            .collect();
        let codes = snapshot
            .codes
            .into_iter()
            .map(|c| (c.code_hash.clone(), c))
            .collect();

        Ok(Self {
            path: Arc::new(path.to_string()),
            devices: Arc::new(RwLock::new(devices)),
            codes: Arc::new(RwLock::new(codes)),
        })
    }

    /// Registers a handset and starts its trial. No personal data is taken.
    pub async fn register_device(
        &self,
        label: &str,
        platform: &str,
        trial_days: i64,
    ) -> Result<(Device, String)> {
        let device_id = uuid::Uuid::new_v4().to_string();
        let token = format!("dev_{device_id}.{}", random_secret());
        let now = Utc::now();

        let device = Device {
            device_id: device_id.clone(),
            token_hash: hash_token(&token),
            label: if label.trim().is_empty() {
                "EverCare".to_string()
            } else {
                label.trim().to_string()
            },
            platform: platform.trim().to_lowercase(),
            created_at: now,
            last_seen_at: now,
            subscription: Subscription::trial(trial_days),
        };

        self.devices
            .write()
            .await
            .insert(device_id, device.clone());
        self.persist().await?;
        Ok((device, token))
    }

    /// Resolves a bearer token to its device.
    ///
    /// Tokens are `dev_<uuid>.<secret>`, so this is one map lookup plus a hash
    /// comparison rather than a scan.
    pub async fn authenticate(&self, token: &str) -> Option<Device> {
        let (head, _) = token.split_once('.')?;
        let device_id = head.strip_prefix("dev_")?;

        let device = self.devices.read().await.get(device_id).cloned()?;
        if device.token_hash == hash_token(token) {
            Some(device)
        } else {
            None
        }
    }

    /// Records that a handset phoned home. Best-effort and not persisted on its
    /// own: a failure here must never fail the request the user actually made.
    pub async fn touch_device(&self, device_id: &str) {
        if let Some(device) = self.devices.write().await.get_mut(device_id) {
            device.last_seen_at = Utc::now();
        }
    }

    pub async fn extend_subscription(
        &self,
        device_id: &str,
        months: i64,
        plan: &str,
    ) -> Result<Device> {
        let device = {
            let mut devices = self.devices.write().await;
            let device = devices
                .get_mut(device_id)
                .ok_or_else(|| Error("no such device".into()))?;
            device.subscription.extend(months, plan);
            device.clone()
        };

        self.persist().await?;
        Ok(device)
    }

    pub async fn set_auto_renew(&self, device_id: &str, auto_renew: bool) -> Result<Device> {
        let device = {
            let mut devices = self.devices.write().await;
            let device = devices
                .get_mut(device_id)
                .ok_or_else(|| Error("no such device".into()))?;
            device.subscription.auto_renew = auto_renew;
            device.subscription.updated_at = Utc::now();
            device.clone()
        };

        self.persist().await?;
        Ok(device)
    }

    /// Moves the remaining subscription from one handset to another.
    ///
    /// This is the escape hatch for device-bound plans: when the user replaces
    /// their phone, the new handset registers itself and then presents the old
    /// device's token once to inherit the paid days. The old device is left with
    /// nothing, so the plan cannot be duplicated.
    pub async fn transfer_subscription(&self, from_id: &str, to_id: &str) -> Result<Device> {
        if from_id == to_id {
            return Err(Error("cannot transfer a subscription to the same device".into()));
        }

        let device = {
            let mut devices = self.devices.write().await;
            if !devices.contains_key(to_id) {
                return Err(Error("no such device".into()));
            }

            let source = devices
                .get_mut(from_id)
                .ok_or_else(|| Error("no such device".into()))?;
            if !source.subscription.is_active(Utc::now()) {
                return Err(Error("the other device has no active subscription".into()));
            }
            let moved = std::mem::replace(&mut source.subscription, Subscription::none());

            let target = devices.get_mut(to_id).expect("checked above");
            target.subscription = moved;
            target.subscription.updated_at = Utc::now();
            target.clone()
        };

        self.persist().await?;
        Ok(device)
    }

    /// Permanently forgets a device. Its token stops authenticating immediately.
    pub async fn delete_device(&self, device_id: &str) -> Result<()> {
        {
            let mut devices = self.devices.write().await;
            if devices.remove(device_id).is_none() {
                return Err(Error("no such device".into()));
            }
        }
        self.persist().await
    }

    /// Mints `count` codes worth `months` each.
    ///
    /// Returns the plaintext codes. This is the only moment they exist outside
    /// the hands of whoever runs the service: the store keeps hashes.
    pub async fn mint_codes(&self, months: i64, count: usize, note: &str) -> Result<Vec<String>> {
        if !(1..=36).contains(&months) {
            return Err(Error("months must be between 1 and 36".into()));
        }
        if !(1..=200).contains(&count) {
            return Err(Error("count must be between 1 and 200".into()));
        }

        let mut plaintext = Vec::with_capacity(count);
        {
            let mut codes = self.codes.write().await;
            for _ in 0..count {
                let code = codes::generate_code();
                let record = RedeemCode {
                    code_hash: hash_token(&code),
                    months,
                    note: note.trim().to_string(),
                    created_at: Utc::now(),
                    used_by: None,
                    used_at: None,
                };
                codes.insert(record.code_hash.clone(), record);
                plaintext.push(code);
            }
        }

        self.persist().await?;
        Ok(plaintext)
    }

    /// Redeems `code` for `device_id`.
    ///
    /// The duration comes from the code, never from the caller: a one-month code
    /// buys one month even if the request asks for thirty-six. The code is marked
    /// used before the subscription is extended, so a failure in between costs a
    /// support conversation rather than letting a code be spent twice.
    pub async fn redeem_code(&self, code: &str, device_id: &str) -> Result<(Device, i64)> {
        let hash = hash_token(&codes::normalise(code));

        let months = {
            let mut codes = self.codes.write().await;
            let record = codes
                .get_mut(&hash)
                .ok_or_else(|| Error("兑换码无效".into()))?;

            if record.is_used() {
                return Err(Error("兑换码已经用过了".into()));
            }

            record.used_by = Some(device_id.to_string());
            record.used_at = Some(Utc::now());
            record.months
        };

        let device = {
            let mut devices = self.devices.write().await;
            let device = devices
                .get_mut(device_id)
                .ok_or_else(|| Error("no such device".into()))?;
            device.subscription.extend(months, "monthly");
            device.clone()
        };

        self.persist().await?;
        Ok((device, months))
    }

    /// How many codes have been minted, and how many of those are spent.
    pub async fn code_stats(&self) -> (usize, usize) {
        let codes = self.codes.read().await;
        let used = codes.values().filter(|c| c.is_used()).count();
        (codes.len(), used)
    }

    pub async fn device_count(&self) -> usize {
        self.devices.read().await.len()
    }

    async fn persist(&self) -> Result<()> {
        let snapshot = Snapshot {
            devices: self.devices.read().await.values().cloned().collect(),
            codes: self.codes.read().await.values().cloned().collect(),
        };

        let json = serde_json::to_vec_pretty(&snapshot)
            .map_err(|e| Error(format!("cannot serialize store: {e}")))?;

        // Write-then-rename so a crash mid-write cannot truncate the live file.
        let tmp = format!("{}.tmp", self.path);
        tokio::fs::write(&tmp, &json)
            .await
            .map_err(|e| Error(format!("cannot write {tmp}: {e}")))?;
        tokio::fs::rename(&tmp, self.path.as_str())
            .await
            .map_err(|e| Error(format!("cannot replace {}: {e}", self.path)))?;
        Ok(())
    }
}

pub fn hash_token(token: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(token.as_bytes());
    hex::encode(hasher.finalize())
}

fn random_secret() -> String {
    use rand::RngCore;
    let mut bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut bytes);
    hex::encode(bytes)
}

/// Tiny local error type, kept so store failures carry a message without another
/// dependency.
mod error {
    #[derive(Debug)]
    pub struct Error(pub String);

    impl std::fmt::Display for Error {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "{}", self.0)
        }
    }

    impl std::error::Error for Error {}

    pub type Result<T> = std::result::Result<T, Error>;
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn temp_store(name: &str) -> Store {
        let path = std::env::temp_dir().join(format!("evercare-test-{name}.json"));
        let _ = std::fs::remove_file(&path);
        Store::load(path.to_str().unwrap()).await.unwrap()
    }

    #[tokio::test]
    async fn a_new_device_starts_on_a_trial() {
        let store = temp_store("trial").await;
        let (device, token) = store
            .register_device("奶奶的手机", "android", 7)
            .await
            .unwrap();

        let authed = store.authenticate(&token).await.unwrap();
        assert_eq!(authed.device_id, device.device_id);
        assert!(authed.is_entitled(Utc::now()));
        assert_eq!(authed.subscription.plan, "trial");
        assert_eq!(authed.subscription.days_remaining(Utc::now()), 6);
    }

    #[tokio::test]
    async fn a_zero_day_trial_is_not_entitled() {
        let store = temp_store("expired").await;
        let (_, token) = store.register_device("phone", "android", 0).await.unwrap();

        let device = store.authenticate(&token).await.unwrap();
        assert!(!device.is_entitled(Utc::now()));
        assert_eq!(device.subscription.plan, "none");
    }

    #[tokio::test]
    async fn a_wrong_token_does_not_authenticate() {
        let store = temp_store("wrongtoken").await;
        let (device, _) = store.register_device("phone", "android", 7).await.unwrap();

        // Right device id, wrong secret.
        let forged = format!("dev_{}.{}", device.device_id, random_secret());
        assert!(store.authenticate(&forged).await.is_none());
        assert!(store.authenticate("garbage").await.is_none());
        assert!(store.authenticate("dev_nosuchdevice.secret").await.is_none());
    }

    #[tokio::test]
    async fn redeeming_activates_a_monthly_plan() {
        let store = temp_store("redeem").await;
        let (device, token) = store.register_device("phone", "android", 0).await.unwrap();
        assert!(!store.authenticate(&token).await.unwrap().is_entitled(Utc::now()));

        store
            .extend_subscription(&device.device_id, 1, "monthly")
            .await
            .unwrap();

        let authed = store.authenticate(&token).await.unwrap();
        assert!(authed.is_entitled(Utc::now()));
        assert_eq!(authed.subscription.plan, "monthly");
        assert_eq!(authed.subscription.days_remaining(Utc::now()), 29);
    }

    #[tokio::test]
    async fn renewing_early_keeps_the_remaining_days() {
        let mut subscription = Subscription::trial(10);
        let first_expiry = subscription.expires_at;
        subscription.extend(1, "monthly");
        assert_eq!(subscription.expires_at, first_expiry + Duration::days(30));
    }

    #[tokio::test]
    async fn transfer_moves_the_plan_to_the_new_phone() {
        let store = temp_store("transfer").await;
        let (old, old_token) = store.register_device("old", "android", 0).await.unwrap();
        let (new, new_token) = store.register_device("new", "android", 0).await.unwrap();
        store
            .extend_subscription(&old.device_id, 2, "monthly")
            .await
            .unwrap();

        store
            .transfer_subscription(&old.device_id, &new.device_id)
            .await
            .unwrap();

        assert!(store.authenticate(&new_token).await.unwrap().is_entitled(Utc::now()));
        // The old handset must not keep a copy.
        assert!(!store.authenticate(&old_token).await.unwrap().is_entitled(Utc::now()));
    }

    #[tokio::test]
    async fn transfer_requires_an_active_plan_on_the_source() {
        let store = temp_store("transfer-empty").await;
        let (old, _) = store.register_device("old", "android", 0).await.unwrap();
        let (new, _) = store.register_device("new", "android", 0).await.unwrap();

        assert!(store
            .transfer_subscription(&old.device_id, &new.device_id)
            .await
            .is_err());
    }

    #[tokio::test]
    async fn deleted_device_token_stops_authenticating() {
        let store = temp_store("delete").await;
        let (device, token) = store.register_device("phone", "android", 7).await.unwrap();

        store.delete_device(&device.device_id).await.unwrap();
        assert!(store.authenticate(&token).await.is_none());
    }

    #[tokio::test]
    async fn a_minted_code_grants_exactly_what_it_is_worth() {
        let store = temp_store("codes").await;
        let (device, token) = store.register_device("phone", "android", 0).await.unwrap();

        let minted = store.mint_codes(12, 1, "test batch").await.unwrap();
        let code = &minted[0];
        assert_eq!(code.len(), 128);

        let (_, months) = store.redeem_code(code, &device.device_id).await.unwrap();
        assert_eq!(months, 12);

        let authed = store.authenticate(&token).await.unwrap();
        assert!(authed.is_entitled(Utc::now()));
        // Twelve 30-day months, minus the day already under way.
        assert_eq!(authed.subscription.days_remaining(Utc::now()), 359);
    }

    #[tokio::test]
    async fn a_code_cannot_be_used_twice() {
        let store = temp_store("codes-once").await;
        let (first, _) = store.register_device("a", "android", 0).await.unwrap();
        let (second, _) = store.register_device("b", "android", 0).await.unwrap();

        let minted = store.mint_codes(1, 1, "").await.unwrap();

        store.redeem_code(&minted[0], &first.device_id).await.unwrap();
        // The same device again, and a different one: both must fail.
        assert!(store.redeem_code(&minted[0], &first.device_id).await.is_err());
        assert!(store.redeem_code(&minted[0], &second.device_id).await.is_err());
    }

    #[tokio::test]
    async fn an_unknown_code_is_refused() {
        let store = temp_store("codes-unknown").await;
        let (device, _) = store.register_device("phone", "android", 0).await.unwrap();

        assert!(store.redeem_code("not-a-code", &device.device_id).await.is_err());
        assert!(store
            .redeem_code(&crate::codes::generate_code(), &device.device_id)
            .await
            .is_err());
    }

    #[tokio::test]
    async fn spacing_and_case_do_not_break_a_code() {
        let store = temp_store("codes-format").await;
        let (device, _) = store.register_device("phone", "android", 0).await.unwrap();
        let minted = store.mint_codes(1, 1, "").await.unwrap();

        // What someone types after reading it off a slip of paper.
        let typed = format!("  {}-{}  ", &minted[0][..64].to_uppercase(), &minted[0][64..]);
        assert!(store.redeem_code(&typed, &device.device_id).await.is_ok());
    }

    #[tokio::test]
    async fn codes_survive_a_restart_and_stay_used() {
        let path = std::env::temp_dir().join("evercare-test-codes-reload.json");
        let _ = std::fs::remove_file(&path);
        let path = path.to_str().unwrap();

        let (code, device_id) = {
            let store = Store::load(path).await.unwrap();
            let (device, _) = store.register_device("phone", "android", 0).await.unwrap();
            let minted = store.mint_codes(1, 1, "").await.unwrap();
            store.redeem_code(&minted[0], &device.device_id).await.unwrap();
            (minted[0].clone(), device.device_id)
        };

        let reopened = Store::load(path).await.unwrap();
        assert!(
            reopened.redeem_code(&code, &device_id).await.is_err(),
            "a used code must stay used across a restart"
        );
        assert_eq!(reopened.code_stats().await, (1, 1));
    }

    #[tokio::test]
    async fn minting_rejects_silly_inputs() {
        let store = temp_store("codes-bounds").await;
        assert!(store.mint_codes(0, 1, "").await.is_err());
        assert!(store.mint_codes(37, 1, "").await.is_err());
        assert!(store.mint_codes(1, 0, "").await.is_err());
        assert!(store.mint_codes(1, 500, "").await.is_err());
    }

    #[tokio::test]
    async fn state_survives_a_restart() {
        let path = std::env::temp_dir().join("evercare-test-reload.json");
        let _ = std::fs::remove_file(&path);
        let path = path.to_str().unwrap();

        let token = {
            let store = Store::load(path).await.unwrap();
            let (device, token) = store.register_device("phone", "android", 0).await.unwrap();
            store
                .extend_subscription(&device.device_id, 2, "monthly")
                .await
                .unwrap();
            token
        };

        let reopened = Store::load(path).await.unwrap();
        let device = reopened.authenticate(&token).await.unwrap();
        assert!(device.is_entitled(Utc::now()));
        assert_eq!(device.subscription.plan, "monthly");
        assert_eq!(reopened.device_count().await, 1);
    }
}
