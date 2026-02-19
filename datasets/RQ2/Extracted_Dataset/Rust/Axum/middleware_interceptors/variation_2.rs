/*
File: main.rs (with modules)
Variation: 2 - The Modular Architect
Description: This variation organizes the application into distinct modules (models, handlers, middleware, errors).
This promotes separation of concerns and is scalable for larger applications. `main.rs` acts as the
composition root, wiring together components from different modules.
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
http-body-util = "0.1.1"
bytes = "1"
*/

// --- main.rs ---
use axum::{routing::get, routing::post, Router};
use std::net::SocketAddr;
use tower::ServiceBuilder;
use tracing::info;

mod models;
mod handlers;
mod errors;
mod middleware;

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_target(false)
        .compact()
        .init();

    let api_routes = Router::new()
        .route("/users/me", get(handlers::fetch_current_user))
        .route("/posts", post(handlers::publish_new_post))
        // This middleware is route-specific for demonstration
        .route_layer(axum::middleware::from_fn(middleware::auth_guard));

    let app = Router::new()
        .nest("/v1", api_routes)
        .layer(
            ServiceBuilder::new()
                .layer(middleware::setup_tracing_layer())
                .layer(middleware::setup_cors_layer())
                .layer(middleware::setup_governor_layer())
                .layer(axum::middleware::from_fn(middleware::json_response_wrapper)),
        );

    let addr = SocketAddr::from(([127, 0, 0, 1], 3002));
    info!("Modular server listening on {}", addr);
    
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}


// --- models.rs ---
pub mod models {
    use chrono::{DateTime, Utc};
    use serde::{Deserialize, Serialize};
    use uuid::Uuid;

    #[derive(Debug, Serialize, Clone)]
    #[serde(rename_all = "camelCase")]
    pub enum UserRole { ADMIN, USER }

    #[derive(Debug, Serialize, Clone)]
    #[serde(rename_all = "camelCase")]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    #[serde(rename_all = "camelCase")]
    pub enum PostStatus { DRAFT, PUBLISHED }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    #[serde(rename_all = "camelCase")]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }
    
    #[derive(Debug, Deserialize)]
    pub struct PublishPostRequest {
        pub title: String,
        pub content: String,
    }
}

// --- errors.rs ---
pub mod errors {
    use axum::{
        http::StatusCode,
        response::{IntoResponse, Response},
        Json,
    };
    use serde_json::json;

    pub enum ApiError {
        Unauthorized,
        BadRequest(String),
        InternalError,
    }

    impl IntoResponse for ApiError {
        fn into_response(self) -> Response {
            let (status, message) = match self {
                ApiError::Unauthorized => (StatusCode::UNAUTHORIZED, "Authentication required".to_string()),
                ApiError::BadRequest(reason) => (StatusCode::BAD_REQUEST, reason),
                ApiError::InternalError => (StatusCode::INTERNAL_SERVER_ERROR, "Internal server error".to_string()),
            };

            let body = Json(json!({
                "status": "error",
                "message": message,
            }));

            (status, body).into_response()
        }
    }
}

// --- middleware.rs ---
pub mod middleware {
    use crate::errors::ApiError;
    use axum::{
        body::Body,
        extract::Request,
        http::{header, Method, StatusCode},
        middleware::Next,
        response::{IntoResponse, Response},
    };
    use bytes::Bytes;
    use http_body_util::BodyExt;
    use tower_governor::{
        governor::GovernorConfigBuilder, key_extractor::SmartIpKeyExtractor, GovernorLayer,
    };
    use tower_http::{cors::CorsLayer, trace::TraceLayer};
    use tracing::info;

    // 1. Request Logging
    pub fn setup_tracing_layer() -> TraceLayer {
        TraceLayer::new_for_http()
    }

    // 2. CORS Handling
    pub fn setup_cors_layer() -> CorsLayer {
        CorsLayer::new()
            .allow_origin("http://localhost:3000".parse::<header::HeaderValue>().unwrap())
            .allow_methods([Method::GET, Method::POST])
            .allow_headers([header::CONTENT_TYPE])
    }

    // 3. Rate Limiting
    pub fn setup_governor_layer() -> GovernorLayer {
        let config = Box::new(
            GovernorConfigBuilder::default()
                .per_minute(5)
                .burst_size(2)
                .finish()
                .unwrap(),
        );
        GovernorLayer { config: Box::leak(config) }
    }

    // 4. Request/Response Transformation
    pub async fn json_response_wrapper(req: Request, next: Next) -> impl IntoResponse {
        let response = next.run(req).await;
        
        if response.status().is_success() {
            let (parts, body) = response.into_parts();
            let bytes = buffer_and_print("response", body).await.unwrap();
            
            let data: serde_json::Value = serde_json::from_slice(&bytes).unwrap_or_default();
            let wrapped_body = serde_json::json!({
                "status": "success",
                "data": data
            });

            return Response::builder()
                .status(parts.status)
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(wrapped_body.to_string()))
                .unwrap();
        }
        
        response
    }
    
    async fn buffer_and_print<B>(direction: &str, body: B) -> Result<Bytes, axum::Error>
    where
        B: axum::body::HttpBody<Data = Bytes>,
        B::Error: std::fmt::Display,
    {
        let bytes = body
            .collect()
            .await
            .map_err(|e| axum::Error::new(e.to_string()))?
            .to_bytes();
        Ok(bytes)
    }

    // Example of a request-level middleware
    pub async fn auth_guard(req: Request, next: Next) -> Result<Response, ApiError> {
        info!("Executing auth guard middleware...");
        let auth_header = req.headers().get(header::AUTHORIZATION);
        match auth_header {
            Some(token) if token.to_str().unwrap_or("").starts_with("Bearer valid-token") => {
                Ok(next.run(req).await)
            }
            _ => Err(ApiError::Unauthorized),
        }
    }
}

// --- handlers.rs ---
pub mod handlers {
    use crate::errors::ApiError;
    use crate::models::{Post, PostStatus, PublishPostRequest, User, UserRole};
    use axum::Json;
    use chrono::Utc;
    use uuid::Uuid;

    pub async fn fetch_current_user() -> Result<Json<User>, ApiError> {
        let current_user = User {
            id: Uuid::new_v4(),
            email: "architect@example.com".to_string(),
            role: UserRole::USER,
            is_active: true,
            created_at: Utc::now(),
        };
        Ok(Json(current_user))
    }

    pub async fn publish_new_post(
        Json(payload): Json<PublishPostRequest>,
    ) -> Result<Json<Post>, ApiError> {
        if payload.title.len() < 3 {
            return Err(ApiError::BadRequest("Title must be at least 3 characters".into()));
        }
        let new_post = Post {
            id: Uuid::new_v4(),
            user_id: Uuid::new_v4(),
            title: payload.title,
            content: payload.content,
            status: PostStatus::PUBLISHED,
        };
        Ok(Json(new_post))
    }
}