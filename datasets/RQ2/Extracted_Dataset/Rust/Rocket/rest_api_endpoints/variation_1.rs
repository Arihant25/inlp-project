// Variation 1: The Straightforward Functionalist
// Style: Simple, functional, all handlers in one module.
// State: `State<Mutex<HashMap>>` for a mock DB.
// Error Handling: Uses Rocket's built-in responders like `Option` and `status`.
// DTOs: Explicit DTOs for create/update payloads.

// --- Cargo.toml dependencies ---
// [dependencies]
// rocket = { version = "0.5.0", features = ["json"] }
// serde = { version = "1.0", features = ["derive"] }
// uuid = { version = "1.6", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }

#[macro_use]
extern crate rocket;

use rocket::serde::{json::Json, Deserialize, Serialize};
use rocket::http::Status;
use rocket::response::status;
use rocket::State;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use uuid::Uuid;
use chrono::{DateTime, Utc};

// --- 1. Models ---

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(crate = "rocket::serde")]
pub enum Role {
    ADMIN,
    USER,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(crate = "rocket::serde")]
pub struct User {
    id: Uuid,
    email: String,
    #[serde(skip_serializing)]
    password_hash: String,
    role: Role,
    is_active: bool,
    created_at: DateTime<Utc>,
}

// --- 2. DTOs (Data Transfer Objects) for Payloads ---

#[derive(Deserialize)]
#[serde(crate = "rocket::serde")]
struct CreateUserPayload {
    email: String,
    password: String,
}

#[derive(Deserialize)]
#[serde(crate = "rocket::serde")]
struct UpdateUserPayload {
    email: Option<String>,
    role: Option<Role>,
    is_active: Option<bool>,
}

// --- 3. Query Parameters for Pagination & Filtering ---

#[derive(FromForm)]
struct ListUsersQuery<'r> {
    page: Option<usize>,
    limit: Option<usize>,
    email: Option<&'r str>,
    is_active: Option<bool>,
}

// --- 4. Database Abstraction ---

type UserDb = Arc<Mutex<HashMap<Uuid, User>>>;

// --- 5. API Routes / Handlers ---

#[post("/users", format = "json", data = "<payload>")]
fn create_user(payload: Json<CreateUserPayload>, db: &State<UserDb>) -> status::Created<Json<User>> {
    let mut users = db.lock().unwrap();
    
    // Simple check for existing email
    if users.values().any(|u| u.email == payload.email) {
        // In a real app, this would be a proper Conflict response.
        // For simplicity, we'll panic, but a better way is a custom Responder.
        panic!("Email already exists"); 
    }

    let new_user = User {
        id: Uuid::new_v4(),
        email: payload.email.clone(),
        // In a real app, hash the password properly.
        password_hash: format!("hashed_{}", payload.password),
        role: Role::USER,
        is_active: true,
        created_at: Utc::now(),
    };

    users.insert(new_user.id, new_user.clone());

    let location = format!("/users/{}", new_user.id);
    status::Created::new(location).body(Json(new_user))
}

#[get("/users/<id>")]
fn get_user_by_id(id: Uuid, db: &State<UserDb>) -> Option<Json<User>> {
    let users = db.lock().unwrap();
    users.get(&id).cloned().map(Json)
}

#[patch("/users/<id>", format = "json", data = "<payload>")]
fn update_user(id: Uuid, payload: Json<UpdateUserPayload>, db: &State<UserDb>) -> Option<Json<User>> {
    let mut users = db.lock().unwrap();
    if let Some(user) = users.get_mut(&id) {
        if let Some(email) = &payload.email {
            user.email = email.clone();
        }
        if let Some(role) = &payload.role {
            user.role = role.clone();
        }
        if let Some(is_active) = payload.is_active {
            user.is_active = is_active;
        }
        Some(Json(user.clone()))
    } else {
        None
    }
}

#[delete("/users/<id>")]
fn delete_user(id: Uuid, db: &State<UserDb>) -> Status {
    let mut users = db.lock().unwrap();
    if users.remove(&id).is_some() {
        Status::NoContent
    } else {
        Status::NotFound
    }
}

#[get("/users")]
fn list_users(query: ListUsersQuery, db: &State<UserDb>) -> Json<Vec<User>> {
    let users = db.lock().unwrap();
    
    let page = query.page.unwrap_or(1);
    let limit = query.limit.unwrap_or(10);
    let skip = (page - 1) * limit;

    let filtered_users: Vec<User> = users
        .values()
        .filter(|user| query.email.map_or(true, |email| user.email.contains(email)))
        .filter(|user| query.is_active.map_or(true, |active| user.is_active == active))
        .cloned()
        .collect();

    let paginated_users = filtered_users.into_iter().skip(skip).take(limit).collect();
    
    Json(paginated_users)
}

// --- 6. Main Application Setup ---

#[launch]
fn rocket() -> _ {
    // Create a mock in-memory database
    let db: UserDb = Arc::new(Mutex::new(HashMap::new()));

    // Add a sample user for testing
    {
        let mut users = db.lock().unwrap();
        let sample_id = Uuid::new_v4();
        users.insert(sample_id, User {
            id: sample_id,
            email: "test@example.com".to_string(),
            password_hash: "hashed_password".to_string(),
            role: Role::ADMIN,
            is_active: true,
            created_at: Utc::now(),
        });
    }

    rocket::build()
        .manage(db)
        .mount("/", routes![
            create_user,
            get_user_by_id,
            update_user,
            delete_user,
            list_users
        ])
}