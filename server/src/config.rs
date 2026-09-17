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
}

impl Config {
    pub fn from_env() -> Self {
        Self {
            bind: var("EVERCARE_BIND", "0.0.0.0:8080"),
            upstream_base_url: var("EVERCARE_UPSTREAM_BASE_URL", "https://api.deepseek.com")
                .trim_end_matches('/')
                .to_string(),
            upstream_api_key: var("EVERCARE_UPSTREAM_API_KEY", ""),
            upstream_model: var("EVERCARE_UPSTREAM_MODEL", "deepseek-chat"),
            data_file: var("EVERCARE_DATA_FILE", "./evercare-data.json"),
            trial_days: var("EVERCARE_TRIAL_DAYS", "7").parse().unwrap_or(7),
        }
    }
}

impl Config {
    /// Explicit constructor used by tests, so they never have to mutate the
    /// process environment (which would race across parallel test threads).
    pub fn for_test(upstream_base_url: &str, data_file: &str, trial_days: i64) -> Self {
        Self {
            bind: "127.0.0.1:0".to_string(),
            upstream_base_url: upstream_base_url.trim_end_matches('/').to_string(),
            upstream_api_key: "sk-stub".to_string(),
            upstream_model: "stub-model".to_string(),
            data_file: data_file.to_string(),
            trial_days,
        }
    }
}

fn var(key: &str, default: &str) -> String {
    env::var(key).unwrap_or_else(|_| default.to_string())
}
