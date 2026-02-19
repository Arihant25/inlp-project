/*
 * VARIATION 3: The "Minimalist & Direct" Developer
 *
 * STYLE:
 * - Prefers a single-file structure for simplicity in smaller projects.
 * - Avoids extra layers of abstraction like services or generic helpers.
 * - Implements caching and database logic directly within the Rocket route handlers.
 * - Uses multiple `State` guards in route signatures to inject dependencies.
 * - Naming is short and pragmatic (e.g., `db`, `cache`).
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

// --- Domain Models (defined directly) ---

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

// --- Type Aliases for State Management ---

// A simple in-memory database
type Db = Mutex<HashMap<Uuid, User>>;

// An LRU cache with time-based expiration for Users
// The value is a tuple: (User, Instant of insertion)
type UserCache = Mutex<LruCache<Uuid, (User, Instant)>>;

const CACHE_TTL: Duration = Duration::from_secs(300); // 5 minutes

// --- API Routes with Inline Logic ---

#[get("/users/<id>")]
async fn get_user(id: Uuid, db: &State<Db>, cache: &State<UserCache>) -> Option<Json<User>> {
    // 1. Check cache first (Cache-Aside Pattern)
    let mut cache_lock = cache.lock().await;
    if let Some((user, inserted_at)) = cache_lock.get(&id) {
        if inserted_at.elapsed() < CACHE_TTL {
            println!("CACHE HIT for user {}", id);
            return Some(Json(user.clone()));
        } else {
            // Expired, remove from cache
            cache_lock.pop(&id);
        }
    }
    drop(cache_lock); // Release lock before DB call

    // 2. On cache miss, go to the database
    println!("CACHE MISS for user {}", id);
    tokio::time::sleep(Duration::from_millis(50)).await; // Simulate DB latency
    let db_lock = db.lock().await;
    let user_from_db = db_lock.get(&id).cloned();

    // 3. If found in DB, populate the cache
    if let Some(user) = &user_from_db {
        let mut cache_lock = cache.lock().await;
        cache_lock.put(id, (user.clone(), Instant::now()));
    }

    user_from_db.map(Json)
}

#[put("/users/<id>", data = "<user_data>")]
async fn update_user(
    id: Uuid,
    mut user_data: Json<User>,
    db: &State<Db>,
    cache: &State<UserCache>,
) -> Option<Json<User>> {
    user_data.id = id;

    // 1. Update the database
    tokio::time::sleep(Duration::from_millis(50)).await; // Simulate DB latency
    let mut db_lock = db.lock().await;
    if db_lock.contains_key(&id) {
        db_lock.insert(id, user_data.clone().into_inner());
    } else {
        return None; // User not found
    }
    drop(db_lock);

    // 2. Invalidate the cache (Cache Invalidation)
    println!("INVALIDATING CACHE for user {}", id);
    let mut cache_lock = cache.lock().await;
    cache_lock.pop(&id);

    Some(user_data)
}

#[launch]
fn rocket() -> _ {
    // --- Initial Mock Data ---
    let mut initial_users = HashMap::new();
    let user_id = Uuid::new_v4();
    initial_users.insert(user_id, User {
        id: user_id,
        email: "admin@example.com".to_string(),
        password_hash: "hashed_password".to_string(),
        role: UserRole::ADMIN,
        is_active: true,
        created_at: Utc::now(),
    });

    // --- State Initialization ---
    let db_state: Db = Mutex::new(initial_users);
    let cache_state: UserCache = Mutex::new(LruCache::new(NonZeroUsize::new(100).unwrap()));

    rocket::build()
        .mount("/", routes![get_user, update_user])
        .manage(db_state)
        .manage(cache_state)
}