// Variation 3: The "Minimalist & Type-Driven" Developer
// Style: Single-file, concise, avoids complex abstractions.
// Cache: Uses the `lru` crate, wrapped to add time-based expiration.
// State: `AppState` holds `Arc<Mutex<...>>` for cache and DB directly.
// Error Handling: Simple `(StatusCode, String)` tuples for errors.

// --- Cargo.toml dependencies ---
// [dependencies]
// axum = "0.7"
// tokio = { version = "1", features = ["full", "sync"] }
// uuid = { version = "1", features = ["v4", "serde"] }
// serde = { version = "1", features = ["derive"] }
// serde_json = "1.0"
// chrono = { version = "0.4", features = ["serde"] }
// lru = "0.12"
// tracing = "0.1"
// tracing-subscriber = { version = "0.3", features = ["env-filter"] }

use axum::{
    extract::{Path, State},
    http::StatusCode,
    routing::{get, patch},
    Json, Router,
};
use chrono::{DateTime, Utc};
use lru::LruCache;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::num::NonZeroUsize;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;
use uuid::Uuid;

// --- Domain Schemas ---
mod domain {
    use super::*;
    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub enum UserRole { ADMIN, USER }
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
use domain::{UpdateUserPayload, User, UserRole};

// --- Cache Wrapper for Expiration ---
#[derive(Clone)]
struct CacheEntry<T> {
    value: T,
    expires_at: Instant,
}

impl<T> CacheEntry<T> {
    fn new(value: T, ttl: Duration) -> Self {
        Self {
            value,
            expires_at: Instant::now() + ttl,
        }
    }
    fn is_expired(&self) -> bool {
        self.expires_at < Instant::now()
    }
}

// --- Application State ---
type Db = Arc<Mutex<HashMap<Uuid, User>>>;
type Cache = Arc<Mutex<LruCache<Uuid, CacheEntry<User>>>>;

#[derive(Clone)]
struct AppState {
    db: Db,
    cache: Cache,
}

// --- API Handlers ---
async fn get_user(
    State(state): State<AppState>,
    Path(user_id): Path<Uuid>,
) -> Result<Json<User>, (StatusCode, String)> {
    // 1. Check cache
    let mut cache_guard = state.cache.lock().await;
    if let Some(entry) = cache_guard.get(&user_id) {
        if !entry.is_expired() {
            tracing::info!("CACHE HIT for user {}", user_id);
            return Ok(Json(entry.value.clone()));
        }
        // Entry expired, it will be overwritten or evicted.
        tracing::info!("CACHE EXPIRED for user {}", user_id);
    }
    
    // 2. On miss or expiration, query DB
    tracing::info!("CACHE MISS for user {}", user_id);
    let db_guard = state.db.lock().await;
    // Simulate DB latency
    tokio::time::sleep(Duration::from_millis(50)).await;
    let user = db_guard.get(&user_id).cloned();

    match user {
        Some(u) => {
            // 3. Populate cache
            let ttl = Duration::from_secs(60 * 5); // 5 minutes
            let entry = CacheEntry::new(u.clone(), ttl);
            cache_guard.put(user_id, entry);
            tracing::info!("DB HIT, cache populated for user {}", user_id);
            Ok(Json(u))
        }
        None => Err((StatusCode::NOT_FOUND, format!("User {} not found", user_id))),
    }
}

async fn update_user(
    State(state): State<AppState>,
    Path(user_id): Path<Uuid>,
    Json(payload): Json<UpdateUserPayload>,
) -> Result<Json<User>, (StatusCode, String)> {
    // 1. Update DB
    let mut db_guard = state.db.lock().await;
    let user = db_guard.get_mut(&user_id);

    match user {
        Some(u) => {
            if let Some(email) = payload.email {
                u.email = email;
            }
            let updated_user = u.clone();
            drop(db_guard); // Release DB lock before acquiring cache lock

            // 2. Invalidate cache
            let mut cache_guard = state.cache.lock().await;
            cache_guard.pop(&user_id);
            tracing::info!("DB UPDATE, cache invalidated for user {}", user_id);

            Ok(Json(updated_user))
        }
        None => Err((StatusCode::NOT_FOUND, format!("User {} not found", user_id))),
    }
}

// --- Main Application ---
#[tokio::main]
async fn main() {
    tracing_subscriber::fmt().with_env_filter("info").init();

    // Initialize mock DB
    let mut db_data = HashMap::new();
    let user_id = Uuid::new_v4();
    db_data.insert(
        user_id,
        User {
            id: user_id,
            email: "minimal@example.com".to_string(),
            password_hash: "hashed_password_3".to_string(),
            role: UserRole::USER,
            is_active: true,
            created_at: Utc::now(),
        },
    );

    // Initialize state
    let state = AppState {
        db: Arc::new(Mutex::new(db_data)),
        cache: Arc::new(Mutex::new(LruCache::new(NonZeroUsize::new(100).unwrap()))),
    };

    // Build router
    let app = Router::new()
        .route("/users/:id", get(get_user))
        .route("/users/:id", patch(update_user))
        .with_state(state);

    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
    tracing::info!("listening on {}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.unwrap();
}