// Variation 1: The "Functional & Modular" Developer
// Style: Prefers functions over structs, organizes code into modules.
// Cache: Uses `moka`, a high-performance concurrent cache library.
// State: Uses Axum's `State` extractor to share services.
// Error Handling: Custom `AppError` type with `IntoResponse`.

// --- Cargo.toml dependencies ---
// [dependencies]
// axum = "0.7"
// tokio = { version = "1", features = ["full"] }
// uuid = { version = "1", features = ["v4", "serde"] }
// serde = { version = "1", features = ["derive"] }
// serde_json = "1.0"
// chrono = { version = "0.4", features = ["serde"] }
// moka = { version = "0.12", features = ["future"] }
// tracing = "0.1"
// tracing-subscriber = { version = "0.3", features = ["env-filter"] }
// std::sync::Arc is used implicitly

use axum::{
    async_trait,
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, patch},
    Json, Router,
};
use chrono::{DateTime, Utc};
use moka::future::Cache;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::RwLock;
use uuid::Uuid;

// --- 1. Domain Models ---
mod models {
    use super::*;

    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub enum UserRole {
        ADMIN,
        USER,
    }

    #[derive(Debug, Clone, Serialize, Deserialize)]
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
    pub enum PostStatus {
        DRAFT,
        PUBLISHED,
    }

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
        pub is_active: Option<bool>,
    }
}

// --- 2. Mock Database Layer ---
mod db {
    use super::models::{User, UserRole};
    use super::*;
    use std::sync::Arc;

    #[derive(Clone)]
    pub struct DbRepo {
        users: Arc<RwLock<HashMap<Uuid, User>>>,
    }

    impl DbRepo {
        pub fn new() -> Self {
            let mut users = HashMap::new();
            let user_id = Uuid::new_v4();
            users.insert(
                user_id,
                User {
                    id: user_id,
                    email: "admin@example.com".to_string(),
                    password_hash: "hashed_password".to_string(),
                    role: UserRole::ADMIN,
                    is_active: true,
                    created_at: Utc::now(),
                },
            );
            Self {
                users: Arc::new(RwLock::new(users)),
            }
        }

        pub async fn find_user_by_id(&self, id: Uuid) -> Option<User> {
            // Simulate DB latency
            tokio::time::sleep(Duration::from_millis(50)).await;
            self.users.read().await.get(&id).cloned()
        }

        pub async fn update_user(&self, id: Uuid, payload: models::UpdateUserPayload) -> Option<User> {
            let mut users = self.users.write().await;
            if let Some(user) = users.get_mut(&id) {
                if let Some(email) = payload.email {
                    user.email = email;
                }
                if let Some(is_active) = payload.is_active {
                    user.is_active = is_active;
                }
                Some(user.clone())
            } else {
                None
            }
        }
    }
}

// --- 3. Caching Layer ---
mod cache {
    use super::models::User;
    use super::*;

    #[derive(Clone)]
    pub struct CacheService {
        user_cache: Cache<Uuid, User>,
    }

    impl CacheService {
        pub fn new() -> Self {
            // LRU cache with a max capacity of 1,000 items and a 10-minute time-to-live.
            let user_cache = Cache::builder()
                .max_capacity(1_000)
                .time_to_live(Duration::from_secs(10 * 60))
                .build();
            Self { user_cache }
        }

        pub async fn get_user(&self, id: &Uuid) -> Option<User> {
            self.user_cache.get(id).await
        }

        pub async fn set_user(&self, user: &User) {
            self.user_cache.insert(user.id, user.clone()).await;
        }

        pub async fn invalidate_user(&self, id: &Uuid) {
            self.user_cache.invalidate(id).await;
        }
    }
}

// --- 4. Application State & Error Handling ---
use db::DbRepo;
use cache::CacheService;
use models::{UpdateUserPayload, User};

#[derive(Clone)]
struct AppState {
    db_repo: DbRepo,
    cache_service: CacheService,
}

struct AppError(StatusCode, String);

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        (self.0, self.1).into_response()
    }
}

impl<E> From<E> for AppError where E: std::error::Error {
    fn from(err: E) -> Self {
        Self(StatusCode::INTERNAL_SERVER_ERROR, err.to_string())
    }
}

// --- 5. API Handlers ---
mod handlers {
    use super::*;

    /// Handler to get a user by ID.
    /// Implements the cache-aside pattern.
    pub async fn get_user(
        State(state): State<Arc<AppState>>,
        Path(user_id): Path<Uuid>,
    ) -> Result<Json<User>, AppError> {
        // 1. Check cache first
        if let Some(user) = state.cache_service.get_user(&user_id).await {
            tracing::info!("CACHE HIT for user_id: {}", user_id);
            return Ok(Json(user));
        }

        // 2. If miss, fetch from database
        tracing::info!("CACHE MISS for user_id: {}", user_id);
        match state.db_repo.find_user_by_id(user_id).await {
            Some(user) => {
                // 3. Populate cache
                tracing::info!("DB HIT, populating cache for user_id: {}", user_id);
                state.cache_service.set_user(&user).await;
                Ok(Json(user))
            }
            None => Err(AppError(
                StatusCode::NOT_FOUND,
                format!("User with id {} not found", user_id),
            )),
        }
    }

    /// Handler to update a user.
    /// Implements cache invalidation.
    pub async fn update_user(
        State(state): State<Arc<AppState>>,
        Path(user_id): Path<Uuid>,
        Json(payload): Json<UpdateUserPayload>,
    ) -> Result<Json<User>, AppError> {
        // 1. Update the database
        match state.db_repo.update_user(user_id, payload).await {
            Some(updated_user) => {
                // 2. Invalidate the cache
                tracing::info!("DB UPDATE, invalidating cache for user_id: {}", user_id);
                state.cache_service.invalidate_user(&user_id).await;
                Ok(Json(updated_user))
            }
            None => Err(AppError(
                StatusCode::NOT_FOUND,
                format!("User with id {} not found", user_id),
            )),
        }
    }
}

// --- 6. Main Application Setup ---
#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter("info")
        .init();

    let shared_state = Arc::new(AppState {
        db_repo: DbRepo::new(),
        cache_service: CacheService::new(),
    });

    let app = Router::new()
        .route("/users/:id", get(handlers::get_user))
        .route("/users/:id", patch(handlers::update_user))
        .with_state(shared_state);

    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
    tracing::info!("listening on {}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.unwrap();
}