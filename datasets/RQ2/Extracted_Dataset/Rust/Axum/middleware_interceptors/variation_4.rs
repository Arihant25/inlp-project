/*
File: main.rs
Variation: 4 - The Pragmatist
Description: This variation takes a pragmatic, concise approach. It uses built-in Axum adapters like
`map_response` for transformations and a `.fallback()` handler for a layered error strategy.
Configuration, like CORS origins, is read from the environment to simulate a more realistic setup.
*/

// --- Cargo.toml dependencies ---
/*
[dependencies]
axum = { version = "0.7", features = ["json"] }
tokio = { version = "1", features = ["full"] }
tower = "0.4"
tower-http = { version = "0.5", features = ["cors", "trace", "request-id"] }
tower_governor = "0.1"
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1.0"
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter", "json"] }
anyhow = "1.0"
*/

use axum::{
    async_trait,
    extract::{Request, State},
    http::{header, Method, StatusCode, Uri},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::{env, net::SocketAddr, sync::Arc, time::Instant};
use tower_governor::{
    governor::GovernorConfigBuilder, key_extractor::SmartIpKeyExtractor, GovernorLayer,
};
use tower_http::{
    cors::CorsLayer,
    request_id::{MakeRequestUuid, SetRequestIdLayer},
    trace::TraceLayer,
};
use tracing::{error, info, Span};
use uuid::Uuid;

// --- Domain Models ---

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
enum UserRole { ADMIN, USER }

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct User { id: Uuid, email: String, role: UserRole, is_active: bool, created_at: DateTime<Utc> }

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct Post { id: Uuid, user_id: Uuid, title: String, content: String, status: PostStatus }

#[derive(Debug, Deserialize)]
struct CreatePostPayload { title: String, content: String }

// --- Application State ---
// Using state for shared resources is a common pattern.
#[derive(Clone)]
struct AppState {
    // e.g., db_pool: PgPool
}

// --- Error Handling ---
// Using `anyhow::Error` for flexible error handling in handlers.
struct AppError(anyhow::Error);

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        error!("Handler error: {:?}", self.0);
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": "Something went wrong"})),
        )
            .into_response()
    }
}

impl<E> From<E> for AppError
where
    E: Into<anyhow::Error>,
{
    fn from(err: E) -> Self {
        Self(err.into())
    }
}

// --- Middleware ---

// 4. Response Transformation using `map_response`
async fn mw_map_response(response: Response) -> Response {
    let mut res = response;
    res.headers_mut()
        .insert("X-Server-Version", "pragmatic-v1.0".parse().unwrap());
    res
}

// A custom middleware to log request processing time.
async fn mw_track_latency(req: Request, next: axum::middleware::Next) -> Response {
    let start = Instant::now();
    let response = next.run(req).await;
    let latency = start.elapsed();
    info!("Request processed in: {:?}", latency);
    response
}

// --- Handlers ---

async fn get_user(_state: State<Arc<AppState>>) -> Json<User> {
    Json(User {
        id: Uuid::new_v4(),
        email: "pragmatist@example.com".to_string(),
        role: UserRole::USER,
        is_active: true,
        created_at: Utc::now(),
    })
}

async fn create_post(
    _state: State<Arc<AppState>>,
    Json(payload): Json<CreatePostPayload>,
) -> Result<Json<Post>, AppError> {
    if payload.title.trim().is_empty() {
        return Err(AppError(anyhow::anyhow!("Post title cannot be empty")));
    }
    let post = Post {
        id: Uuid::new_v4(),
        user_id: Uuid::new_v4(),
        title: payload.title,
        content: payload.content,
        status: PostStatus::PUBLISHED,
    };
    Ok(Json(post))
}

// Fallback handler for 404 Not Found errors.
async fn fallback_handler(uri: Uri) -> impl IntoResponse {
    (
        StatusCode::NOT_FOUND,
        Json(serde_json::json!({"error": "NotFound", "path": uri.to_string()})),
    )
}

// --- Main Application Setup ---

#[tokio::main]
async fn main() {
    // Setup tracing for structured JSON logging, common in production.
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .json()
        .init();

    // 2. CORS: Read allowed origins from environment for flexibility.
    let allowed_origin = env::var("ALLOWED_ORIGIN").unwrap_or_else(|_| "http://localhost:3000".to_string());
    let cors = CorsLayer::new()
        .allow_origin(allowed_origin.parse::<HeaderValue>().unwrap())
        .allow_methods([Method::GET, Method::POST]);

    // 3. Rate Limiting
    let governor_config = Box::new(
        GovernorConfigBuilder::default()
            .per_second(10)
            .burst_size(25)
            .key_extractor(SmartIpKeyExtractor)
            .finish()
            .unwrap(),
    );

    let app_state = Arc::new(AppState {});

    let app = Router::new()
        .route("/user", get(get_user))
        .route("/post", post(create_post))
        .with_state(app_state)
        .fallback(fallback_handler) // 5. Error Handling: Catch-all for 404s
        .map_response(mw_map_response) // 4. Response Transformation
        .layer(
            tower::ServiceBuilder::new()
                // Order matters: innermost layers are applied first.
                .layer(GovernorLayer { config: Box::leak(governor_config) })
                .layer(cors)
                .layer(axum::middleware::from_fn(mw_track_latency))
                // 1. Request Logging with Request ID
                .layer(
                    TraceLayer::new_for_http().on_request(|_req: &Request, _span: &Span| {
                        info!("Request received");
                    }),
                )
                .layer(SetRequestIdLayer::new(
                    header::HeaderName::from_static("x-request-id"),
                    MakeRequestUuid,
                )),
        );

    let addr = SocketAddr::from(([127, 0, 0, 1], 3004));
    info!("Pragmatic server listening on {}", addr);
    
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}