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

/// Whether a URL points at this machine.
///
/// Deliberately string-based rather than resolving DNS: this runs at startup and
/// only needs to catch the forms an operator actually writes in a config file.
fn is_loopback_url(url: &str) -> bool {
    let host = url
        .split("://")
        .nth(1)
        .unwrap_or(url)
        .split(['/', '?'])
        .next()
        .unwrap_or("")
        .rsplit_once(':')
        .map(|(h, _)| h)
        .unwrap_or_else(|| url.split("://").nth(1).unwrap_or(url));

    let host = host.trim_start_matches('[').trim_end_matches(']');
    host == "localhost" || host == "::1" || host.starts_with("127.")
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
        let mut builder = reqwest::Client::builder()
            // Generous: a long explanation over a slow mobile link still needs to land.
            .timeout(std::time::Duration::from_secs(120))
            .connect_timeout(std::time::Duration::from_secs(15));

        // reqwest picks up HTTP_PROXY/ALL_PROXY from the environment, which is
        // wrong for a gateway running on this same machine: the request would be
        // handed to a proxy that has no idea what 127.0.0.1 means to us. Hosts
        // that are already local get a client with proxies switched off.
        if is_loopback_url(&base_url) {
            builder = builder.no_proxy();
        }

        let client = builder.build().expect("failed to build HTTP client");

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
            // `base_url` is normalised to end with `/v1` by Config, so both
            // `https://api.deepseek.com` and `http://127.0.0.1:8787/v1/` work.
            .post(format!("{}/chat/completions", self.base_url))
            .bearer_auth(&self.api_key)
            .json(body)
            .send()
            .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn loopback_urls_are_recognised() {
        assert!(is_loopback_url("http://127.0.0.1:8787/v1"));
        assert!(is_loopback_url("http://localhost:8787/v1"));
        assert!(is_loopback_url("http://127.0.0.1/v1"));
        assert!(is_loopback_url("http://[::1]:8787/v1"));
    }

    #[test]
    fn remote_urls_are_not() {
        assert!(!is_loopback_url("https://api.deepseek.com/v1"));
        assert!(!is_loopback_url("https://evercare.vintces.icu/v1"));
        assert!(!is_loopback_url("http://192.168.10.10:8080/v1"));
    }
}
