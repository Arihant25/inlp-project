/*
File: main.rs
Variation: 1 - The Functional Enthusiast
Description: This variation uses a direct, functional approach. Middleware are applied as simple layers
of async functions. The structure is flat, contained within a single file, prioritizing simplicity and
readability for smaller projects.
*/

// --- Cargo.toml dependencies ---
/*
[dependencies]
axum = { version = "0.7", features = ["json"] }
tokio = { version = "1", features = ["full"] }
tower = "0.4"
tower-http = { version = "0.5", features = ["cors", "trace"] }
tower_governor = "0.1"
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1.0"
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
*/

use axum::{
    async_trait,
    extract::{FromRequest, Request},
    http::{header, Method, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::{net::SocketAddr, time::Duration};
use tower_governor::{
    governor::GovernorConfigBuilder, key_extractor::SmartIpKeyExtractor, GovernorLayer,
};
use tower_http::{cors::CorsLayer, trace::TraceLayer};
use tracing::info;
use uuid::Uuid;

// --- Domain Models ---

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
enum UserRole {
    ADMIN,
    USER,
}

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct User {
    id: Uuid,
    email: String,
    // password_hash is omitted for security
    role: UserRole,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
enum PostStatus {
    DRAFT,
    PUBLISHED,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

#[derive(Debug, Deserialize)]
struct CreatePostPayload {
    title: String,
    content: String,
}

// --- Error Handling ---

enum AppError {
    UserNotFound,
    InvalidInput(String),
    InternalServerError,
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, error_message) = match self {
            AppError::UserNotFound => (StatusCode::NOT_FOUND, "User not found".to_string()),
            AppError::InvalidInput(msg) => (StatusCode::BAD_REQUEST, msg),
            AppError::InternalServerError => (
                StatusCode::INTERNAL_SERVER_ERROR,
                "An internal server error occurred".to_string(),
            ),
        };

        let body = Json(serde_json::json!({
            "error": error_message,
        }));

        (status, body).into_response()
    }
}

// --- Middleware ---

// This middleware demonstrates response transformation by adding a custom header.
async fn add_custom_header_middleware(req: Request, next: axum::middleware::Next) -> Response {
    let mut response = next.run(req).await;
    response
        .headers_mut()
        .insert("X-Powered-By", "Functional-Axum-App".parse().unwrap());
    response
}

// --- Handlers ---

async fn get_user_handler() -> Result<Json<User>, AppError> {
    info!("Fetching user...");
    // Mock user data
    let user = User {
        id: Uuid::new_v4(),
        email: "functional.dev@example.com".to_string(),
        role: UserRole::ADMIN,
        is_active: true,
        created_at: Utc::now(),
    };
    Ok(Json(user))
}

async fn create_post_handler(
    Json(payload): Json<CreatePostPayload>,
) -> Result<Json<Post>, AppError> {
    info!("Creating post with title: {}", payload.title);
    if payload.title.is_empty() {
        return Err(AppError::InvalidInput("Title cannot be empty".to_string()));
    }

    let new_post = Post {
        id: Uuid::new_v4(),
        user_id: Uuid::new_v4(), // Mock user ID
        title: payload.title,
        content: payload.content,
        status: PostStatus::DRAFT,
    };
    Ok(Json(new_post))
}

// --- Main Application Setup ---

#[tokio::main]
async fn main() {
    // Initialize logging
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::new(
            "info,tower_http=debug",
        ))
        .init();

    // Configure CORS
    let cors_layer = CorsLayer::new()
        .allow_methods([Method::GET, Method::POST])
        .allow_origin(tower_http::cors::Any);

    // Configure Rate Limiting: 10 requests per 30 seconds
    let governor_config = Box::new(
        GovernorConfigBuilder::default()
            .per_second(30)
            .burst_size(10)
            .key_extractor(SmartIpKeyExtractor)
            .finish()
            .unwrap(),
    );

    // Define application routes
    let app_routes = Router::new()
        .route("/user", get(get_user_handler))
        .route("/post", post(create_post_handler));

    // Combine routes with all middleware layers
    let app = Router::new().nest("/api", app_routes).layer(
        tower::ServiceBuilder::new()
            // 1. Request Logging
            .layer(TraceLayer::new_for_http())
            // 2. CORS Handling
            .layer(cors_layer)
            // 3. Rate Limiting
            .layer(GovernorLayer {
                config: Box::leak(governor_config),
            })
            // 4. Request/Response Transformation (custom header)
            .layer(axum::middleware::from_fn(add_custom_header_middleware)),
            // 5. Error Handling is handled by `AppError::IntoResponse`
    );

    let addr = SocketAddr::from(([127, 0, 0, 1], 3001));
    info!("Functional server listening on {}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}