use axum::extract::State;
use axum::Json;
use chrono::Utc;
use serde::{Deserialize, Serialize};

use crate::auth::AuthedDevice;
use crate::error::{ApiError, ApiResult};
use crate::state::AppState;
use crate::store::Device;

#[derive(Debug, Default, Deserialize)]
pub struct RegisterRequest {
    /// Something a family member can recognise later, e.g. the phone model.
    #[serde(default)]
    pub label: Option<String>,
    #[serde(default)]
    pub platform: Option<String>,
}

#[derive(Serialize)]
pub struct RegisterResponse {
    pub device_id: String,
    /// Returned exactly once. The app stores it in platform secure storage; the
    /// server keeps only its hash, so it cannot be recovered afterwards.
    pub token: String,
    pub subscription: SubscriptionStatus,
}

#[derive(Serialize)]
pub struct SubscriptionStatus {
    pub device_id: String,
    pub active: bool,
    pub plan: String,
    pub expires_at: String,
    pub days_remaining: i64,
    pub auto_renew: bool,
}

impl From<&Device> for SubscriptionStatus {
    fn from(device: &Device) -> Self {
        let now = Utc::now();
        Self {
            device_id: device.device_id.clone(),
            active: device.is_entitled(now),
            plan: device.subscription.plan.clone(),
            expires_at: device.subscription.expires_at.to_rfc3339(),
            days_remaining: device.subscription.days_remaining(now),
            auto_renew: device.subscription.auto_renew,
        }
    }
}

/// Registers a fresh handset and starts its trial.
///
/// No account, no password, no email: the subscription is bound to the device id
/// that comes back from here. An elderly user should never have to create an
/// account before the app will help them.
pub async fn register_device(
    State(state): State<AppState>,
    body: Option<Json<RegisterRequest>>,
) -> ApiResult<Json<RegisterResponse>> {
    let request = body.map(|Json(b)| b).unwrap_or_default();

    let (device, token) = state
        .store
        .register_device(
            request.label.as_deref().unwrap_or(""),
            request.platform.as_deref().unwrap_or("unknown"),
            state.config.trial_days,
        )
        .await
        .map_err(|e| ApiError::Internal(e.to_string()))?;

    tracing::info!(
        device = %device.device_id,
        platform = %device.platform,
        "registered new device"
    );

    Ok(Json(RegisterResponse {
        device_id: device.device_id.clone(),
        token,
        subscription: SubscriptionStatus::from(&device),
    }))
}

pub async fn status(AuthedDevice(device): AuthedDevice) -> Json<SubscriptionStatus> {
    Json(SubscriptionStatus::from(&device))
}

#[derive(Deserialize)]
pub struct RedeemRequest {
    /// Placeholder for a real payment receipt (App Store / Play / WeChat Pay).
    pub code: String,
    #[serde(default = "one")]
    pub months: i64,
}

fn one() -> i64 {
    1
}

/// Grants subscription months to the calling device.
///
/// TODO(payments): `code` must be verified against the store receipt API before
/// any days are granted. This build accepts any non-empty code, which would hand
/// out free months to anyone who can POST -- it is wired up so the app's payment
/// flow can be built end to end, and must not ship as-is.
pub async fn redeem(
    State(state): State<AppState>,
    AuthedDevice(device): AuthedDevice,
    Json(request): Json<RedeemRequest>,
) -> ApiResult<Json<SubscriptionStatus>> {
    if request.code.trim().is_empty() {
        return Err(ApiError::BadRequest("code must not be empty".into()));
    }
    if !(1..=36).contains(&request.months) {
        return Err(ApiError::BadRequest(
            "months must be between 1 and 36".into(),
        ));
    }

    let updated = state
        .store
        .extend_subscription(&device.device_id, request.months, "monthly")
        .await
        .map_err(|e| ApiError::Internal(e.to_string()))?;

    tracing::info!(
        device = %updated.device_id,
        months = request.months,
        until = %updated.subscription.expires_at,
        "subscription extended"
    );

    Ok(Json(SubscriptionStatus::from(&updated)))
}

#[derive(Deserialize)]
pub struct AutoRenewRequest {
    pub auto_renew: bool,
}

pub async fn set_auto_renew(
    State(state): State<AppState>,
    AuthedDevice(device): AuthedDevice,
    Json(request): Json<AutoRenewRequest>,
) -> ApiResult<Json<SubscriptionStatus>> {
    let updated = state
        .store
        .set_auto_renew(&device.device_id, request.auto_renew)
        .await
        .map_err(|e| ApiError::Internal(e.to_string()))?;

    Ok(Json(SubscriptionStatus::from(&updated)))
}

#[derive(Deserialize)]
pub struct TransferRequest {
    /// The *old* handset's token. The user reads it from the old phone (the app
    /// shows it as a QR code) while the new phone is the one calling.
    pub from_token: String,
}

/// Moves a subscription from an old handset to this one.
///
/// This is the escape hatch for device-bound plans. Authentication is doubled up
/// on purpose: the caller must hold the new device's token *and* present the old
/// device's token, so neither one alone can steal a plan.
pub async fn transfer(
    State(state): State<AppState>,
    AuthedDevice(device): AuthedDevice,
    Json(request): Json<TransferRequest>,
) -> ApiResult<Json<SubscriptionStatus>> {
    let source = state
        .store
        .authenticate(request.from_token.trim())
        .await
        .ok_or_else(|| ApiError::BadRequest("the other device's code is not valid".into()))?;

    let updated = state
        .store
        .transfer_subscription(&source.device_id, &device.device_id)
        .await
        .map_err(|e| ApiError::BadRequest(e.to_string()))?;

    tracing::info!(
        from = %source.device_id,
        to = %updated.device_id,
        "subscription transferred"
    );

    Ok(Json(SubscriptionStatus::from(&updated)))
}

/// Forgets this device and its subscription. Used by "reset the app".
pub async fn delete_device(
    State(state): State<AppState>,
    AuthedDevice(device): AuthedDevice,
) -> ApiResult<Json<serde_json::Value>> {
    state
        .store
        .delete_device(&device.device_id)
        .await
        .map_err(|e| ApiError::Internal(e.to_string()))?;

    tracing::info!(device = %device.device_id, "device deleted");
    Ok(Json(serde_json::json!({ "deleted": true })))
}
