// Variation 2: The "OOP & Service-Oriented" Developer
// Style: Encapsulates logic within structs and `impl` blocks (services).
// Cache: A custom LRU cache implementation from scratch.
// State: A single `AppState` struct holds all shared services.
// Error Handling: Uses `anyhow` for flexible error handling in services.

// --- Cargo.toml dependencies ---
// [dependencies]
// axum = "0.7"
// tokio = { version = "1", features = ["full"] }
// uuid = { version = "1", features = ["v4", "serde"] }
// serde = { version = "1", features = ["derive"] }
// serde_json = "1.0"
// chrono = { version = "0.4", features = ["serde"] }
// anyhow = "1.0"
// parking_lot = "0.12"
// tracing = "0.1"
// tracing-subscriber = { version = "0.3", features = ["env-filter"] }

use axum::{
    async_trait,
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, patch},
    Json, Router,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use std::time::{Duration, Instant};
use uuid::Uuid;
use parking_lot::Mutex;

// --- Domain Layer ---
mod domain {
    use super::*;
    #[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
    pub enum UserRole { ADMIN, USER }

    #[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        #[serde(skip_serializing)]
        pub password_hash: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
    pub enum PostStatus { DRAFT, PUBLISHED }

    #[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }
    
    #[derive(Deserialize)]
    pub struct UserUpdateDTO {
        pub email: Option<String>,
    }
}

// --- Service Layer ---
mod services {
    use super::domain::{User, UserRole, UserUpdateDTO};
    use super::*;
    use anyhow::{anyhow, Context, Result};

    // --- Custom Cache Implementation ---
    struct CacheEntry<V> {
        value: V,
        expiry: Instant,
    }
    
    pub struct LruCache<K, V> {
        capacity: usize,
        ttl: Duration,
        map: HashMap<K, CacheEntry<V>>,
        lru_queue: VecDeque<K>,
    }

    impl<K, V> LruCache<K, V>
    where
        K: Eq + std::hash::Hash + Clone,
        V: Clone,
    {
        pub fn new(capacity: usize, ttl: Duration) -> Self {
            Self {
                capacity,
                ttl,
                map: HashMap::with_capacity(capacity),
                lru_queue: VecDeque::with_capacity(capacity),
            }
        }

        pub fn get(&mut self, key: &K) -> Option<V> {
            if let Some(entry) = self.map.get(key) {
                if entry.expiry > Instant::now() {
                    // Move to front of LRU queue
                    self.lru_queue.retain(|k| k != key);
                    self.lru_queue.push_front(key.clone());
                    return Some(entry.value.clone());
                } else {
                    // Expired, remove it
                    self.map.remove(key);
                    self.lru_queue.retain(|k| k != key);
                }
            }
            None
        }

        pub fn put(&mut self, key: K, value: V) {
            if self.map.len() >= self.capacity {
                if let Some(oldest_key) = self.lru_queue.pop_back() {
                    self.map.remove(&oldest_key);
                }
            }
            let entry = CacheEntry {
                value,
                expiry: Instant::now() + self.ttl,
            };
            self.map.insert(key.clone(), entry);
            self.lru_queue.push_front(key);
        }

        pub fn remove(&mut self, key: &K) {
            self.map.remove(key);
            self.lru_queue.retain(|k| k != key);
        }
    }

    // --- Mock Database ---
    pub struct MockDb {
        users: HashMap<Uuid, User>,
    }

    impl MockDb {
        pub fn new() -> Arc<Mutex<Self>> {
            let mut users = HashMap::new();
            let user_id = Uuid::new_v4();
            users.insert(
                user_id,
                User {
                    id: user_id,
                    email: "oop@example.com".to_string(),
                    password_hash: "hashed_password_2".to_string(),
                    role: UserRole::USER,
                    is_active: true,
                    created_at: Utc::now(),
                },
            );
            Arc::new(Mutex::new(Self { users }))
        }
    }

    // --- User Service ---
    #[derive(Clone)]
    pub struct UserService {
        db: Arc<Mutex<MockDb>>,
        cache: Arc<Mutex<LruCache<Uuid, User>>>,
    }

    impl UserService {
        pub fn new(db: Arc<Mutex<MockDb>>) -> Self {
            Self {
                db,
                cache: Arc::new(Mutex::new(LruCache::new(100, Duration::from_secs(300)))),
            }
        }

        // Cache-Aside Pattern
        pub async fn find_by_id(&self, user_id: Uuid) -> Result<User> {
            // 1. Check cache
            if let Some(user) = self.cache.lock().get(&user_id) {
                tracing::info!("CACHE HIT on user {}", user_id);
                return Ok(user);
            }
            
            tracing::info!("CACHE MISS on user {}", user_id);
            // 2. Fetch from DB on miss
            // Simulate DB latency
            tokio::time::sleep(Duration::from_millis(50)).await;
            let db_lock = self.db.lock();
            let user = db_lock.users.get(&user_id).cloned()
                .ok_or_else(|| anyhow!("User not found"))?;

            // 3. Populate cache
            self.cache.lock().put(user_id, user.clone());
            Ok(user)
        }

        // Cache Invalidation
        pub async fn update_user(&self, user_id: Uuid, payload: UserUpdateDTO) -> Result<User> {
            // 1. Update DB
            let mut db_lock = self.db.lock();
            let user = db_lock.users.get_mut(&user_id)
                .context("User not found for update")?;
            
            if let Some(email) = payload.email {
                user.email = email;
            }
            let updated_user = user.clone();

            // 2. Invalidate cache
            self.cache.lock().remove(&user_id);
            tracing::info!("CACHE INVALIDATED for user {}", user_id);
            Ok(updated_user)
        }
    }
}

// --- Application Layer (Axum Handlers & State) ---
use domain::{User, UserUpdateDTO};
use services::{MockDb, UserService};

#[derive(Clone)]
struct AppState {
    user_service: UserService,
}

struct AppError(anyhow::Error);

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        tracing::error!("Application error: {:?}", self.0);
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Something went wrong: {}", self.0),
        )
            .into_response()
    }
}

impl<E> From<E> for AppError where E: Into<anyhow::Error> {
    fn from(err: E) -> Self {
        Self(err.into())
    }
}

async fn get_user_handler(
    State(state): State<Arc<AppState>>,
    Path(user_id): Path<Uuid>,
) -> Result<Json<User>, AppError> {
    let user = state.user_service.find_by_id(user_id).await?;
    Ok(Json(user))
}

async fn update_user_handler(
    State(state): State<Arc<AppState>>,
    Path(user_id): Path<Uuid>,
    Json(payload): Json<UserUpdateDTO>,
) -> Result<Json<User>, AppError> {
    let user = state.user_service.update_user(user_id, payload).await?;
    Ok(Json(user))
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt().with_env_filter("info").init();

    let db = MockDb::new();
    let user_service = UserService::new(db);
    let app_state = Arc::new(AppState { user_service });

    let app = Router::new()
        .route("/users/:id", get(get_user_handler))
        .route("/users/:id", patch(update_user_handler))
        .with_state(app_state);

    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
    tracing::info!("listening on {}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.unwrap();
}