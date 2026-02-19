// Variation 3: The Type-Safe Purist
// Style: Emphasizes strong typing with newtypes (e.g., `UserId(Uuid)`).
// State: A dedicated `Repository` struct wrapping a `DashMap` for fine-grained locking.
// Error Handling: Uses `thiserror` for a rich error type with a `Responder` implementation.
// DTOs: Explicit request and response DTOs to separate API contract from domain model.

// --- Cargo.toml dependencies ---
// [dependencies]
// rocket = { version = "0.5.0", features = ["json"] }
// serde = { version = "1.0", features = ["derive"] }
// uuid = { version = "1.6", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }
// dashmap = "5.5"
// thiserror = "1.0"

#[macro_use]
extern crate rocket;

use rocket::http::{ContentType, Status};
use rocket::request::{self, FromParam};
use rocket::response::{self, Responder, Response};
use rocket::serde::{json::Json, Deserialize, Serialize};
use rocket::State;
use std::io::Cursor;
use std::ops::Deref;
use uuid::Uuid;
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use thiserror::Error;

// --- Type-Safe IDs ---
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
#[serde(crate = "rocket::serde")]
pub struct UserId(Uuid);

impl<'a> FromParam<'a> for UserId {
    type Error = uuid::Error;
    fn from_param(param: &'a str) -> Result<Self, Self::Error> {
        Uuid::parse_str(param).map(UserId)
    }
}

// --- Domain Model ---
#[derive(Debug, Clone)]
pub enum Role { ADMIN, USER }

#[derive(Debug, Clone)]
pub struct User {
    id: UserId,
    email: String,
    password_hash: String,
    role: Role,
    is_active: bool,
    created_at: DateTime<Utc>,
}

// --- API DTOs (Request/Response) ---
#[derive(Deserialize)]
#[serde(crate = "rocket::serde")]
pub struct CreateUserRequest<'r> {
    email: &'r str,
    password: &'r str,
}

#[derive(Deserialize)]
#[serde(crate = "rocket::serde")]
pub struct UpdateUserRequest {
    is_active: Option<bool>,
}

#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
pub struct UserResponse {
    id: UserId,
    email: String,
    is_active: bool,
    created_at: DateTime<Utc>,
}

// --- Error Handling with `thiserror` ---
#[derive(Error, Debug)]
pub enum ApiError {
    #[error("resource not found")]
    NotFound,
    #[error("email already exists")]
    Conflict,
    #[error("internal server error")]
    Internal,
}

impl<'r> Responder<'r, 'static> for ApiError {
    fn respond_to(self, _: &'r request::Request<'_>) -> response::Result<'static> {
        let (status, message) = match self {
            ApiError::NotFound => (Status::NotFound, self.to_string()),
            ApiError::Conflict => (Status::Conflict, self.to_string()),
            ApiError::Internal => (Status::InternalServerError, self.to_string()),
        };
        let body = format!("{{\"error\":\"{}\"}}", message);
        Response::build()
            .status(status)
            .header(ContentType::JSON)
            .sized_body(body.len(), Cursor::new(body))
            .ok()
    }
}

// --- Repository Layer ---
#[derive(Default)]
pub struct UserRepository(DashMap<UserId, User>);

impl UserRepository {
    fn insert(&self, user: User) -> Result<User, ApiError> {
        if self.0.iter().any(|entry| entry.value().email == user.email) {
            return Err(ApiError::Conflict);
        }
        self.0.insert(user.id, user.clone());
        Ok(user)
    }

    fn find(&self, id: UserId) -> Option<impl Deref<Target = User> + '_> {
        self.0.get(&id)
    }
    
    fn update(&self, id: UserId, data: UpdateUserRequest) -> Result<User, ApiError> {
        let mut user = self.0.get_mut(&id).ok_or(ApiError::NotFound)?;
        if let Some(is_active) = data.is_active {
            user.is_active = is_active;
        }
        Ok(user.clone())
    }

    fn delete(&self, id: UserId) -> Result<(), ApiError> {
        self.0.remove(&id).ok_or(ApiError::NotFound).map(|_| ())
    }

    fn list_all(&self) -> Vec<User> {
        self.0.iter().map(|entry| entry.value().clone()).collect()
    }
}

// --- Conversion Logic ---
impl From<User> for UserResponse {
    fn from(user: User) -> Self {
        UserResponse {
            id: user.id,
            email: user.email,
            is_active: user.is_active,
            created_at: user.created_at,
        }
    }
}

// --- API Handlers ---
type ApiResult<T> = Result<T, ApiError>;

#[post("/users", format = "json", data = "<req>")]
fn create_user(req: Json<CreateUserRequest<'_>>, repo: &State<UserRepository>) -> ApiResult<status::Created<Json<UserResponse>>> {
    let new_user = User {
        id: UserId(Uuid::new_v4()),
        email: req.email.to_string(),
        password_hash: "hashed_password".to_string(),
        role: Role::USER,
        is_active: true,
        created_at: Utc::now(),
    };
    let created_user = repo.insert(new_user)?;
    let response = UserResponse::from(created_user);
    let location = format!("/users/{}", response.id.0);
    Ok(status::Created::new(location).body(Json(response)))
}

#[get("/users/<id>")]
fn get_user(id: UserId, repo: &State<UserRepository>) -> ApiResult<Json<UserResponse>> {
    repo.find(id)
        .map(|user| Json(UserResponse::from(user.clone())))
        .ok_or(ApiError::NotFound)
}

#[patch("/users/<id>", format = "json", data = "<req>")]
fn update_user(id: UserId, req: Json<UpdateUserRequest>, repo: &State<UserRepository>) -> ApiResult<Json<UserResponse>> {
    let updated_user = repo.update(id, req.into_inner())?;
    Ok(Json(UserResponse::from(updated_user)))
}

#[delete("/users/<id>")]
fn delete_user(id: UserId, repo: &State<UserRepository>) -> ApiResult<Status> {
    repo.delete(id).map(|_| Status::NoContent)
}

#[get("/users")]
fn list_users(repo: &State<UserRepository>) -> Json<Vec<UserResponse>> {
    let users = repo.list_all();
    let response: Vec<UserResponse> = users.into_iter().map(UserResponse::from).collect();
    Json(response)
}

// --- Main Application Setup ---
#[launch]
fn rocket() -> _ {
    let user_repo = UserRepository::default();
    // Seed with some data
    let seed_user = User {
        id: UserId(Uuid::new_v4()),
        email: "seed@example.com".to_string(),
        password_hash: "secret".to_string(),
        role: Role::ADMIN,
        is_active: true,
        created_at: Utc::now(),
    };
    user_repo.0.insert(seed_user.id, seed_user);

    rocket::build()
        .manage(user_repo)
        .mount("/", routes![
            create_user,
            get_user,
            update_user,
            delete_user,
            list_users
        ])
}