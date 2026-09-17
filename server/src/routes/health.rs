use axum::Json;
use serde_json::{json, Value};

pub async fn healthz() -> Json<Value> {
    Json(json!({
        "status": "ok",
        "service": "evercare-server",
        "version": env!("CARGO_PKG_VERSION"),
    }))
}
