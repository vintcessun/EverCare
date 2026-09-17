//! End-to-end tests over real HTTP.
//!
//! A stub upstream stands in for DeepSeek so the whole path is exercised --
//! router, auth extractor, subscription gate, prompt injection and the streaming
//! proxy -- without a network or an API key.

use std::net::SocketAddr;

use serde_json::{json, Value};

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
    let response = reqwest::get(format!("{base}/healthz")).await.unwrap();

    assert_eq!(response.status(), 200);
    assert_eq!(response.json::<Value>().await.unwrap()["status"], "ok");
}

#[tokio::test]
async fn a_new_handset_can_chat_immediately_on_its_trial() {
    let (base, recorded) = start_server("trial-chat").await;
    let client = reqwest::Client::new();
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
    let client = reqwest::Client::new();
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

    let response = reqwest::Client::new()
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
    let client = reqwest::Client::new();
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
    let client = reqwest::Client::new();
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
    let client = reqwest::Client::new();
    let (_, token) = register(&client, &base).await;

    let blocked = client
        .post(format!("{base}/v1/chat/completions"))
        .bearer_auth(&token)
        .json(&json!({ "messages": [{ "role": "user", "content": "hi" }] }))
        .send()
        .await
        .unwrap();
    assert_eq!(blocked.status(), 402);

    let redeemed = client
        .post(format!("{base}/v1/evercare/subscription/redeem"))
        .bearer_auth(&token)
        .json(&json!({ "code": "receipt-123", "months": 1 }))
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
    let client = reqwest::Client::new();
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
    let client = reqwest::Client::new();
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
    let client = reqwest::Client::new();

    let (_, old_token) = register(&client, &base).await;
    client
        .post(format!("{base}/v1/evercare/subscription/redeem"))
        .bearer_auth(&old_token)
        .json(&json!({ "code": "receipt-123", "months": 2 }))
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
    let client = reqwest::Client::new();
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
