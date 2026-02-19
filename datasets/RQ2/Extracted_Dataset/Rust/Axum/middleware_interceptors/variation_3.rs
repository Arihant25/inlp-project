/*
File: main.rs
Variation: 3 - The Type-Driven Designer
Description: This variation emphasizes Rust's type system. Middleware are implemented as structs that
implement the `tower::Layer` trait. This approach is more verbose but offers greater flexibility for
stateful middleware and compile-time guarantees. It's well-suited for complex, reusable middleware components.
*/

// --- Cargo.toml dependencies ---
/*
[dependencies]
axum = { version = "0.7", features = ["json"] }
tokio = { version = "1", features = ["full"] }
tower = { version = "0.4", features = ["full"] }
tower-http = { version = "0.5", features = ["cors", "trace"] }
tower_governor = "0.1"
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1.0"
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
futures-util = "0.3"
http-body-util = "0.1.1"
bytes = "1"
*/

use axum::{
    body::Body,
    extract::{FromRequest, Request},
    http::{header, HeaderValue, Method, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use bytes::Bytes;
use chrono::{DateTime, Utc};
use futures_util::future::BoxFuture;
use http_body_util::BodyExt;
use serde::{Deserialize, Serialize};
use std::{
    net::SocketAddr,
    sync::{Arc, Mutex},
    task::{Context, Poll},
};
use tower::{Layer, Service};
use tower_governor::{
    governor::GovernorConfigBuilder, key_extractor::KeyExtractor, GovernorLayer,
};
use tower_http::{
    cors::CorsLayer,
    trace::{DefaultMakeSpan, TraceLayer},
};
use tracing::info;
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

// --- Error Handling ---

#[derive(Debug)]
enum ServerError {
    DatabaseError(String),
    ValidationFailed(String),
}

impl IntoResponse for ServerError {
    fn into_response(self) -> Response {
        let (status, message) = match self {
            ServerError::DatabaseError(e) => (StatusCode::INTERNAL_SERVER_ERROR, format!("DB Error: {}", e)),
            ServerError::ValidationFailed(e) => (StatusCode::UNPROCESSABLE_ENTITY, format!("Validation: {}", e)),
        };
        (status, Json(serde_json::json!({ "error": message }))).into_response()
    }
}

// --- Middleware Implementation (Type-Driven) ---

// 4. Request/Response Transformation as a Layer
#[derive(Clone)]
struct JsonApiResponseLayer;

impl<S> Layer<S> for JsonApiResponseLayer {
    type Service = JsonApiResponseService<S>;
    fn layer(&self, inner: S) -> Self::Service {
        JsonApiResponseService { inner }
    }
}

#[derive(Clone)]
struct JsonApiResponseService<S> {
    inner: S,
}

impl<S> Service<Request> for JsonApiResponseService<S>
where
    S: Service<Request, Response = Response> + Send + 'static,
    S::Future: Send + 'static,
{
    type Response = Response;
    type Error = S::Error;
    type Future = BoxFuture<'static, Result<Self::Response, Self::Error>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, request: Request) -> Self::Future {
        let future = self.inner.call(request);
        Box::pin(async move {
            let mut response: Response = future.await?;
            if response.status().is_success() {
                let (parts, body) = response.into_parts();
                let bytes = body.collect().await.unwrap().to_bytes();
                let data: serde_json::Value = serde_json::from_slice(&bytes).unwrap_or_default();
                let wrapped_body = serde_json::json!({ "data": data });
                let new_body = Body::from(wrapped_body.to_string());
                response = Response::from_parts(parts, new_body);
                response.headers_mut().insert(header::CONTENT_TYPE, HeaderValue::from_static("application/json"));
            }
            Ok(response)
        })
    }
}

// 3. Custom Key Extractor for Rate Limiting
#[derive(Clone)]
struct ApiKeyExtractor;

impl KeyExtractor for ApiKeyExtractor {
    type Key = String;
    fn extract<B>(&self, req: &http::Request<B>) -> Result<Self::Key, tower_governor::GovernorError> {
        req.headers()
            .get("X-API-KEY")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string())
            .ok_or_else(|| tower_governor::GovernorError::MissingKey)
    }
}

// --- Handlers ---

async fn get_current_user() -> Result<Json<User>, ServerError> {
    Ok(Json(User {
        id: Uuid::new_v4(),
        email: "typed.dev@example.com".to_string(),
        role: UserRole::ADMIN,
        is_active: true,
        created_at: Utc::now(),
    }))
}

async fn create_new_post(Json(payload): Json<CreatePostPayload>) -> Result<Json<Post>, ServerError> {
    if payload.title.is_empty() {
        return Err(ServerError::ValidationFailed("Title cannot be empty".to_string()));
    }
    Ok(Json(Post {
        id: Uuid::new_v4(),
        user_id: Uuid::new_v4(),
        title: payload.title,
        content: payload.content,
        status: PostStatus::DRAFT,
    }))
}

// --- Main Application Setup ---

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt().with_max_level(tracing::Level::DEBUG).init();

    // 1. Logging Layer
    let trace_layer = TraceLayer::new_for_http().make_span_with(DefaultMakeSpan::new().include_headers(true));

    // 2. CORS Layer
    let cors_layer = CorsLayer::new()
        .allow_methods([Method::GET, Method::POST])
        .allow_headers([header::CONTENT_TYPE, header::HeaderName::from_static("x-api-key")])
        .allow_origin(tower_http::cors::Any);

    // 3. Rate Limiting Layer (with custom key extractor)
    let governor_config = Box::new(
        GovernorConfigBuilder::default()
            .key_extractor(ApiKeyExtractor)
            .per_second(1)
            .burst_size(5)
            .finish()
            .unwrap(),
    );
    let governor_layer = GovernorLayer { config: Box::leak(governor_config) };

    let app = Router::new()
        .route("/user", get(get_current_user))
        .route("/post", post(create_new_post))
        .layer(
            tower::ServiceBuilder::new()
                .layer(trace_layer)
                .layer(cors_layer)
                .layer(governor_layer)
                .layer(JsonApiResponseLayer), // 4. Our custom transformation layer
        );

    let addr = SocketAddr::from(([127, 0, 0, 1], 3003));
    info!("Type-driven server listening on {}", addr);
    
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}