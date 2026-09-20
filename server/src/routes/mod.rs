pub mod admin;
pub mod chat;
pub mod health;
pub mod subscription;

use axum::routing::{delete, get, post};
use axum::Router;

use crate::state::AppState;

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/healthz", get(health::healthz))
        // OpenAI-compatible surface. A client configured against EverCare and a
        // client configured against a third-party provider hit the same paths
        // with the same payloads, which is what makes "use your own provider" a
        // configuration change rather than a second code path in the app.
        .route("/v1/models", get(chat::models))
        .route("/v1/chat/completions", post(chat::chat_completions))
        // EverCare-specific surface, namespaced so it cannot collide with the
        // OpenAI one.
        .route("/v1/evercare/devices", post(subscription::register_device))
        .route("/v1/evercare/devices/me", delete(subscription::delete_device))
        .route("/v1/evercare/explain", post(chat::explain_screen))
        .route("/v1/evercare/subscription", get(subscription::status))
        .route(
            "/v1/evercare/subscription/redeem",
            post(subscription::redeem),
        )
        .route(
            "/v1/evercare/subscription/auto-renew",
            post(subscription::set_auto_renew),
        )
        .route(
            "/v1/evercare/subscription/transfer",
            post(subscription::transfer),
        )
        // Operator-only. Guarded by EVERCARE_ADMIN_TOKEN; disabled when unset.
        .route("/v1/evercare/admin/codes", post(admin::mint_codes))
        .route("/v1/evercare/admin/stats", get(admin::stats))
        .with_state(state)
}
