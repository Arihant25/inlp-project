// Variation 2: "The OOP Enthusiast" - Struct-based with Methods
// This variation centralizes logic into "manager" structs (CacheManager, DataRepository)
// that hold state and expose methods. The handlers are lean and orchestrate calls
// to these manager objects.
//
// Dependencies to add in Cargo.toml:
// actix-web = "4"
// serde = { version = "1.0", features = ["derive"] }
// uuid = { version = "1.8", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }
// lru = "0.12"
// tokio = { version = "1", features = ["macros", "rt-multi-thread"] }
// env_logger = "0.10"

use actix_web::{web, App, HttpResponse, HttpServer, Responder};
use chrono::{DateTime, Utc};
use lru::LruCache;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::num::NonZeroUsize;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use uuid::Uuid;

// --- Domain Schema ---
#[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
pub enum UserRole { ADMIN, USER }
#[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
pub struct User {
    pub id: Uuid,
    pub email: String,
    pub role: UserRole,
    pub is_active: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
pub enum PostStatus { DRAFT, PUBLISHED }
#[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
pub struct Post {
    pub id: Uuid,
    pub user_id: Uuid,
    pub title: String,
    pub content: String,
    pub status: PostStatus,
}

// --- Cache Implementation ---
struct CacheEntry<T> {
    value: T,
    expires_at: Instant,
}

impl<T> CacheEntry<T> {
    fn new(value: T, ttl: Duration) -> Self {
        Self { value, expires_at: Instant::now() + ttl }
    }
    fn is_expired(&self) -> bool {
        self.expires_at < Instant::now()
    }
}

// A manager for all caching operations
struct CacheManager {
    user_cache: Mutex<LruCache<Uuid, CacheEntry<User>>>,
    post_cache: Mutex<LruCache<Uuid, CacheEntry<Post>>>,
    default_ttl: Duration,
}

impl CacheManager {
    fn new(user_cap: usize, post_cap: usize, ttl_secs: u64) -> Self {
        CacheManager {
            user_cache: Mutex::new(LruCache::new(NonZeroUsize::new(user_cap).unwrap())),
            post_cache: Mutex::new(LruCache::new(NonZeroUsize::new(post_cap).unwrap())),
            default_ttl: Duration::from_secs(ttl_secs),
        }
    }

    fn get_user(&self, id: &Uuid) -> Option<User> {
        let mut cache = self.user_cache.lock().unwrap();
        if let Some(entry) = cache.get(id) {
            if !entry.is_expired() {
                return Some(entry.value.clone());
            }
        }
        None
    }

    fn set_user(&self, user: User) {
        let id = user.id;
        let entry = CacheEntry::new(user, self.default_ttl);
        self.user_cache.lock().unwrap().put(id, entry);
    }

    fn invalidate_user(&self, id: &Uuid) {
        self.user_cache.lock().unwrap().pop(id);
    }
}

// --- Database Mock ---
// A repository for all database operations
struct DataRepository {
    users: Mutex<HashMap<Uuid, User>>,
    posts: Mutex<HashMap<Uuid, Post>>,
}

impl DataRepository {
    fn new() -> Self {
        let mut users = HashMap::new();
        let user_id = Uuid::new_v4();
        users.insert(user_id, User {
            id: user_id,
            email: "test.user@example.com".to_string(),
            role: UserRole::USER,
            is_active: true,
            created_at: Utc::now(),
        });
        DataRepository {
            users: Mutex::new(users),
            posts: Mutex::new(HashMap::new()),
        }
    }

    fn find_user_by_id(&self, id: &Uuid) -> Option<User> {
        self.users.lock().unwrap().get(id).cloned()
    }

    fn update_user_activity(&self, id: &Uuid, is_active: bool) -> Option<User> {
        let mut users = self.users.lock().unwrap();
        if let Some(user) = users.get_mut(id) {
            user.is_active = is_active;
            return Some(user.clone());
        }
        None
    }
}

// --- Application State ---
struct AppContext {
    repo: DataRepository,
    cache: CacheManager,
}

// --- API Handlers ---
async fn get_user_by_id(
    path: web::Path<Uuid>,
    app_ctx: web::Data<AppContext>,
) -> impl Responder {
    let user_id = path.into_inner();
    
    // 1. Check cache
    if let Some(user) = app_ctx.cache.get_user(&user_id) {
        println!("User {}: CACHE HIT", user_id);
        return HttpResponse::Ok().json(user);
    }

    // 2. Cache miss, check database
    println!("User {}: CACHE MISS", user_id);
    if let Some(user) = app_ctx.repo.find_user_by_id(&user_id) {
        // 3. Found in DB, populate cache
        println!("User {}: DB HIT, populating cache", user_id);
        app_ctx.cache.set_user(user.clone());
        return HttpResponse::Ok().json(user);
    }

    HttpResponse::NotFound().finish()
}

async fn deactivate_user(
    path: web::Path<Uuid>,
    app_ctx: web::Data<AppContext>,
) -> impl Responder {
    let user_id = path.into_inner();

    // 1. Update the primary data source
    match app_ctx.repo.update_user_activity(&user_id, false) {
        Some(updated_user) => {
            // 2. Invalidate the cache to ensure consistency
            println!("User {}: DB UPDATED, invalidating cache", user_id);
            app_ctx.cache.invalidate_user(&user_id);
            HttpResponse::Ok().json(updated_user)
        }
        None => HttpResponse::NotFound().finish(),
    }
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    env_logger::init_from_env(env_logger::Env::new().default_filter_or("info"));

    let app_context = web::Data::new(AppContext {
        repo: DataRepository::new(),
        cache: CacheManager::new(100, 200, 60), // 100 users, 200 posts, 60s TTL
    });

    println!("Server starting at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(app_context.clone())
            .service(
                web::scope("/users")
                    .route("/{id}", web::get().to(get_user_by_id))
                    .route("/{id}/deactivate", web::put().to(deactivate_user)),
            )
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}