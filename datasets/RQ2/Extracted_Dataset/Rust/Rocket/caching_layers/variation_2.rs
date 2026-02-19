/*
 * VARIATION 2: The "Functional & Idiomatic Rust" Developer
 *
 * STYLE:
 * - Prefers free-standing functions organized in modules over service classes.
 * - Leverages a single, comprehensive `AppState` struct for state management.
 * - Uses type aliases for clarity (e.g., `UserCache`).
 * - Implements a generic `get_or_fetch` helper function to encapsulate the cache-aside pattern.
 * - Naming is concise and follows Rust conventions (e.g., `db`, `user_cache`, `get_user`).
 */

// Cargo.toml dependencies:
// rocket = { version = "0.5.0", features = ["json"] }
// serde = { version = "1.0", features = ["derive"] }
// uuid = { version = "1.6", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }
// lru = "0.12"
// tokio = { version = "1", features = ["full"] }

#[macro_use]
extern crate rocket;

use rocket::serde::{json::Json, Deserialize, Serialize};
use rocket::State;
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::collections::HashMap;
use tokio::sync::Mutex;
use lru::LruCache;
use std::num::NonZeroUsize;
use std::time::{Duration, Instant};
use std::future::Future;
use std::hash::Hash;

// --- Domain Model ---
mod domain {
    use super::*;
    #[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
    #[serde(crate = "rocket::serde")]
    pub enum UserRole { ADMIN, USER }

    #[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
    #[serde(crate = "rocket::serde")]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        pub password_hash: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }
    // Post model would be defined here as well
}
use domain::*;

// --- Data Source ---
mod data_source {
    use super::*;
    pub struct Db {
        users: Mutex<HashMap<Uuid, User>>,
    }

    impl Db {
        pub fn new() -> Self {
            let mut users = HashMap::new();
            let user_id = Uuid::new_v4();
            users.insert(user_id, User {
                id: user_id,
                email: "admin@example.com".to_string(),
                password_hash: "hashed_password".to_string(),
                role: UserRole::ADMIN,
                is_active: true,
                created_at: Utc::now(),
            });
            Self { users: Mutex::new(users) }
        }

        pub async fn find_user(&self, id: &Uuid) -> Option<User> {
            tokio::time::sleep(Duration::from_millis(50)).await; // Simulate latency
            self.users.lock().await.get(id).cloned()
        }

        pub async fn save_user(&self, user: &User) -> User {
            tokio::time::sleep(Duration::from_millis(50)).await;
            self.users.lock().await.insert(user.id, user.clone());
            user.clone()
        }
    }
}
use data_source::Db;

// --- Caching Logic ---
mod caching {
    use super::*;
    
    const TTL: Duration = Duration::from_secs(300);

    #[derive(Clone)]
    pub struct CacheItem<V> {
        value: V,
        inserted_at: Instant,
    }

    impl<V> CacheItem<V> {
        fn new(value: V) -> Self {
            Self { value, inserted_at: Instant::now() }
        }
        fn is_expired(&self) -> bool {
            self.inserted_at.elapsed() > TTL
        }
    }

    pub type UserCache = Mutex<LruCache<Uuid, CacheItem<User>>>;

    // Generic cache-aside helper function
    pub async fn get_or_fetch<K, V, F, Fut>(
        cache: &Mutex<LruCache<K, CacheItem<V>>>,
        key: K,
        fetch_fn: F,
    ) -> Option<V>
    where
        K: Hash + Eq + Copy + Send,
        V: Clone + Send,
        F: FnOnce() -> Fut,
        Fut: Future<Output = Option<V>> + Send,
    {
        // 1. Check cache
        let mut locked_cache = cache.lock().await;
        if let Some(item) = locked_cache.get(&key) {
            if !item.is_expired() {
                println!("CACHE HIT for key");
                return Some(item.value.clone());
            }
            // Expired, pop it
            locked_cache.pop(&key);
        }
        drop(locked_cache);

        // 2. On miss, fetch from data source
        println!("CACHE MISS for key");
        let fetched_value = fetch_fn().await;

        // 3. If found, update cache
        if let Some(ref value) = fetched_value {
            let mut locked_cache = cache.lock().await;
            locked_cache.put(key, CacheItem::new(value.clone()));
        }

        fetched_value
    }
}
use caching::{get_or_fetch, UserCache};

// --- Application State ---
struct AppState {
    db: Db,
    user_cache: UserCache,
}

// --- Web Handlers ---
mod handlers {
    use super::*;

    #[get("/users/<id>")]
    pub async fn get_user(id: Uuid, state: &State<AppState>) -> Option<Json<User>> {
        let fetch_from_db = || async { state.db.find_user(&id).await };
        
        get_or_fetch(&state.user_cache, id, fetch_from_db)
            .await
            .map(Json)
    }

    #[put("/users/<id>", data = "<user_data>")]
    pub async fn update_user(id: Uuid, mut user_data: Json<User>, state: &State<AppState>) -> Json<User> {
        user_data.id = id;
        
        // 1. Update data source
        let updated_user = state.db.save_user(&user_data).await;

        // 2. Invalidate cache
        println!("INVALIDATING CACHE for user {}", id);
        state.user_cache.lock().await.pop(&id);

        Json(updated_user)
    }
}

#[launch]
fn rocket() -> _ {
    let state = AppState {
        db: Db::new(),
        user_cache: Mutex::new(LruCache::new(NonZeroUsize::new(100).unwrap())),
    };

    rocket::build()
        .mount("/", routes![handlers::get_user, handlers::update_user])
        .manage(state)
}