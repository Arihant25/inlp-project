/*
 * VARIATION 1: The "Service-Oriented" Developer
 *
 * STYLE:
 * - Clear separation of concerns with distinct Service, Repository (Database), and Cache layers.
 * - Rocket handlers are thin, delegating all business and data logic to services.
 * - State is managed as individual service components.
 * - Naming is explicit and verbose (e.g., `UserService`, `fetch_user_by_id`).
 * - Encapsulation is prioritized; internal cache/db details are hidden from handlers.
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
use std::sync::Arc;
use tokio::sync::Mutex;
use lru::LruCache;
use std::num::NonZeroUsize;
use std::time::{Duration, Instant};

// --- Domain Models ---
mod models {
    use super::*;

    #[derive(Debug, Clone, Serialize, Deserialize)]
    #[serde(crate = "rocket::serde")]
    pub enum UserRole { ADMIN, USER }

    #[derive(Debug, Clone, Serialize, Deserialize)]
    #[serde(crate = "rocket::serde")]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        pub password_hash: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Clone, Serialize, Deserialize)]
    #[serde(crate = "rocket::serde")]
    pub enum PostStatus { DRAFT, PUBLISHED }

    #[derive(Debug, Clone, Serialize, Deserialize)]
    #[serde(crate = "rocket::serde")]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }
}
use models::*;

// --- Data Layer (Mock Database) ---
mod database {
    use super::*;
    
    pub struct MockDatabase {
        users: Mutex<HashMap<Uuid, User>>,
        posts: Mutex<HashMap<Uuid, Post>>,
    }

    impl MockDatabase {
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

            Self {
                users: Mutex::new(users),
                posts: Mutex::new(HashMap::new()),
            }
        }

        pub async fn find_user_by_id(&self, id: Uuid) -> Option<User> {
            tokio::time::sleep(Duration::from_millis(50)).await; // Simulate DB latency
            self.users.lock().await.get(&id).cloned()
        }

        pub async fn update_user(&self, user: User) -> Option<User> {
            tokio::time::sleep(Duration::from_millis(50)).await;
            let mut users = self.users.lock().await;
            if users.contains_key(&user.id) {
                users.insert(user.id, user.clone());
                Some(user)
            } else {
                None
            }
        }
    }
}
use database::MockDatabase;

// --- Caching Layer ---
mod cache {
    use super::*;

    const CACHE_TTL_SECONDS: u64 = 300; // 5 minutes

    #[derive(Clone)]
    struct CacheEntry<T> {
        value: T,
        expires_at: Instant,
    }

    pub struct LruCacheManager<K, V> {
        cache: Mutex<LruCache<K, CacheEntry<V>>>,
    }

    impl<K: std::hash::Hash + Eq + Copy, V: Clone> LruCacheManager<K, V> {
        pub fn new(capacity: usize) -> Self {
            Self {
                cache: Mutex::new(LruCache::new(NonZeroUsize::new(capacity).unwrap())),
            }
        }

        pub async fn get(&self, key: &K) -> Option<V> {
            let mut cache = self.cache.lock().await;
            if let Some(entry) = cache.get(key) {
                if entry.expires_at > Instant::now() {
                    return Some(entry.value.clone());
                } else {
                    // Entry expired, remove it
                    cache.pop(key);
                }
            }
            None
        }

        pub async fn set(&self, key: K, value: V) {
            let mut cache = self.cache.lock().await;
            let entry = CacheEntry {
                value,
                expires_at: Instant::now() + Duration::from_secs(CACHE_TTL_SECONDS),
            };
            cache.put(key, entry);
        }

        pub async fn invalidate(&self, key: &K) {
            let mut cache = self.cache.lock().await;
            cache.pop(key);
        }
    }
}
use cache::LruCacheManager;

// --- Service Layer ---
mod services {
    use super::*;

    pub struct UserService {
        db: Arc<MockDatabase>,
        cache: Arc<LruCacheManager<Uuid, User>>,
    }

    impl UserService {
        pub fn new(db: Arc<MockDatabase>, cache: Arc<LruCacheManager<Uuid, User>>) -> Self {
            Self { db, cache }
        }

        // Cache-Aside Pattern Implementation
        pub async fn fetch_user_by_id(&self, id: Uuid) -> Option<User> {
            // 1. Try to get from cache
            if let Some(user) = self.cache.get(&id).await {
                println!("CACHE HIT for user {}", id);
                return Some(user);
            }

            // 2. On cache miss, get from database
            println!("CACHE MISS for user {}", id);
            if let Some(user) = self.db.find_user_by_id(id).await {
                // 3. Put the result into the cache
                self.cache.set(id, user.clone()).await;
                return Some(user);
            }

            None
        }

        // Cache Invalidation Strategy
        pub async fn update_user_details(&self, user: User) -> Option<User> {
            // 1. Update the database
            let updated_user = self.db.update_user(user).await;
            
            // 2. Invalidate the cache entry
            if let Some(ref u) = updated_user {
                println!("INVALIDATING CACHE for user {}", u.id);
                self.cache.invalidate(&u.id).await;
            }
            
            updated_user
        }
    }
}
use services::UserService;

// --- API Routes (Controller Layer) ---
mod routes {
    use super::*;

    #[get("/users/<id>")]
    pub async fn get_user(id: Uuid, user_service: &State<UserService>) -> Option<Json<User>> {
        user_service.fetch_user_by_id(id).await.map(Json)
    }

    #[put("/users/<id>", data = "<user_data>")]
    pub async fn update_user(id: Uuid, mut user_data: Json<User>, user_service: &State<UserService>) -> Option<Json<User>> {
        user_data.id = id; // Ensure ID from path is used
        user_service.update_user_details(user_data.into_inner()).await.map(Json)
    }
}

#[launch]
fn rocket() -> _ {
    // Initialize dependencies
    let db = Arc::new(MockDatabase::new());
    let user_cache = Arc::new(LruCacheManager::<Uuid, User>::new(100));
    let user_service = UserService::new(db.clone(), user_cache.clone());

    rocket::build()
        .mount("/", routes![routes::get_user, routes::update_user])
        .manage(user_service)
}