use axum::extract::FromRequestParts;
use axum::http::request::Parts;

use crate::error::ApiError;
use crate::state::AppState;
use crate::store::Device;

/// Extractor that resolves the bearer token to a registered device.
/// Presence of a device says nothing about whether it may spend tokens --
/// see [`SubscribedDevice`] for that.
pub struct AuthedDevice(pub Device);

impl FromRequestParts<AppState> for AuthedDevice {
    type Rejection = ApiError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let token = parts
            .headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.strip_prefix("Bearer "))
            .map(str::trim)
            .filter(|token| !token.is_empty())
            .ok_or(ApiError::Unauthorized)?;

        let device = state
            .store
            .authenticate(token)
            .await
            .ok_or(ApiError::Unauthorized)?;

        // "Last seen" is what tells a family member whether the phone is still
        // being used at all, so it is recorded on every authenticated call.
        state.store.touch_device(&device.device_id).await;

        Ok(AuthedDevice(device))
    }
}

/// Same as [`AuthedDevice`], but additionally requires a live subscription.
/// Endpoints that cost upstream tokens take this one.
pub struct SubscribedDevice(pub Device);

impl FromRequestParts<AppState> for SubscribedDevice {
    type Rejection = ApiError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let AuthedDevice(device) = AuthedDevice::from_request_parts(parts, state).await?;
        if device.is_entitled(chrono::Utc::now()) {
            Ok(SubscribedDevice(device))
        } else {
            Err(ApiError::SubscriptionRequired)
        }
    }
}


/// Proves the caller holds the admin bearer token.
///
/// Guards code minting, which is effectively the ability to create money.
pub struct AdminCaller;

impl FromRequestParts<AppState> for AdminCaller {
    type Rejection = ApiError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let token = parts
            .headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.strip_prefix("Bearer "))
            .map(str::trim)
            .unwrap_or_default();

        if state.config.is_admin_token(token) {
            Ok(AdminCaller)
        } else {
            tracing::warn!("rejected admin request");
            Err(ApiError::Unauthorized)
        }
    }
}
