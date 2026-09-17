//! EverCare AI service.
//!
//! An OpenAI-compatible gateway that injects elder-friendly system prompts in the
//! caller's language and gates access on a device-bound subscription.
//!
//! The binary is a thin wrapper around [`build_app`]; everything else lives here
//! so integration tests can run the real router in-process.

pub mod auth;
pub mod config;
pub mod error;
pub mod prompts;
pub mod routes;
pub mod state;
pub mod store;
pub mod upstream;

use std::time::Duration;

use axum::Router;
use tower_http::cors::{Any, CorsLayer};
use tower_http::limit::RequestBodyLimitLayer;
use tower_http::trace::TraceLayer;

use crate::config::Config;
use crate::state::AppState;
use crate::store::Store;
use crate::upstream::Upstream;

/// Builds the fully configured router, including its storage and HTTP client.
pub async fn build_app(config: Config) -> store::Result<Router> {
    let store = Store::load(&config.data_file).await?;
    tracing::info!(devices = store.device_count().await, "store loaded");

    let upstream = Upstream::new(
        config.upstream_base_url.clone(),
        config.upstream_api_key.clone(),
        config.upstream_model.clone(),
    );

    let state = AppState {
        config,
        store,
        upstream,
    };

    Ok(routes::router(state)
        .layer(TraceLayer::new_for_http())
        // Screen captures can be long, but nothing legitimate is megabytes.
        .layer(RequestBodyLimitLayer::new(512 * 1024))
        .layer(
            CorsLayer::new()
                .allow_origin(Any)
                .allow_methods(Any)
                .allow_headers(Any)
                .max_age(Duration::from_secs(3600)),
        ))
}
