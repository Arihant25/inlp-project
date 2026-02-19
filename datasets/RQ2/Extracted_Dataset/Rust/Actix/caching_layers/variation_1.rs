// Variation 1: "The Pragmatist" - Functional & Service-Oriented
// This variation uses a clean, modular structure with a dedicated service layer
// to encapsulate business logic, including the cache-aside pattern.
//
// Dependencies to add in Cargo.toml:
// actix-web = "4"
// serde = { version = "1.0", features = ["derive"] }
// uuid = { version = "1.8", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }
// lru = "0.12"
// tokio = { version = "1", features = ["macros", "rt-multi-thread"] }
// env_logger = "0.10"
// std::sync::Arc
// std::sync::Mutex

use actix_web::{web, App, HttpResponse, HttpServer, Responder};
use chrono::{DateTime, Utc};
use lru::LruCache;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::num::NonZeroUsize;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use uuid::Uuid;

// --- 1. Models ---
mod models {
    use super::*;

    #[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
    pub enum UserRole {
        ADMIN,
        USER,
    }

    #[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        #[serde(skip_serializing)]
        pub password_hash: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
    pub enum PostStatus {
        DRAFT,
        PUBLISHED,
    }

    #[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }
}

// --- 2. Cache Implementation ---
mod cache {
    use super::*;

    const CACHE_TTL_SECONDS: u64 = 300; // 5 minutes

    pub struct CacheEntry<T> {
        value: T,
        expires_at: Instant,
    }

    impl<T: Clone> CacheEntry<T> {
        pub fn new(value: T) -> Self {
            CacheEntry {
                value,
                expires_at: Instant::now() + Duration::from_secs(CACHE_TTL_SECONDS),
            }
        }

        pub fn is_expired(&self) -> bool {
            self.expires_at < Instant::now()
        }

        pub fn value(&self) -> T {
            self.value.clone()
        }
    }

    pub type UserCache = Arc<Mutex<LruCache<Uuid, CacheEntry<models::User>>>>;
    pub type PostCache = Arc<Mutex<LruCache<Uuid, CacheEntry<models::Post>>>>;

    pub fn new_user_cache() -> UserCache {
        Arc::new(Mutex::new(LruCache::new(NonZeroUsize::new(100).unwrap())))
    }

    pub fn new_post_cache() -> PostCache {
        Arc::new(Mutex::new(LruCache::new(NonZeroUsize::new(200).unwrap())))
    }
}

// --- 3. Database Mock ---
mod db {
    use super::*;

    pub type DbUsers = Arc<Mutex<HashMap<Uuid, models::User>>>;
    pub type DbPosts = Arc<Mutex<HashMap<Uuid, models::Post>>>;

    pub fn init_db() -> (DbUsers, DbPosts) {
        let mut users = HashMap::new();
        let mut posts = HashMap::new();

        let user_id = Uuid::new_v4();
        let user = models::User {
            id: user_id,
            email: "admin@example.com".to_string(),
            password_hash: "hashed_password".to_string(),
            role: models::UserRole::ADMIN,
            is_active: true,
            created_at: Utc::now(),
        };
        users.insert(user_id, user.clone());

        let post_id = Uuid::new_v4();
        let post = models::Post {
            id: post_id,
            user_id,
            title: "First Post".to_string(),
            content: "This is the first post.".to_string(),
            status: models::PostStatus::PUBLISHED,
        };
        posts.insert(post_id, post.clone());

        (Arc::new(Mutex::new(users)), Arc::new(Mutex::new(posts)))
    }
}

// --- 4. Service Layer ---
mod services {
    use super::*;

    pub struct UserService {
        db: db::DbUsers,
        cache: cache::UserCache,
    }

    impl UserService {
        pub fn new(db: db::DbUsers, cache: cache::UserCache) -> Self {
            Self { db, cache }
        }

        // Implements cache-aside pattern for reads
        pub fn find_by_id(&self, id: Uuid) -> Option<models::User> {
            // 1. Check cache
            if let Some(entry) = self.cache.lock().unwrap().get(&id) {
                if !entry.is_expired() {
                    println!("CACHE HIT for user {}", id);
                    return Some(entry.value());
                }
                println!("CACHE EXPIRED for user {}", id);
            }

            // 2. Cache miss or expired, fetch from DB
            println!("CACHE MISS for user {}", id);
            let user = self.db.lock().unwrap().get(&id).cloned();

            // 3. If found in DB, update cache
            if let Some(ref found_user) = user {
                let entry = cache::CacheEntry::new(found_user.clone());
                self.cache.lock().unwrap().put(id, entry);
                println!("CACHE SET for user {}", id);
            }

            user
        }

        // Implements cache invalidation for writes
        pub fn delete_by_id(&self, id: Uuid) -> Option<models::User> {
            // 1. Update primary data source
            let deleted_user = self.db.lock().unwrap().remove(&id);

            // 2. Invalidate cache
            if deleted_user.is_some() {
                self.cache.lock().unwrap().pop(&id);
                println!("CACHE INVALIDATED for user {}", id);
            }

            deleted_user
        }
    }
}

// --- 5. Handlers / Controllers ---
mod handlers {
    use super::*;

    pub async fn get_user(
        user_service: web::Data<services::UserService>,
        user_id: web::Path<Uuid>,
    ) -> impl Responder {
        let id = user_id.into_inner();
        match user_service.find_by_id(id) {
            Some(user) => HttpResponse::Ok().json(user),
            None => HttpResponse::NotFound().body(format!("User not found: {}", id)),
        }
    }

    pub async fn delete_user(
        user_service: web::Data<services::UserService>,
        user_id: web::Path<Uuid>,
    ) -> impl Responder {
        let id = user_id.into_inner();
        match user_service.delete_by_id(id) {
            Some(user) => HttpResponse::Ok().json(user),
            None => HttpResponse::NotFound().body(format!("User not found: {}", id)),
        }
    }
}

// --- 6. Application State and Main ---
struct AppState {
    user_service: services::UserService,
    // In a real app, you'd also have a PostService, etc.
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    env_logger::init_from_env(env_logger::Env::new().default_filter_or("info"));

    let (db_users, _db_posts) = db::init_db();
    let user_cache = cache::new_user_cache();

    let user_service = services::UserService::new(db_users.clone(), user_cache.clone());

    let app_state = web::Data::new(AppState { user_service });

    println!("Server starting at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(app_state.clone())
            .route("/users/{id}", web::get().to(handlers::get_user))
            .route("/users/{id}", web::delete().to(handlers::delete_user))
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}