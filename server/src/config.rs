use std::env;

/// Runtime configuration, read from the environment once at startup.
#[derive(Clone, Debug)]
pub struct Config {
    pub bind: String,
    pub upstream_base_url: String,
    pub upstream_api_key: String,
    pub upstream_model: String,
    pub data_file: String,
    pub trial_days: i64,

    /// Bearer token for the admin endpoints that mint redemption codes.
    ///
    /// Empty means **the admin API is disabled**, which is the safe default: an
    /// unprotected mint endpoint is a free subscription generator.
    pub admin_token: String,

    /// Chat requests one device may make per UTC day.
    pub daily_request_limit: u32,

    /// Upper bound on `max_tokens`, whatever the client asks for.
    pub max_tokens: u32,

    /// Upper bound on captured screen text, in characters.
    pub max_screen_chars: usize,
}

impl Config {
    pub fn from_env() -> Self {
        Self {
            bind: var("EVERCARE_BIND", "0.0.0.0:8080"),
            upstream_base_url: normalise_upstream_base(&var(
                "EVERCARE_UPSTREAM_BASE_URL",
                "https://api.deepseek.com",
            )),
            upstream_api_key: var("EVERCARE_UPSTREAM_API_KEY", ""),
            upstream_model: var("EVERCARE_UPSTREAM_MODEL", "deepseek-chat"),
            data_file: var("EVERCARE_DATA_FILE", "./evercare-data.json"),
            trial_days: parse("EVERCARE_TRIAL_DAYS", 7),
            admin_token: var("EVERCARE_ADMIN_TOKEN", ""),
            daily_request_limit: parse("EVERCARE_DAILY_REQUEST_LIMIT", 200),
            max_tokens: parse("EVERCARE_MAX_TOKENS", 800),
            max_screen_chars: parse("EVERCARE_MAX_SCREEN_CHARS", 6000),
        }
    }

    /// Explicit constructor used by tests, so they never have to mutate the
    /// process environment (which would race across parallel test threads).
    pub fn for_test(upstream_base_url: &str, data_file: &str, trial_days: i64) -> Self {
        Self {
            bind: "127.0.0.1:0".to_string(),
            upstream_base_url: normalise_upstream_base(upstream_base_url),
            upstream_api_key: "sk-stub".to_string(),
            upstream_model: "stub-model".to_string(),
            data_file: data_file.to_string(),
            trial_days,
            admin_token: "test-admin-token".to_string(),
            daily_request_limit: 200,
            max_tokens: 800,
            max_screen_chars: 6000,
        }
    }

    /// Constant-time-ish check of the admin bearer token.
    ///
    /// An empty configured token never matches, so forgetting to set
    /// `EVERCARE_ADMIN_TOKEN` disables minting rather than opening it up.
    pub fn is_admin_token(&self, presented: &str) -> bool {
        if self.admin_token.is_empty() || presented.is_empty() {
            return false;
        }
        if presented.len() != self.admin_token.len() {
            return false;
        }
        presented
            .bytes()
            .zip(self.admin_token.bytes())
            .fold(0u8, |acc, (a, b)| acc | (a ^ b))
            == 0
    }
}

/// Normalises an upstream base URL to the form `<root>/v1`.
///
/// Providers are inconsistent about this: DeepSeek's docs give
/// `https://api.deepseek.com`, while an OpenAI-compatible gateway usually gives
/// `http://127.0.0.1:8787/v1/`. Accepting either and appending `/chat/completions`
/// once is the difference between a working deployment and a 404 that looks like
/// an auth problem.
fn normalise_upstream_base(raw: &str) -> String {
    let trimmed = raw.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return String::new();
    }
    if trimmed.ends_with("/v1") {
        trimmed.to_string()
    } else {
        format!("{trimmed}/v1")
    }
}

fn var(key: &str, default: &str) -> String {
    env::var(key).unwrap_or_else(|_| default.to_string())
}

fn parse<T: std::str::FromStr>(key: &str, default: T) -> T {
    env::var(key)
        .ok()
        .and_then(|v| v.trim().parse().ok())
        .unwrap_or(default)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn upstream_base_accepts_both_conventions() {
        // DeepSeek style: root only.
        assert_eq!(
            normalise_upstream_base("https://api.deepseek.com"),
            "https://api.deepseek.com/v1"
        );
        assert_eq!(
            normalise_upstream_base("https://api.deepseek.com/"),
            "https://api.deepseek.com/v1"
        );
        // OpenAI-compatible gateway style: already includes /v1.
        assert_eq!(
            normalise_upstream_base("http://127.0.0.1:8787/v1/"),
            "http://127.0.0.1:8787/v1"
        );
        assert_eq!(
            normalise_upstream_base("http://127.0.0.1:8787/v1"),
            "http://127.0.0.1:8787/v1"
        );
    }

    #[test]
    fn an_unset_admin_token_disables_minting() {
        let mut config = Config::for_test("http://localhost", "/tmp/x.json", 7);
        config.admin_token = String::new();

        assert!(!config.is_admin_token(""));
        assert!(!config.is_admin_token("anything"));
    }

    #[test]
    fn only_the_exact_admin_token_matches() {
        let mut config = Config::for_test("http://localhost", "/tmp/x.json", 7);
        config.admin_token = "s3cret-token".to_string();

        assert!(config.is_admin_token("s3cret-token"));
        assert!(!config.is_admin_token("s3cret-toke"));
        assert!(!config.is_admin_token("s3cret-tokenn"));
        assert!(!config.is_admin_token("S3CRET-TOKEN"));
        assert!(!config.is_admin_token(""));
    }
}
