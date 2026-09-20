use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde_json::json;

#[derive(Debug, thiserror::Error)]
pub enum ApiError {
    #[error("missing or malformed Authorization header")]
    Unauthorized,
    #[error("no active subscription")]
    SubscriptionRequired,
    #[error("daily limit reached ({used}/{limit})")]
    QuotaExceeded { used: u32, limit: u32 },
    #[error("{0}")]
    BadRequest(String),
    #[error("upstream provider error: {0}")]
    Upstream(String),
    #[error("internal error: {0}")]
    Internal(String),
}

impl ApiError {
    fn parts(&self) -> (StatusCode, &'static str) {
        match self {
            // 401 tells the client to re-register the device.
            ApiError::Unauthorized => (StatusCode::UNAUTHORIZED, "unauthorized"),
            // 402 is the one the app turns into "订阅已过期" rather than a scary error.
            ApiError::SubscriptionRequired => {
                (StatusCode::PAYMENT_REQUIRED, "subscription_required")
            }
            // 429 is the one the app turns into "今天问得有点多了，明天再来"
            // rather than an error the user cannot act on.
            ApiError::QuotaExceeded { .. } => {
                (StatusCode::TOO_MANY_REQUESTS, "rate_limit_exceeded")
            }
            ApiError::BadRequest(_) => (StatusCode::BAD_REQUEST, "bad_request"),
            ApiError::Upstream(_) => (StatusCode::BAD_GATEWAY, "upstream_error"),
            ApiError::Internal(_) => (StatusCode::INTERNAL_SERVER_ERROR, "internal_error"),
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, code) = self.parts();
        // Mirrors the OpenAI error envelope so a client written for a third-party
        // provider can parse our failures with the same code path.
        let body = json!({
            "error": {
                "type": code,
                "message": self.to_string(),
            }
        });
        (status, Json(body)).into_response()
    }
}

pub type ApiResult<T> = Result<T, ApiError>;
