// Variation 4: The "Async & Modern" Developer
// Style: Emphasizes `async/.await`, uses modern crates and declarative macros.
// Cache: Uses the `cached` crate with proc-macros for low-boilerplate caching.
// State: A `SharedState` struct is shared via `Arc`.
// Error Handling: Uses `thiserror` for structured, descriptive error enums.

// --- Cargo.toml dependencies ---
// [dependencies]
// axum = "0.7"
// tokio = { version = "1", features = ["full"] }
// uuid = { version = "1", features = ["v4", "serde"] }
// serde = { version = "1", features = ["derive"] }
// serde_json = "1.0"
// chrono = { version = "0.4", features = ["serde"] }
// cached = { version = "0.49", features = ["proc_macro"] }
// thiserror = "1.0"
// lazy_static = "1.4"
// tracing = "0.1"
// tracing-subscriber = { version = "0.3", features = ["env-filter"] }

use axum::{
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, patch},
    Json, Router,
};
use cached::proc_macro::cached;
use cached::{Cached, SizedCache};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use uuid::Uuid;

// --- 1. Domain Models ---
mod models {
    use super::*;
    #[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
    pub enum UserRole { ADMIN, USER }
    #[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        #[serde(skip_serializing)]
        pub password_hash: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }
    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub enum PostStatus { DRAFT, PUBLISHED }
    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }
    #[derive(Deserialize)]
    pub struct UpdateUserPayload {
        pub email: Option<String>,
    }
}
use models::{UpdateUserPayload, User, UserRole};

// --- 2. Custom Errors ---
mod errors {
    use super::*;
    use thiserror::Error;

    #[derive(Debug, Error)]
    pub enum AppError {
        #[error("User with ID {0} not found")]
        UserNotFound(Uuid),
        #[error("Internal server error")]
        Internal(#[from] anyhow::Error),
    }

    impl IntoResponse for AppError {
        fn into_response(self) -> Response {
            let (status, message) = match self {
                AppError::UserNotFound(id) => (StatusCode::NOT_FOUND, format!("User {} not found", id)),
                AppError::Internal(_) => (StatusCode::INTERNAL_SERVER_ERROR, "Internal server error".to_string()),
            };
            (status, message).into_response()
        }
    }
}
use errors::AppError;

// --- 3. Data Layer with Declarative Caching ---
// The `cached` macro will generate a static, thread-safe cache instance.
// Name: `USER_CACHE`, Type: `SizedCache` (LRU), Key: `Uuid`, Value: `Result<User, AppError>`
// Size: 100, TTL: 300 seconds.
// `result = true` caches only `Ok` variants.
#[cached(
    name = "USER_CACHE",
    type = "SizedCache<Uuid, Result<User, AppError>>",
    create = "{ SizedCache::with_size(100) }",
    convert = r#"{ user_id }"#,
    result = true,
    time = 300
)]
async fn fetch_user_from_db(
    db: Arc<RwLock<HashMap<Uuid, User>>>,
    user_id: Uuid,
) -> Result<User, AppError> {
    tracing::info!("DB QUERY for user_id: {}", user_id);
    // Simulate DB latency
    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    let db_guard = db.read().await;
    db_guard
        .get(&user_id)
        .cloned()
        .ok_or_else(|| AppError::UserNotFound(user_id))
}

// --- 4. Application State ---
#[derive(Clone)]
struct AppState {
    db: Arc<RwLock<HashMap<Uuid, User>>>,
}

impl AppState {
    fn new() -> Self {
        let mut db_data = HashMap::new();
        let user_id = Uuid::new_v4();
        db_data.insert(
            user_id,
            User {
                id: user_id,
                email: "modern@example.com".to_string(),
                password_hash: "hashed_password_4".to_string(),
                role: UserRole::ADMIN,
                is_active: true,
                created_at: Utc::now(),
            },
        );
        Self {
            db: Arc::new(RwLock::new(db_data)),
        }
    }
}

// --- 5. API Routes and Handlers ---
mod routes {
    use super::*;

    /// Gets a user, leveraging the declarative cache.
    /// The cache-aside logic is handled by the `#[cached]` macro on `fetch_user_from_db`.
    pub async fn get_user(
        State(state): State<Arc<AppState>>,
        Path(user_id): Path<Uuid>,
    ) -> Result<Json<User>, AppError> {
        // This function call is now transparently cached.
        let user_result = fetch_user_from_db(state.db.clone(), user_id).await;
        match user_result {
            Ok(user) => Ok(Json(user)),
            Err(e) => Err(e),
        }
    }

    /// Updates a user and invalidates the cache.
    pub async fn update_user(
        State(state): State<Arc<AppState>>,
        Path(user_id): Path<Uuid>,
        Json(payload): Json<UpdateUserPayload>,
    ) -> Result<Json<User>, AppError> {
        // 1. Update the database directly
        let mut db_guard = state.db.write().await;
        let user = db_guard
            .get_mut(&user_id)
            .ok_or_else(|| AppError::UserNotFound(user_id))?;
        
        if let Some(email) = payload.email {
            user.email = email;
        }
        let updated_user = user.clone();
        drop(db_guard);

        // 2. Invalidate the cache entry
        // We need to lock the global cache instance generated by the macro.
        let mut cache = USER_CACHE.lock().await;
        cache.cache_remove(&user_id);
        tracing::info!("CACHE INVALIDATED for user_id: {}", user_id);

        Ok(Json(updated_user))
    }
}

// --- 6. Main Application Setup ---
#[tokio::main]
async fn main() {
    tracing_subscriber::fmt().with_env_filter("info").init();

    let shared_state = Arc::new(AppState::new());

    let app = Router::new()
        .route("/users/:id", get(routes::get_user))
        .route("/users/:id", patch(routes::update_user))
        .with_state(shared_state);

    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
    tracing::info!("listening on {}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.unwrap();
}