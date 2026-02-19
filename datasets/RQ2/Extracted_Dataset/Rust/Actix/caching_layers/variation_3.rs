// Variation 3: "The Minimalist" - All-in-One with Macros
// This variation puts everything into a single file, uses type aliases for brevity,
// and employs a macro to reduce the boilerplate of the cache-aside logic in handlers.
// State is managed by registering individual `web::Data` types with the app.
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

// --- Domain Models ---
#[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
pub enum UserRole { ADMIN, USER }
#[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
pub struct User {
    pub id: Uuid,
    pub email: String,
    pub password_hash: String,
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

// --- Cache & DB Infrastructure with Type Aliases ---
const TTL: Duration = Duration::from_secs(120);

#[derive(Clone)]
struct CacheItem<T> {
    value: T,
    expires: Instant,
}

impl<T> CacheItem<T> {
    fn new(value: T) -> Self {
        Self { value, expires: Instant::now() + TTL }
    }
    fn is_valid(&self) -> bool {
        self.expires > Instant::now()
    }
}

type UserCache = Arc<Mutex<LruCache<Uuid, CacheItem<User>>>>;
type PostCache = Arc<Mutex<LruCache<Uuid, CacheItem<Post>>>>;
type UserDb = Arc<Mutex<HashMap<Uuid, User>>>;
type PostDb = Arc<Mutex<HashMap<Uuid, Post>>>;

// --- Macro for Cache-Aside Logic ---
macro_rules! implement_cache_aside_get {
    ($fn_name:ident, $entity:ty, $cache_type:ty, $db_type:ty) => {
        async fn $fn_name(
            id: web::Path<Uuid>,
            cache: web::Data<$cache_type>,
            db: web::Data<$db_type>,
        ) -> impl Responder {
            let entity_id = id.into_inner();
            let entity_name = stringify!($entity).to_lowercase();

            // 1. Check cache
            if let Some(item) = cache.lock().unwrap().get(&entity_id) {
                if item.is_valid() {
                    println!("CACHE HIT for {} {}", entity_name, entity_id);
                    return HttpResponse::Ok().json(item.value.clone());
                }
            }

            // 2. Cache miss, check DB
            println!("CACHE MISS for {} {}", entity_name, entity_id);
            if let Some(entity) = db.lock().unwrap().get(&entity_id).cloned() {
                // 3. Found in DB, populate cache
                cache.lock().unwrap().put(entity_id, CacheItem::new(entity.clone()));
                println!("CACHE SET for {} {}", entity_name, entity_id);
                return HttpResponse::Ok().json(entity);
            }

            HttpResponse::NotFound().body(format!("{} with id {} not found", entity_name, entity_id))
        }
    };
}

// --- Generate Handlers using the Macro ---
implement_cache_aside_get!(get_user, User, UserCache, UserDb);
implement_cache_aside_get!(get_post, Post, PostCache, PostDb);

// --- Write/Invalidation Handler ---
async fn create_post(
    new_post: web::Json<Post>,
    post_cache: web::Data<PostCache>,
    post_db: web::Data<PostDb>,
) -> impl Responder {
    let mut post = new_post.into_inner();
    post.id = Uuid::new_v4(); // Assign a new ID

    // 1. Write to the primary data source
    post_db.lock().unwrap().insert(post.id, post.clone());
    println!("DB WRITE for new post {}", post.id);

    // 2. Invalidate cache (or in this case, we can prime it)
    post_cache.lock().unwrap().put(post.id, CacheItem::new(post.clone()));
    println!("CACHE PRIMED for new post {}", post.id);

    HttpResponse::Created().json(post)
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    env_logger::init_from_env(env_logger::Env::new().default_filter_or("info"));

    // --- Mock Data Initialization ---
    let user_id = Uuid::new_v4();
    let user_db_data = Arc::new(Mutex::new(HashMap::from([(user_id, User {
        id: user_id,
        email: "minimal@example.com".to_string(),
        password_hash: "secret".to_string(),
        role: UserRole::USER,
        is_active: true,
        created_at: Utc::now(),
    })])));

    let post_id = Uuid::new_v4();
    let post_db_data = Arc::new(Mutex::new(HashMap::from([(post_id, Post {
        id: post_id,
        user_id,
        title: "Minimalist Post".to_string(),
        content: "Content...".to_string(),
        status: PostStatus::PUBLISHED,
    })])));

    let user_cache_data = Arc::new(Mutex::new(LruCache::new(NonZeroUsize::new(100).unwrap())));
    let post_cache_data = Arc::new(Mutex::new(LruCache::new(NonZeroUsize::new(200).unwrap())));

    println!("Server starting at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(web::Data::new(user_cache_data.clone()))
            .app_data(web::Data::new(post_cache_data.clone()))
            .app_data(web::Data::new(user_db_data.clone()))
            .app_data(web::Data::new(post_db_data.clone()))
            .route("/users/{id}", web::get().to(get_user))
            .route("/posts/{id}", web::get().to(get_post))
            .route("/posts", web::post().to(create_post))
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}