use axum::body::Body;
use axum::extract::State;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde_json::{json, Value};

use crate::auth::SubscribedDevice;
use crate::error::{ApiError, ApiResult};
use crate::prompts;
use crate::state::AppState;
use crate::upstream::{ChatMessage, ChatRequest, UpstreamRequest};

#[derive(serde::Deserialize)]
pub struct ExplainRequest {
    /// Raw text scraped off the phone screen, in reading order.
    pub screen_text: String,
    #[serde(default)]
    pub language: Option<String>,
    /// Optional follow-up, e.g. "so should I tap the red button?".
    #[serde(default)]
    pub question: Option<String>,
    #[serde(default)]
    pub stream: bool,
}

/// "What is this screen?" -- the one-tap feature on the floating panel.
///
/// It exists as its own endpoint rather than being assembled on the phone so the
/// wording of the instruction can be improved server-side without shipping a new
/// app build to users who will never press "update".
pub async fn explain_screen(
    State(state): State<AppState>,
    SubscribedDevice(device): SubscribedDevice,
    Json(request): Json<ExplainRequest>,
) -> ApiResult<Response> {
    let screen_text = request.screen_text.trim();
    if screen_text.is_empty() {
        return Err(ApiError::BadRequest("screen_text must not be empty".into()));
    }

    let language = request.language.as_deref().unwrap_or("en");
    let mut content = format!(
        "{}

---
{}
---",
        prompts::explain_screen_instruction(language),
        screen_text
    );
    if let Some(question) = request.question.as_deref().map(str::trim).filter(|q| !q.is_empty()) {
        content.push_str("

");
        content.push_str(question);
    }

    let chat = ChatRequest {
        model: None,
        messages: vec![ChatMessage {
            role: "user".to_string(),
            content,
        }],
        stream: request.stream,
        temperature: None,
        max_tokens: None,
        language: Some(language.to_string()),
    };

    tracing::info!(device = %device.device_id, language = %language, "explain screen");
    proxy_chat(state, chat).await
}

pub async fn models(State(state): State<AppState>) -> Json<Value> {
    // The app shows this list when the user picks a model. For the EverCare
    // service there is exactly one: whatever we are paying for upstream.
    Json(json!({
        "object": "list",
        "data": [{
            "id": state.upstream.default_model(),
            "object": "model",
            "owned_by": "evercare",
        }],
    }))
}

pub async fn chat_completions(
    State(state): State<AppState>,
    SubscribedDevice(_device): SubscribedDevice,
    Json(request): Json<ChatRequest>,
) -> ApiResult<Response> {
    if request.messages.is_empty() {
        return Err(ApiError::BadRequest("messages must not be empty".into()));
    }
    proxy_chat(state, request).await
}

async fn proxy_chat(state: AppState, request: ChatRequest) -> ApiResult<Response> {
    let language = request.language.as_deref().unwrap_or("en");
    let messages = with_system_prompt(language, request.messages);

    // The model is ours to choose: a subscriber pays for "the EverCare assistant",
    // not for a specific model, and letting the client name one would let it bill
    // us for an arbitrarily expensive model.
    let body = UpstreamRequest {
        model: state.upstream.default_model().to_string(),
        messages,
        stream: request.stream,
        temperature: request.temperature,
        max_tokens: request.max_tokens,
    };

    tracing::debug!(language = %language, stream = body.stream, "forwarding to provider");

    let response = state
        .upstream
        .chat(&body)
        .await
        .map_err(|e| ApiError::Upstream(e.to_string()))?;

    let status = response.status();
    if !status.is_success() {
        let detail = response
            .text()
            .await
            .unwrap_or_else(|_| "<no body>".to_string());
        tracing::warn!(%status, %detail, "upstream rejected the request");
        return Err(ApiError::Upstream(format!("provider returned {status}")));
    }

    // Stream the upstream response straight through, so the first words reach the
    // phone while the model is still writing. The elderly user sees the assistant
    // "start talking" instead of staring at a spinner.
    let content_type = response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or(if body.stream {
            "text/event-stream"
        } else {
            "application/json"
        })
        .to_string();

    let stream = futures_util::TryStreamExt::map_err(response.bytes_stream(), std::io::Error::other);

    Ok(Response::builder()
        .status(status)
        .header(axum::http::header::CONTENT_TYPE, content_type)
        .header(axum::http::header::CACHE_CONTROL, "no-cache")
        .body(Body::from_stream(stream))
        .map_err(|e| ApiError::Internal(e.to_string()))?
        .into_response())
}

/// Ensures the conversation starts with an EverCare system prompt in the
/// caller's language. A client-supplied system message is kept, but ours goes
/// first: the tone rules are not negotiable from the app side.
fn with_system_prompt(language: &str, messages: Vec<ChatMessage>) -> Vec<ChatMessage> {
    let mut result = Vec::with_capacity(messages.len() + 1);
    result.push(ChatMessage {
        role: "system".to_string(),
        content: prompts::system_prompt(language).to_string(),
    });
    result.extend(messages);
    result
}
