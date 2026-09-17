use evercare_server::build_app;
use evercare_server::config::Config;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::registry()
        .with(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .with(tracing_subscriber::fmt::layer())
        .init();

    let config = Config::from_env();

    if config.upstream_api_key.is_empty() {
        // Not fatal: /healthz and device registration still work, which is enough
        // to bring the service up in a container before secrets are injected.
        tracing::warn!(
            "EVERCARE_UPSTREAM_API_KEY is not set -- chat requests will fail until it is"
        );
    }

    let bind = config.bind.clone();
    let upstream = config.upstream_base_url.clone();
    let model = config.upstream_model.clone();

    let app = build_app(config).await?;

    let listener = tokio::net::TcpListener::bind(&bind).await?;
    tracing::info!(address = %bind, %upstream, %model, "evercare-server listening");

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;

    Ok(())
}

async fn shutdown_signal() {
    match tokio::signal::ctrl_c().await {
        Ok(()) => tracing::info!("shutting down"),
        Err(e) => tracing::error!("cannot listen for shutdown signal: {e}"),
    }
}
