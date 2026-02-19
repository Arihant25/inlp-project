// Variation 4: The Modern Async/Modular Developer
// Style: Async handlers, modular routing, and input validation.
// State: `RwLock` for concurrent read/write access in an async context.
// Error Handling: Standardized `ApiResponse<T>` enum wrapper for all responses.
// Validation: Uses the `validator` crate with a custom `ValidatedJson` request guard.

// --- Cargo.toml dependencies ---
// [dependencies]
// rocket = { version = "0.5.0", features = ["json"] }
// serde = { version = "1.0", features = ["derive"] }
// uuid = { version = "1.6", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }
// tokio = { version = "1", features = ["sync"] }
// validator = { version = "0.16", features = ["derive"] }

#[macro_use]
extern crate rocket;

use rocket::data::{Data, ToByteUnit};
use rocket::http::{ContentType, Status};
use rocket::request::{self, FromRequest, Outcome, Request};
use rocket::response::{self, Responder, Response};
use rocket::serde::{json::Json, Deserialize, Serialize};
use rocket::State;
use std::collections::HashMap;
use std::io::Cursor;
use std::sync::Arc;
use tokio::sync::RwLock;
use uuid::Uuid;
use chrono::{DateTime, Utc};
use validator::{Validate, ValidationError};

// --- Domain Model ---
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

// --- DTOs with Validation ---
#[derive(Deserialize, Validate)]
#[serde(crate = "rocket::serde")]
struct CreateUserDto {
    #[validate(email)]
    email: String,
    #[validate(length(min = 8))]
    password: String,
}

#[derive(Deserialize, Validate)]
#[serde(crate = "rocket::serde")]
struct UpdateUserDto {
    #[validate(email)]
    email: Option<String>,
    role: Option<Role>,
    is_active: Option<bool>,
}

// --- Standardized API Response ---
#[derive(Debug, Serialize)]
#[serde(crate = "rocket::serde")]
struct ErrorInfo {
    code: u16,
    message: String,
}

#[derive(Debug)]
enum ApiResponse<T> {
    Success(T),
    Error(ErrorInfo),
}

impl<T: Serialize> ApiResponse<T> {
    fn ok(data: T) -> Self {
        ApiResponse::Success(data)
    }
    fn err(status: Status, message: &str) -> Self {
        ApiResponse::Error(ErrorInfo {
            code: status.code,
            message: message.to_string(),
        })
    }
}

impl<'r, T: Serialize> Responder<'r, 'static> for ApiResponse<T> {
    fn respond_to(self, req: &'r Request<'_>) -> response::Result<'static> {
        match self {
            ApiResponse::Success(data) => Json(data).respond_to(req),
            ApiResponse::Error(err_info) => {
                let body = rocket::serde::json::to_string(&err_info).unwrap();
                Response::build()
                    .status(Status { code: err_info.code })
                    .header(ContentType::JSON)
                    .sized_body(body.len(), Cursor::new(body))
                    .ok()
            }
        }
    }
}

// --- Custom Request Guard for Validation ---
struct ValidatedJson<T>(T);

#[rocket::async_trait]
impl<'r, T: serde::de::DeserializeOwned + Validate> FromRequest<'r> for ValidatedJson<T> {
    type Error = (Status, String);

    async fn from_request(req: &'r Request<'_>) -> request::Outcome<Self, Self::Error> {
        let data: Data<'r> = match req.data(2.mebibytes()).await {
            Ok(d) => d,
            Err(_) => return Outcome::Forward(Status::PayloadTooLarge),
        };
        
        let string = match data.open(2.mebibytes()).into_string().await {
            Ok(s) if s.is_complete() => s.into_inner(),
            _ => return Outcome::Error((Status::UnprocessableEntity, "Failed to read body".into())),
        };

        let value: T = match rocket::serde::json::from_str(&string) {
            Ok(v) => v,
            Err(e) => return Outcome::Error((Status::BadRequest, e.to_string())),
        };

        if let Err(e) = value.validate() {
            return Outcome::Error((Status::UnprocessableEntity, e.to_string()));
        }

        Outcome::Success(ValidatedJson(value))
    }
}

// --- Database & API Module ---
type DbLock = Arc<RwLock<HashMap<Uuid, User>>>;

mod user_api {
    use super::*;

    #[post("/", format = "json", data = "<dto>")]
    pub async fn create_user(dto: ValidatedJson<CreateUserDto>, db: &State<DbLock>) -> ApiResponse<User> {
        let mut users = db.write().await;
        if users.values().any(|u| u.email == dto.0.email) {
            return ApiResponse::err(Status::Conflict, "Email already exists");
        }
        let new_user = User {
            id: Uuid::new_v4(),
            email: dto.0.email,
            password_hash: "hashed_password".to_string(),
            role: Role::USER,
            is_active: true,
            created_at: Utc::now(),
        };
        users.insert(new_user.id, new_user.clone());
        ApiResponse::ok(new_user)
    }

    #[get("/<id>")]
    pub async fn get_user(id: Uuid, db: &State<DbLock>) -> ApiResponse<User> {
        let users = db.read().await;
        match users.get(&id) {
            Some(user) => ApiResponse::ok(user.clone()),
            None => ApiResponse::err(Status::NotFound, "User not found"),
        }
    }

    #[patch("/<id>", format = "json", data = "<dto>")]
    pub async fn update_user(id: Uuid, dto: ValidatedJson<UpdateUserDto>, db: &State<DbLock>) -> ApiResponse<User> {
        let mut users = db.write().await;
        if let Some(user) = users.get_mut(&id) {
            if let Some(email) = dto.0.email { user.email = email; }
            if let Some(role) = dto.0.role { user.role = role; }
            if let Some(is_active) = dto.0.is_active { user.is_active = is_active; }
            ApiResponse::ok(user.clone())
        } else {
            ApiResponse::err(Status::NotFound, "User not found")
        }
    }

    #[delete("/<id>")]
    pub async fn delete_user(id: Uuid, db: &State<DbLock>) -> ApiResponse<()> {
        let mut users = db.write().await;
        if users.remove(&id).is_some() {
            ApiResponse::ok(())
        } else {
            ApiResponse::err(Status::NotFound, "User not found")
        }
    }

    #[get("/")]
    pub async fn list_users(db: &State<DbLock>) -> ApiResponse<Vec<User>> {
        let users = db.read().await;
        ApiResponse::ok(users.values().cloned().collect())
    }

    pub fn routes() -> Vec<rocket::Route> {
        routes![create_user, get_user, update_user, delete_user, list_users]
    }
}

// --- Main Application Setup ---
#[launch]
fn rocket() -> _ {
    let db: DbLock = Arc::new(RwLock::new(HashMap::new()));

    rocket::build()
        .manage(db)
        .mount("/api/v1/users", user_api::routes())
}