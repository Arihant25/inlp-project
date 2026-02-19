/*
 * VARIATION 4: The "Trait-based & Generic" Developer
 *
 * STYLE:
 * - Heavily utilizes traits for abstracting behavior (e.g., `Cacheable`, `Repository`).
 * - Aims for generic, reusable components (`LruCacheProvider`, `InMemoryRepository`).
 * - Structure is organized by architectural layers (domain, application, infrastructure).
 * - The `Cacheable` trait centralizes how entities are identified and keyed for caching.
 * - Demonstrates a highly decoupled and testable architecture.
 */

// Cargo.toml dependencies:
// rocket = { version = "0.5.0", features = ["json"] }
// serde = { version = "1.0", features = ["derive"] }
// uuid = { version = "1.6", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }
// lru = "0.12"
// tokio = { version = "1", features = ["full"] }
// async-trait = "0.1.77"

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
use std::marker::PhantomData;
use std::hash::Hash;
use async_trait::async_trait;
use serde::de::DeserializeOwned;

// --- Domain Layer ---
mod domain {
    use super::*;
    use crate::application_traits::Cacheable;

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

    impl Cacheable for User {
        type Key = Uuid;
        fn entity_id(&self) -> Self::Key { self.id }
    }
    // Post model and its `Cacheable` impl would go here
}

// --- Application Traits Layer ---
mod application_traits {
    use super::*;

    pub trait Cacheable: Serialize + DeserializeOwned + Clone + Send + Sync + 'static {
        type Key: Hash + Eq + Copy + Send + Sync;
        fn entity_id(&self) -> Self::Key;
    }

    #[async_trait]
    pub trait Repository<E: Cacheable>: Send + Sync {
        async fn find(&self, id: E::Key) -> Option<E>;
        async fn save(&self, entity: &E) -> E;
    }

    #[async_trait]
    pub trait CacheProvider<E: Cacheable>: Send + Sync {
        async fn get(&self, key: E::Key) -> Option<E>;
        async fn set(&self, entity: &E);
        async fn invalidate(&self, key: E::Key);
    }
}

// --- Infrastructure Layer ---
mod infrastructure {
    use super::*;
    use application_traits::*;
    use domain::User;

    // --- In-Memory Repository Implementation ---
    pub struct InMemoryRepository<E: Cacheable> {
        store: Mutex<HashMap<E::Key, E>>,
    }

    impl<E: Cacheable> InMemoryRepository<E> {
        pub fn new(initial_data: HashMap<E::Key, E>) -> Self {
            Self { store: Mutex::new(initial_data) }
        }
    }

    #[async_trait]
    impl<E: Cacheable> Repository<E> for InMemoryRepository<E> {
        async fn find(&self, id: E::Key) -> Option<E> {
            tokio::time::sleep(Duration::from_millis(50)).await; // Simulate latency
            self.store.lock().await.get(&id).cloned()
        }
        async fn save(&self, entity: &E) -> E {
            tokio::time::sleep(Duration::from_millis(50)).await;
            self.store.lock().await.insert(entity.entity_id(), entity.clone());
            entity.clone()
        }
    }

    // --- Generic LRU Cache Provider Implementation ---
    #[derive(Clone)]
    struct TimedEntry<V> { value: V, inserted: Instant }
    
    pub struct LruCacheProvider<E: Cacheable> {
        cache: Mutex<LruCache<E::Key, TimedEntry<E>>>,
        ttl: Duration,
        _phantom: PhantomData<E>,
    }

    impl<E: Cacheable> LruCacheProvider<E> {
        pub fn new(capacity: usize, ttl: Duration) -> Self {
            Self {
                cache: Mutex::new(LruCache::new(NonZeroUsize::new(capacity).unwrap())),
                ttl,
                _phantom: PhantomData,
            }
        }
    }

    #[async_trait]
    impl<E: Cacheable> CacheProvider<E> for LruCacheProvider<E> {
        async fn get(&self, key: E::Key) -> Option<E> {
            let mut cache = self.cache.lock().await;
            if let Some(entry) = cache.get(&key) {
                if entry.inserted.elapsed() < self.ttl {
                    return Some(entry.value.clone());
                }
                cache.pop(&key);
            }
            None
        }
        async fn set(&self, entity: &E) {
            let entry = TimedEntry { value: entity.clone(), inserted: Instant::now() };
            self.cache.lock().await.put(entity.entity_id(), entry);
        }
        async fn invalidate(&self, key: E::Key) {
            self.cache.lock().await.pop(&key);
        }
    }
}

// --- Application/API Layer ---
mod api {
    use super::*;
    use application_traits::*;
    use domain::User;

    type UserRepo = dyn Repository<User>;
    type UserCache = dyn CacheProvider<User>;

    #[get("/users/<id>")]
    pub async fn get_user(id: Uuid, repo: &State<Box<UserRepo>>, cache: &State<Box<UserCache>>) -> Option<Json<User>> {
        // Cache-Aside Pattern
        if let Some(user) = cache.get(id).await {
            println!("CACHE HIT for user {}", id);
            return Some(Json(user));
        }

        println!("CACHE MISS for user {}", id);
        if let Some(user) = repo.find(id).await {
            cache.set(&user).await;
            return Some(Json(user));
        }
        
        None
    }

    #[put("/users/<id>", data = "<user_data>")]
    pub async fn update_user(id: Uuid, mut user_data: Json<User>, repo: &State<Box<UserRepo>>, cache: &State<Box<UserCache>>) -> Json<User> {
        user_data.id = id;
        
        // Update DB
        let updated_user = repo.save(&user_data).await;
        
        // Invalidate Cache
        println!("INVALIDATING CACHE for user {}", id);
        cache.invalidate(id).await;

        Json(updated_user)
    }
}

#[launch]
fn rocket() -> _ {
    use domain::User;
    use infrastructure::{InMemoryRepository, LruCacheProvider};
    use application_traits::{Repository, CacheProvider};

    // --- Dependency Injection Setup ---
    let user_id = Uuid::new_v4();
    let initial_user = User {
        id: user_id,
        email: "admin@example.com".to_string(),
        password_hash: "hashed_password".to_string(),
        role: domain::UserRole::ADMIN,
        is_active: true,
        created_at: Utc::now(),
    };
    let mut initial_data = HashMap::new();
    initial_data.insert(user_id, initial_user);

    let user_repo: Box<dyn Repository<User>> = Box::new(InMemoryRepository::new(initial_data));
    let user_cache: Box<dyn CacheProvider<User>> = Box::new(LruCacheProvider::new(100, Duration::from_secs(300)));

    rocket::build()
        .mount("/", routes![api::get_user, api::update_user])
        .manage(user_repo)
        .manage(user_cache)
}