use axum::extract::State;
use axum::Json;
use serde::{Deserialize, Serialize};

use crate::auth::AdminCaller;
use crate::error::{ApiError, ApiResult};
use crate::state::AppState;

#[derive(Deserialize)]
pub struct MintRequest {
    /// How many months each minted code is worth.
    pub months: i64,
    #[serde(default = "one")]
    pub count: usize,
    /// Optional label for the batch, e.g. who it was sold to.
    #[serde(default)]
    pub note: String,
}

fn one() -> usize {
    1
}

#[derive(Serialize)]
pub struct MintResponse {
    pub months: i64,
    /// Plaintext codes, returned exactly once. The server keeps only hashes, so
    /// there is no way to read them back later.
    pub codes: Vec<String>,
    pub minted_total: usize,
    pub used_total: usize,
}

/// Mints redemption codes.
///
/// Guarded by the admin bearer token: this endpoint creates something people pay
/// for, so it is the one place in the service where a leaked credential costs
/// real money.
pub async fn mint_codes(
    State(state): State<AppState>,
    _admin: AdminCaller,
    Json(request): Json<MintRequest>,
) -> ApiResult<Json<MintResponse>> {
    let codes = state
        .store
        .mint_codes(request.months, request.count, &request.note)
        .await
        .map_err(|e| ApiError::BadRequest(e.to_string()))?;

    let (minted_total, used_total) = state.store.code_stats().await;

    tracing::info!(
        months = request.months,
        count = codes.len(),
        note = %request.note,
        "minted redemption codes"
    );

    Ok(Json(MintResponse {
        months: request.months,
        codes,
        minted_total,
        used_total,
    }))
}

#[derive(Serialize)]
pub struct StatsResponse {
    pub devices: usize,
    pub codes_minted: usize,
    pub codes_used: usize,
}

pub async fn stats(
    State(state): State<AppState>,
    _admin: AdminCaller,
) -> ApiResult<Json<StatsResponse>> {
    let (codes_minted, codes_used) = state.store.code_stats().await;
    Ok(Json(StatsResponse {
        devices: state.store.device_count().await,
        codes_minted,
        codes_used,
    }))
}
