//! End-to-end tests over real HTTP.
//!
//! A stub upstream stands in for DeepSeek so the whole path is exercised --
//! router, auth extractor, subscription gate, prompt injection and the streaming
//! proxy -- without a network or an API key.

use std::net::SocketAddr;

use serde_json::{json, Value};

/// A client that ignores any ambient HTTP_PROXY.
///
/// The developer machine has a system-wide proxy, and without this even requests
/// to 127.0.0.1 are handed to it, which produces sporadic 502s that look exactly
/// like a bug in the service.
fn test_client() -> reqwest::Client {
    reqwest::Client::builder()
        .no_proxy()
        .build()
        .expect("failed to build test client")
}

/// Starts the stub provider. Returns its base URL and the recorded request body
/// of the last call, so a test can assert on what we forwarded.
async fn start_stub_upstream() -> (String, std::sync::Arc<tokio::sync::Mutex<Option<Value>>>) {
    use axum::routing::post;
    use axum::{Json, Router};

    let recorded = std::sync::Arc::new(tokio::sync::Mutex::new(None::<Value>));
    let sink = recorded.clone();

    let app = Router::new().route(
        "/v1/chat/completions",
        post(move |Json(body): Json<Value>| {
            let sink = sink.clone();
            async move {
                *sink.lock().await = Some(body);
                Json(json!({
                    "id": "chatcmpl-stub",
                    "object": "chat.completion",
                    "choices": [{
                        "index": 0,
                        "message": { "role": "assistant", "content": "这是一个付款页面。" },
                        "finish_reason": "stop"
                    }]
                }))
            }
        }),
    );

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr: SocketAddr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });

    (format!("http://{addr}"), recorded)
}

/// Boots the real server with its own data file and a stub upstream.
///
/// `trial_days` is passed explicitly rather than through the environment: these
/// tests run in parallel in one process, and `set_var` would make them race.
async fn start_server_with_trial(
    test_name: &str,
    trial_days: i64,
) -> (String, std::sync::Arc<tokio::sync::Mutex<Option<Value>>>) {
    let (upstream_url, recorded) = start_stub_upstream().await;

    let data_file = std::env::temp_dir().join(format!("evercare-api-test-{test_name}.json"));
    let _ = std::fs::remove_file(&data_file);

    let config = evercare_server::config::Config::for_test(
        &upstream_url,
        data_file.to_str().unwrap(),
        trial_days,
    );
    let app = evercare_server::build_app(config).await.unwrap();

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr: SocketAddr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });

    (format!("http://{addr}"), recorded)
}

/// The common case: a handset on a seven-day trial.
async fn start_server(
    test_name: &str,
) -> (String, std::sync::Arc<tokio::sync::Mutex<Option<Value>>>) {
    start_server_with_trial(test_name, 7).await
}

/// Mints one code worth `months` through the admin API.
async fn mint_code(client: &reqwest::Client, base: &str, months: i64) -> String {
    let response = client
        .post(format!("{base}/v1/evercare/admin/codes"))
        .bearer_auth("test-admin-token")
        .json(&json!({ "months": months, "count": 1, "note": "test" }))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);

    let body: Value = response.json().await.unwrap();
    body["codes"][0].as_str().unwrap().to_string()
}

async fn register(client: &reqwest::Client, base: &str) -> (String, String) {
    let response = client
        .post(format!("{base}/v1/evercare/devices"))
        .json(&json!({ "label": "奶奶的手机", "platform": "android" }))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);

    let body: Value = response.json().await.unwrap();
    (
        body["device_id"].as_str().unwrap().to_string(),
        body["token"].as_str().unwrap().to_string(),
    )
}

#[tokio::test]
async fn health_needs_no_authentication() {
    let (base, _) = start_server("health").await;
    let response = test_client().get(format!("{base}/healthz")).send().await.unwrap();

    assert_eq!(response.status(), 200);
    assert_eq!(response.json::<Value>().await.unwrap()["status"], "ok");
}

#[tokio::test]
async fn a_new_handset_can_chat_immediately_on_its_trial() {
    let (base, recorded) = start_server("trial-chat").await;
    let client = test_client();
    let (_, token) = register(&client, &base).await;

    let response = client
        .post(format!("{base}/v1/chat/completions"))
        .bearer_auth(&token)
        .json(&json!({
            "messages": [{ "role": "user", "content": "这是什么？" }],
            "language": "zh-CN",
            "stream": false
        }))
        .send()
        .await
        .unwrap();

    assert_eq!(response.status(), 200);

    // The server must prepend its own Chinese system prompt, and must not pass
    // the client's model choice through.
    let forwarded = recorded.lock().await.clone().unwrap();
    let messages = forwarded["messages"].as_array().unwrap();
    assert_eq!(messages[0]["role"], "system");
    assert!(
        messages[0]["content"].as_str().unwrap().contains("长伴"),
        "expected the Chinese system prompt, got: {}",
        messages[0]["content"]
    );
    assert_eq!(forwarded["model"], "stub-model");
}

#[tokio::test]
async fn the_system_prompt_follows_the_requested_language() {
    let (base, recorded) = start_server("language").await;
    let client = test_client();
    let (_, token) = register(&client, &base).await;

    client
        .post(format!("{base}/v1/chat/completions"))
        .bearer_auth(&token)
        .json(&json!({
            "messages": [{ "role": "user", "content": "What is this?" }],
            "language": "en-GB"
        }))
        .send()
        .await
        .unwrap();

    let forwarded = recorded.lock().await.clone().unwrap();
    let system = forwarded["messages"][0]["content"].as_str().unwrap();
    assert!(system.contains("EverCare"), "expected the English prompt");
}

#[tokio::test]
async fn chat_without_a_token_is_rejected() {
    let (base, _) = start_server("no-token").await;

    let response = test_client()
        .post(format!("{base}/v1/chat/completions"))
        .json(&json!({ "messages": [{ "role": "user", "content": "hello" }] }))
        .send()
        .await
        .unwrap();

    assert_eq!(response.status(), 401);
}

#[tokio::test]
async fn chat_with_a_forged_token_is_rejected() {
    let (base, _) = start_server("forged").await;
    let client = test_client();
    let (device_id, _) = register(&client, &base).await;

    let response = client
        .post(format!("{base}/v1/chat/completions"))
        .bearer_auth(format!("dev_{device_id}.not-the-real-secret"))
        .json(&json!({ "messages": [{ "role": "user", "content": "hello" }] }))
        .send()
        .await
        .unwrap();

    assert_eq!(response.status(), 401);
}

#[tokio::test]
async fn an_expired_device_is_told_to_pay_rather_than_given_an_error() {
    // Trial of zero days: entitled to nothing from the moment it registers.
    let (base, _) = start_server_with_trial("expired", 0).await;
    let client = test_client();
    let (_, token) = register(&client, &base).await;

    let response = client
        .post(format!("{base}/v1/chat/completions"))
        .bearer_auth(&token)
        .json(&json!({ "messages": [{ "role": "user", "content": "hello" }] }))
        .send()
        .await
        .unwrap();

    // 402, so the app can say "your plan has ended" instead of a scary error.
    assert_eq!(response.status(), 402);
    let body: Value = response.json().await.unwrap();
    assert_eq!(body["error"]["type"], "subscription_required");
}

#[tokio::test]
async fn redeeming_restores_access() {
    let (base, _) = start_server_with_trial("redeem", 0).await;
    let client = test_client();
    let (_, token) = register(&client, &base).await;

    let blocked = client
        .post(format!("{base}/v1/chat/completions"))
        .bearer_auth(&token)
        .json(&json!({ "messages": [{ "role": "user", "content": "hi" }] }))
        .send()
        .await
        .unwrap();
    assert_eq!(blocked.status(), 402);

    let code = mint_code(&client, &base, 1).await;
    let redeemed = client
        .post(format!("{base}/v1/evercare/subscription/redeem"))
        .bearer_auth(&token)
        .json(&json!({ "code": code }))
        .send()
        .await
        .unwrap();
    assert_eq!(redeemed.status(), 200);
    let status: Value = redeemed.json().await.unwrap();
    assert_eq!(status["active"], true);
    assert_eq!(status["plan"], "monthly");

    let allowed = client
        .post(format!("{base}/v1/chat/completions"))
        .bearer_auth(&token)
        .json(&json!({ "messages": [{ "role": "user", "content": "hi" }] }))
        .send()
        .await
        .unwrap();
    assert_eq!(allowed.status(), 200);
}

#[tokio::test]
async fn explaining_a_screen_wraps_the_text_in_a_localized_instruction() {
    let (base, recorded) = start_server("explain").await;
    let client = test_client();
    let (_, token) = register(&client, &base).await;

    let response = client
        .post(format!("{base}/v1/evercare/explain"))
        .bearer_auth(&token)
        .json(&json!({
            "screen_text": "确认支付 ￥88.00",
            "language": "zh-CN"
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);

    let forwarded = recorded.lock().await.clone().unwrap();
    let user_message = forwarded["messages"][1]["content"].as_str().unwrap();
    assert!(user_message.contains("确认支付 ￥88.00"), "screen text must be forwarded");
    assert!(user_message.contains("最简单的话"), "expected the Chinese instruction");
}

#[tokio::test]
async fn explaining_an_empty_screen_is_a_bad_request() {
    let (base, _) = start_server("explain-empty").await;
    let client = test_client();
    let (_, token) = register(&client, &base).await;

    let response = client
        .post(format!("{base}/v1/evercare/explain"))
        .bearer_auth(&token)
        .json(&json!({ "screen_text": "   ", "language": "zh-CN" }))
        .send()
        .await
        .unwrap();

    assert_eq!(response.status(), 400);
}

#[tokio::test]
async fn a_subscription_moves_to_a_new_handset_and_leaves_the_old_one_empty() {
    let (base, _) = start_server_with_trial("transfer", 0).await;
    let client = test_client();

    let (_, old_token) = register(&client, &base).await;
    let code = mint_code(&client, &base, 2).await;
    client
        .post(format!("{base}/v1/evercare/subscription/redeem"))
        .bearer_auth(&old_token)
        .json(&json!({ "code": code }))
        .send()
        .await
        .unwrap();

    let (_, new_token) = register(&client, &base).await;
    let transfer = client
        .post(format!("{base}/v1/evercare/subscription/transfer"))
        .bearer_auth(&new_token)
        .json(&json!({ "from_token": old_token }))
        .send()
        .await
        .unwrap();
    assert_eq!(transfer.status(), 200);
    assert_eq!(transfer.json::<Value>().await.unwrap()["active"], true);

    // The old handset must not keep a usable copy of the plan.
    let old_status = client
        .get(format!("{base}/v1/evercare/subscription"))
        .bearer_auth(&old_token)
        .send()
        .await
        .unwrap();
    assert_eq!(old_status.json::<Value>().await.unwrap()["active"], false);
}

#[tokio::test]
async fn subscription_status_reports_the_trial() {
    let (base, _) = start_server("status").await;
    let client = test_client();
    let (device_id, token) = register(&client, &base).await;

    let status: Value = client
        .get(format!("{base}/v1/evercare/subscription"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    assert_eq!(status["device_id"], device_id);
    assert_eq!(status["active"], true);
    assert_eq!(status["plan"], "trial");
    assert_eq!(status["days_remaining"], 6);
}

#[tokio::test]
async fn an_unknown_redeem_code_is_refused() {
    // Before the allowlist existed, any non-empty string granted a month, which
    // on a public deployment means anyone can spend the model budget.
    let (base, _) = start_server_with_trial("redeem-bad", 0).await;
    let client = test_client();
    let (_, token) = register(&client, &base).await;

    for code in ["", "   ", "guess-me", "receipt-123"] {
        let response = client
            .post(format!("{base}/v1/evercare/subscription/redeem"))
            .bearer_auth(&token)
            .json(&json!({ "code": code }))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 400, "code {code:?} should be refused");
    }

    // Still blocked from chatting.
    let blocked = client
        .post(format!("{base}/v1/chat/completions"))
        .bearer_auth(&token)
        .json(&json!({ "messages": [{ "role": "user", "content": "hi" }] }))
        .send()
        .await
        .unwrap();
    assert_eq!(blocked.status(), 402);
}

#[tokio::test]
async fn the_daily_quota_returns_429_once_it_is_used_up() {
    let (upstream_url, _) = start_stub_upstream().await;
    let data_file = std::env::temp_dir().join("evercare-api-test-quota.json");
    let _ = std::fs::remove_file(&data_file);

    let mut config = evercare_server::config::Config::for_test(
        &upstream_url,
        data_file.to_str().unwrap(),
        7,
    );
    config.daily_request_limit = 2;

    let app = evercare_server::build_app(config).await.unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr: SocketAddr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let base = format!("http://{addr}");

    let client = test_client();
    let (_, token) = register(&client, &base).await;

    let ask = |token: String, base: String| async move {
        test_client()
            .post(format!("{base}/v1/chat/completions"))
            .bearer_auth(token)
            .json(&json!({ "messages": [{ "role": "user", "content": "hi" }] }))
            .send()
            .await
            .unwrap()
            .status()
            .as_u16()
    };

    assert_eq!(ask(token.clone(), base.clone()).await, 200);
    assert_eq!(ask(token.clone(), base.clone()).await, 200);

    let third = test_client()
        .post(format!("{base}/v1/chat/completions"))
        .bearer_auth(&token)
        .json(&json!({ "messages": [{ "role": "user", "content": "hi" }] }))
        .send()
        .await
        .unwrap();
    assert_eq!(third.status(), 429);
    let body: Value = third.json().await.unwrap();
    assert_eq!(body["error"]["type"], "rate_limit_exceeded");
}

#[tokio::test]
async fn max_tokens_is_clamped_whatever_the_client_asks_for() {
    let (base, recorded) = start_server("clamp").await;
    let client = test_client();
    let (_, token) = register(&client, &base).await;

    client
        .post(format!("{base}/v1/chat/completions"))
        .bearer_auth(&token)
        .json(&json!({
            "messages": [{ "role": "user", "content": "hi" }],
            "max_tokens": 1000000
        }))
        .send()
        .await
        .unwrap();

    let forwarded = recorded.lock().await.clone().unwrap();
    assert_eq!(
        forwarded["max_tokens"], 800,
        "a client must not be able to order an arbitrarily expensive completion"
    );
}

#[tokio::test]
async fn an_oversized_screen_capture_is_truncated_not_rejected() {
    let (base, recorded) = start_server("truncate").await;
    let client = test_client();
    let (_, token) = register(&client, &base).await;

    // Far past the 6000-character cap.
    let huge = "付款".repeat(20000);
    let response = client
        .post(format!("{base}/v1/evercare/explain"))
        .bearer_auth(&token)
        .json(&json!({ "screen_text": huge, "language": "zh-CN" }))
        .send()
        .await
        .unwrap();

    // The user still gets an answer about the top of the screen.
    assert_eq!(response.status(), 200);

    let forwarded = recorded.lock().await.clone().unwrap();
    let sent = forwarded["messages"][1]["content"].as_str().unwrap();
    assert!(
        sent.chars().count() < 7000,
        "screen text should have been truncated, got {} chars",
        sent.chars().count()
    );
}

#[tokio::test]
async fn minting_requires_the_admin_token() {
    let (base, _) = start_server("admin-auth").await;
    let client = test_client();

    // No token at all.
    let anonymous = client
        .post(format!("{base}/v1/evercare/admin/codes"))
        .json(&json!({ "months": 1, "count": 1 }))
        .send()
        .await
        .unwrap();
    assert_eq!(anonymous.status(), 401);

    // A device token is not an admin token: minting is creating money.
    let (_, device_token) = register(&client, &base).await;
    let as_device = client
        .post(format!("{base}/v1/evercare/admin/codes"))
        .bearer_auth(&device_token)
        .json(&json!({ "months": 1, "count": 1 }))
        .send()
        .await
        .unwrap();
    assert_eq!(as_device.status(), 401);
}

#[tokio::test]
async fn a_code_is_worth_what_it_was_minted_for_whatever_the_client_asks() {
    let (base, _) = start_server_with_trial("code-months", 0).await;
    let client = test_client();
    let (_, token) = register(&client, &base).await;

    let code = mint_code(&client, &base, 1).await;

    // The request also carries months=36; it must be ignored entirely.
    let redeemed = client
        .post(format!("{base}/v1/evercare/subscription/redeem"))
        .bearer_auth(&token)
        .json(&json!({ "code": code, "months": 36 }))
        .send()
        .await
        .unwrap();
    assert_eq!(redeemed.status(), 200);

    let status: Value = redeemed.json().await.unwrap();
    assert_eq!(
        status["days_remaining"], 29,
        "a one-month code must grant one month, not what the client asked for"
    );
}

#[tokio::test]
async fn a_code_works_once_and_then_never_again() {
    let (base, _) = start_server_with_trial("code-once-http", 0).await;
    let client = test_client();
    let (_, first) = register(&client, &base).await;
    let (_, second) = register(&client, &base).await;

    let code = mint_code(&client, &base, 1).await;

    let ok = client
        .post(format!("{base}/v1/evercare/subscription/redeem"))
        .bearer_auth(&first)
        .json(&json!({ "code": &code }))
        .send()
        .await
        .unwrap();
    assert_eq!(ok.status(), 200);

    let reused = client
        .post(format!("{base}/v1/evercare/subscription/redeem"))
        .bearer_auth(&second)
        .json(&json!({ "code": &code }))
        .send()
        .await
        .unwrap();
    assert_eq!(reused.status(), 400);

    // The second handset stays locked out.
    let blocked = client
        .post(format!("{base}/v1/chat/completions"))
        .bearer_auth(&second)
        .json(&json!({ "messages": [{ "role": "user", "content": "hi" }] }))
        .send()
        .await
        .unwrap();
    assert_eq!(blocked.status(), 402);
}

#[tokio::test]
async fn minted_codes_are_128_hex_characters() {
    let (base, _) = start_server("code-shape").await;
    let client = test_client();

    let response = client
        .post(format!("{base}/v1/evercare/admin/codes"))
        .bearer_auth("test-admin-token")
        .json(&json!({ "months": 12, "count": 5, "note": "batch" }))
        .send()
        .await
        .unwrap();
    let body: Value = response.json().await.unwrap();

    let codes = body["codes"].as_array().unwrap();
    assert_eq!(codes.len(), 5);
    for code in codes {
        let code = code.as_str().unwrap();
        assert_eq!(code.len(), 128, "codes must be long enough not to guess");
        assert!(code.chars().all(|c| c.is_ascii_hexdigit()));
    }

    // No two codes in a batch may collide.
    let unique: std::collections::HashSet<&str> =
        codes.iter().map(|c| c.as_str().unwrap()).collect();
    assert_eq!(unique.len(), 5);
}
