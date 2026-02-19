// Variation 4: "The Modern Rustacean" - Async & Actor-like
// This variation uses the `actix` actor framework to manage the cache state.
// This avoids explicit Mutex locks in handlers and provides a clean, message-passing
// interface for cache operations, which is highly idiomatic for Actix applications.
//
// Dependencies to add in Cargo.toml:
// actix = "0.13"
// actix-web = "4"
// serde = { version = "1.0", features = ["derive"] }
// uuid = { version = "1.8", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }
// lru = "0.12"
// tokio = { version = "1", features = ["macros", "rt-multi-thread"] }
// env_logger = "0.10"
// futures = "0.3"

use actix::prelude::*;
use actix_web::{web, App, HttpResponse, HttpServer, Responder};
use chrono::{DateTime, Utc};
use lru::LruCache;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::num::NonZeroUsize;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use uuid::Uuid;

// --- 1. Domain Models ---
#[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
pub enum UserRole { ADMIN, USER }
#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Message)]
#[rtype(result = "()")]
pub struct User {
    pub id: Uuid,
    pub email: String,
    pub role: UserRole,
    pub is_active: bool,
    pub created_at: DateTime<Utc>,
}

// --- 2. Cache Actor ---
mod cache_actor {
    use super::*;

    #[derive(Clone)]
    struct CacheEntry<T> {
        value: T,
        expires_at: Instant,
    }

    impl<T: Clone> CacheEntry<T> {
        fn new(value: T) -> Self {
            CacheEntry {
                value,
                expires_at: Instant::now() + Duration::from_secs(300),
            }
        }
        fn is_valid(&self) -> bool {
            self.expires_at > Instant::now()
        }
    }

    // --- Actor Definition ---
    pub struct CacheActor {
        user_cache: LruCache<Uuid, CacheEntry<User>>,
    }

    impl CacheActor {
        pub fn new() -> Self {
            CacheActor {
                user_cache: LruCache::new(NonZeroUsize::new(100).unwrap()),
            }
        }
    }

    impl Actor for CacheActor {
        type Context = Context<Self>;
    }

    // --- Messages ---
    #[derive(Message)]
    #[rtype(result = "Option<User>")]
    pub struct GetUser(pub Uuid);

    #[derive(Message)]
    #[rtype(result = "()")]
    pub struct SetUser(pub User);

    #[derive(Message)]
    #[rtype(result = "()")]
    pub struct DeleteUser(pub Uuid);

    // --- Message Handlers ---
    impl Handler<GetUser> for CacheActor {
        type Result = Option<User>;

        fn handle(&mut self, msg: GetUser, _: &mut Self::Context) -> Self::Result {
            if let Some(entry) = self.user_cache.get(&msg.0) {
                if entry.is_valid() {
                    return Some(entry.value.clone());
                }
            }
            None
        }
    }

    impl Handler<SetUser> for CacheActor {
        type Result = ();

        fn handle(&mut self, msg: SetUser, _: &mut Self::Context) -> Self::Result {
            let user = msg.0;
            let entry = CacheEntry::new(user.clone());
            self.user_cache.put(user.id, entry);
        }
    }

    impl Handler<DeleteUser> for CacheActor {
        type Result = ();

        fn handle(&mut self, msg: DeleteUser, _: &mut Self::Context) -> Self::Result {
            self.user_cache.pop(&msg.0);
        }
    }
}

// --- 3. Database Mock ---
type Db = Arc<Mutex<HashMap<Uuid, User>>>;

fn init_db() -> Db {
    let mut users = HashMap::new();
    let user_id = Uuid::new_v4();
    users.insert(user_id, User {
        id: user_id,
        email: "actor.user@example.com".to_string(),
        role: UserRole::ADMIN,
        is_active: true,
        created_at: Utc::now(),
    });
    Arc::new(Mutex::new(users))
}

// --- 4. API Handlers ---
async fn get_user(
    user_id: web::Path<Uuid>,
    db: web::Data<Db>,
    cache: web::Data<Addr<cache_actor::CacheActor>>,
) -> impl Responder {
    let id = user_id.into_inner();

    // 1. Check cache by sending a message to the actor
    match cache.send(cache_actor::GetUser(id)).await {
        Ok(Some(user)) => {
            println!("CACHE HIT for user {}", id);
            return HttpResponse::Ok().json(user);
        }
        Ok(None) => { /* Cache miss, continue to DB */ }
        Err(_) => {
            eprintln!("Error communicating with cache actor");
            return HttpResponse::InternalServerError().finish();
        }
    }

    // 2. Cache miss, fetch from DB
    println!("CACHE MISS for user {}", id);
    let user_from_db = db.lock().unwrap().get(&id).cloned();

    if let Some(user) = user_from_db {
        // 3. Found in DB, update cache by sending another message
        println!("DB HIT, updating cache for user {}", id);
        cache.do_send(cache_actor::SetUser(user.clone()));
        return HttpResponse::Ok().json(user);
    }

    HttpResponse::NotFound().finish()
}

async fn delete_user(
    user_id: web::Path<Uuid>,
    db: web::Data<Db>,
    cache: web::Data<Addr<cache_actor::CacheActor>>,
) -> impl Responder {
    let id = user_id.into_inner();

    // 1. Update primary data source
    let deleted_user = db.lock().unwrap().remove(&id);

    if let Some(user) = deleted_user {
        // 2. Invalidate cache via actor message
        println!("DB DELETE, invalidating cache for user {}", id);
        cache.do_send(cache_actor::DeleteUser(id));
        HttpResponse::Ok().json(user)
    } else {
        HttpResponse::NotFound().finish()
    }
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    env_logger::init_from_env(env_logger::Env::new().default_filter_or("info"));

    // Start the cache actor
    let cache_addr = cache_actor::CacheActor::new().start();

    // Initialize the mock database
    let db_data = web::Data::new(init_db());

    println!("Server starting at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(db_data.clone())
            .app_data(web::Data::new(cache_addr.clone()))
            .service(
                web::scope("/users")
                    .route("/{id}", web::get().to(get_user))
                    .route("/{id}", web::delete().to(delete_user)),
            )
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}