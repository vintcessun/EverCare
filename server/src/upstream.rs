use serde::{Deserialize, Serialize};

/// The subset of the OpenAI chat-completions schema the app actually uses.
/// DeepSeek, OpenAI, Moonshot and most self-hosted gateways all speak it, which
/// is why the client can talk to EverCare's own service and to a user-supplied
/// third-party endpoint through exactly the same code path.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct ChatRequest {
    /// Accepted because every OpenAI client sends it, and deliberately ignored:
    /// a subscriber pays for "the EverCare assistant", not for a model of their
    /// choosing, and honouring this field would let a client bill us for an
    /// arbitrarily expensive one.
    #[serde(default)]
    #[allow(dead_code)]
    pub model: Option<String>,
    pub messages: Vec<ChatMessage>,
    #[serde(default)]
    pub stream: bool,
    #[serde(default)]
    pub temperature: Option<f32>,
    #[serde(default)]
    pub max_tokens: Option<u32>,
    /// EverCare extension: BCP-47 tag of the phone's language, e.g. `zh-CN`.
    /// Drives which system prompt is prepended. Ignored by upstream providers
    /// because we strip it before forwarding.
    #[serde(default)]
    pub language: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct UpstreamRequest {
    pub model: String,
    pub messages: Vec<ChatMessage>,
    pub stream: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub temperature: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_tokens: Option<u32>,
}

#[derive(Clone)]
pub struct Upstream {
    client: reqwest::Client,
    base_url: String,
    api_key: String,
    default_model: String,
}

impl Upstream {
    pub fn new(base_url: String, api_key: String, default_model: String) -> Self {
        let client = reqwest::Client::builder()
            // Generous: a long explanation over a slow mobile link still needs to land.
            .timeout(std::time::Duration::from_secs(120))
            .connect_timeout(std::time::Duration::from_secs(15))
            .build()
            .expect("failed to build HTTP client");

        Self {
            client,
            base_url,
            api_key,
            default_model,
        }
    }

    pub fn default_model(&self) -> &str {
        &self.default_model
    }

    pub async fn chat(&self, body: &UpstreamRequest) -> reqwest::Result<reqwest::Response> {
        self.client
            .post(format!("{}/v1/chat/completions", self.base_url))
            .bearer_auth(&self.api_key)
            .json(body)
            .send()
            .await
    }
}
