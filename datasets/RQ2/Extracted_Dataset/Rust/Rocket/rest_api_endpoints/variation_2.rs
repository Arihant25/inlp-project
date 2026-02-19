// Variation 2: The OOP/Service Layer Enthusiast
// Style: Business logic encapsulated in a `UserService`. Handlers are thin wrappers.
// State: `State<UserService>` holds the application logic and data layer.
// Error Handling: Custom `ApiError` enum with a `Responder` implementation for consistent JSON errors.
// Naming: Method-style calls like `user_service.find(id)`.

// --- Cargo.toml dependencies ---
// [dependencies]
// rocket = { version = "0.5.0", features = ["json"] }
// serde = { version = "1.0", features = ["derive"] }
// uuid = { version = "1.6", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }

#[macro_use]
extern crate rocket;

use rocket::http::{ContentType, Status};
use rocket::request::Request;
use rocket::response::{self, Responder, Response};
use rocket::serde::{json::Json, Deserialize, Serialize};
use rocket::State;
use std::collections::HashMap;
use std::io::Cursor;
use std::sync::{Arc, Mutex};
use uuid::Uuid;
use chrono::{DateTime, Utc};

// --- Domain Models ---
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(crate = "rocket::serde")]
pub enum Role { ADMIN, USER }

#[derive(Debug, Clone, Serialize)]
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

// --- DTOs for input ---
#[derive(Deserialize)]
#[serde(crate = "rocket::serde")]
pub struct CreateUserData {
    email: String,
    password: String,
}

#[derive(Deserialize, AsChangeset)]
#[serde(crate = "rocket::serde")]
pub struct UpdateUserData {
    email: Option<String>,
    role: Option<Role>,
    is_active: Option<bool>,
}
// Dummy derive for AsChangeset
pub trait AsChangeset {}
impl AsChangeset for UpdateUserData {}

// --- Custom Error Handling ---
#[derive(Debug, Serialize)]
#[serde(crate = "rocket::serde")]
struct ErrorResponse {
    message: String,
}

#[derive(Debug)]
enum ApiError {
    NotFound(String),
    Conflict(String),
    InternalError(String),
}

impl<'r> Responder<'r, 'static> for ApiError {
    fn respond_to(self, _: &'r Request<'_>) -> response::Result<'static> {
        let (status, message) = match self {
            ApiError::NotFound(msg) => (Status::NotFound, msg),
            ApiError::Conflict(msg) => (Status::Conflict, msg),
            ApiError::InternalError(msg) => (Status::InternalServerError, msg),
        };

        let body = rocket::serde::json::to_string(&ErrorResponse { message }).unwrap();
        
        Response::build()
            .status(status)
            .header(ContentType::JSON)
            .sized_body(body.len(), Cursor::new(body))
            .ok()
    }
}

// --- Service Layer ---
type Db = Arc<Mutex<HashMap<Uuid, User>>>;

struct UserService {
    db: Db,
}

impl UserService {
    fn new(db: Db) -> Self {
        UserService { db }
    }

    fn find(&self, id: Uuid) -> Result<User, ApiError> {
        let db_lock = self.db.lock().map_err(|e| ApiError::InternalError(e.to_string()))?;
        db_lock.get(&id).cloned().ok_or_else(|| ApiError::NotFound(format!("User with ID {} not found", id)))
    }

    fn create(&self, data: CreateUserData) -> Result<User, ApiError> {
        let mut db_lock = self.db.lock().map_err(|e| ApiError::InternalError(e.to_string()))?;
        if db_lock.values().any(|u| u.email == data.email) {
            return Err(ApiError::Conflict("Email already in use".to_string()));
        }
        let new_user = User {
            id: Uuid::new_v4(),
            email: data.email,
            password_hash: format!("hashed_{}", data.password), // Placeholder for hashing
            role: Role::USER,
            is_active: true,
            created_at: Utc::now(),
        };
        db_lock.insert(new_user.id, new_user.clone());
        Ok(new_user)
    }

    fn update(&self, id: Uuid, data: UpdateUserData) -> Result<User, ApiError> {
        let mut db_lock = self.db.lock().map_err(|e| ApiError::InternalError(e.to_string()))?;
        let user = db_lock.get_mut(&id).ok_or_else(|| ApiError::NotFound(format!("User with ID {} not found", id)))?;
        
        if let Some(email) = data.email { user.email = email; }
        if let Some(role) = data.role { user.role = role; }
        if let Some(is_active) = data.is_active { user.is_active = is_active; }
        
        Ok(user.clone())
    }

    fn delete(&self, id: Uuid) -> Result<(), ApiError> {
        let mut db_lock = self.db.lock().map_err(|e| ApiError::InternalError(e.to_string()))?;
        if db_lock.remove(&id).is_some() {
            Ok(())
        } else {
            Err(ApiError::NotFound(format!("User with ID {} not found", id)))
        }
    }

    fn list(&self, page: usize, limit: usize) -> Result<Vec<User>, ApiError> {
        let db_lock = self.db.lock().map_err(|e| ApiError::InternalError(e.to_string()))?;
        let users = db_lock.values()
            .cloned()
            .skip((page.saturating_sub(1)) * limit)
            .take(limit)
            .collect();
        Ok(users)
    }
}

// --- API Handlers (Thin Wrappers) ---
type ApiResult<T> = Result<T, ApiError>;

#[post("/users", format = "json", data = "<user_data>")]
fn create_user_handler(user_data: Json<CreateUserData>, service: &State<UserService>) -> ApiResult<status::Created<Json<User>>> {
    let new_user = service.create(user_data.into_inner())?;
    let location = format!("/users/{}", new_user.id);
    Ok(status::Created::new(location).body(Json(new_user)))
}

#[get("/users/<id>")]
fn get_user_handler(id: Uuid, service: &State<UserService>) -> ApiResult<Json<User>> {
    service.find(id).map(Json)
}

#[patch("/users/<id>", format = "json", data = "<user_data>")]
fn update_user_handler(id: Uuid, user_data: Json<UpdateUserData>, service: &State<UserService>) -> ApiResult<Json<User>> {
    service.update(id, user_data.into_inner()).map(Json)
}

#[delete("/users/<id>")]
fn delete_user_handler(id: Uuid, service: &State<UserService>) -> ApiResult<Status> {
    service.delete(id)?;
    Ok(Status::NoContent)
}

#[get("/users?<page>&<limit>")]
fn list_users_handler(page: Option<usize>, limit: Option<usize>, service: &State<UserService>) -> ApiResult<Json<Vec<User>>> {
    let users = service.list(page.unwrap_or(1), limit.unwrap_or(10))?;
    Ok(Json(users))
}

// --- Main Application Setup ---
#[launch]
fn rocket() -> _ {
    let db: Db = Arc::new(Mutex::new(HashMap::new()));
    let user_service = UserService::new(db);

    rocket::build()
        .manage(user_service)
        .mount("/", routes![
            create_user_handler,
            get_user_handler,
            update_user_handler,
            delete_user_handler,
            list_users_handler
        ])
}