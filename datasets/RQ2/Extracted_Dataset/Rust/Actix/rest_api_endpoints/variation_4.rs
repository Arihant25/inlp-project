// Variation 4: The Async Purist - Modern & Concurrent
// Uses DashMap for fine-grained, concurrent state management, avoiding broad Mutex locks.
// Integrates `validator` for DTO validation and `thiserror` for clean, idiomatic error handling.
//
// Cargo.toml dependencies:
// actix-web = "4"
// serde = { version = "1.0", features = ["derive"] }
// serde_json = "1.0"
// uuid = { version = "1", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }
// tokio = { version = "1", features = ["full"] }
// dashmap = "5"
// validator = { version = "0.16", features = ["derive"] }
// thiserror = "1.0"

use actix_web::{web, App, HttpResponse, HttpServer, Responder, ResponseError, http::StatusCode};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use thiserror::Error;
use uuid::Uuid;
use validator::{Validate, ValidationError};

// --- Domain Models ---

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
enum Role {
    ADMIN,
    USER,
}

#[derive(Debug, Serialize, Clone)]
struct User {
    id: Uuid,
    email: String,
    #[serde(skip_serializing)]
    password_hash: String,
    role: Role,
    is_active: bool,
    created_at: DateTime<Utc>,
}

// --- Application State ---

type AppState = web::Data<Arc<DashMap<Uuid, User>>>;

// --- Custom Error Handling with `thiserror` ---

#[derive(Error, Debug)]
enum ApiError {
    #[error("Validation error: {0}")]
    Validation(String),
    #[error("User not found: {0}")]
    NotFound(Uuid),
    #[error("Email already exists: {0}")]
    Conflict(String),
    #[error("Internal server error")]
    Internal,
}

impl ResponseError for ApiError {
    fn status_code(&self) -> StatusCode {
        match self {
            ApiError::Validation(_) => StatusCode::BAD_REQUEST,
            ApiError::NotFound(_) => StatusCode::NOT_FOUND,
            ApiError::Conflict(_) => StatusCode::CONFLICT,
            ApiError::Internal => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }
    fn error_response(&self) -> HttpResponse {
        HttpResponse::build(self.status_code()).json(serde_json::json!({ "error": self.to_string() }))
    }
}

// --- DTOs with Validation ---

#[derive(Deserialize, Validate)]
struct CreateUserRequest {
    #[validate(email(message = "Must be a valid email address"))]
    email: String,
    #[validate(length(min = 8, message = "Password must be at least 8 characters long"))]
    password: String,
}

#[derive(Deserialize, Validate)]
struct UpdateUserRequest {
    #[validate(email(message = "Must be a valid email address"))]
    email: Option<String>,
    role: Option<Role>,
    is_active: Option<bool>,
}

#[derive(Deserialize)]
struct SearchParams {
    page: Option<u32>,
    limit: Option<u32>,
    is_active: Option<bool>,
}

// --- API Handlers ---

async fn create_user_endpoint(
    state: AppState,
    payload: web::Json<CreateUserRequest>,
) -> Result<impl Responder, ApiError> {
    payload.validate().map_err(|e| ApiError::Validation(e.to_string()))?;

    if state.iter().any(|entry| entry.value().email == payload.email) {
        return Err(ApiError::Conflict(payload.email.clone()));
    }

    let new_user = User {
        id: Uuid::new_v4(),
        email: payload.email.clone(),
        password_hash: format!("super_secret_hash_for_{}", payload.password),
        role: Role::USER,
        is_active: true,
        created_at: Utc::now(),
    };

    state.insert(new_user.id, new_user.clone());
    Ok(HttpResponse::Created().json(new_user))
}

async fn get_user_endpoint(state: AppState, user_id: web::Path<Uuid>) -> Result<impl Responder, ApiError> {
    let id = user_id.into_inner();
    match state.get(&id) {
        Some(user_ref) => Ok(HttpResponse::Ok().json(user_ref.value().clone())),
        None => Err(ApiError::NotFound(id)),
    }
}

async fn update_user_endpoint(
    state: AppState,
    user_id: web::Path<Uuid>,
    payload: web::Json<UpdateUserRequest>,
) -> Result<impl Responder, ApiError> {
    payload.validate().map_err(|e| ApiError::Validation(e.to_string()))?;
    let id = user_id.into_inner();

    let mut user = state.get_mut(&id).ok_or(ApiError::NotFound(id))?;

    if let Some(email) = &payload.email {
        user.email = email.clone();
    }
    if let Some(role) = &payload.role {
        user.role = role.clone();
    }
    if let Some(is_active) = payload.is_active {
        user.is_active = is_active;
    }

    Ok(HttpResponse::Ok().json(user.value().clone()))
}

async fn delete_user_endpoint(state: AppState, user_id: web::Path<Uuid>) -> Result<impl Responder, ApiError> {
    let id = user_id.into_inner();
    if state.remove(&id).is_some() {
        Ok(HttpResponse::NoContent().finish())
    } else {
        Err(ApiError::NotFound(id))
    }
}

async fn list_users_endpoint(
    state: AppState,
    query: web::Query<SearchParams>,
) -> Result<impl Responder, ApiError> {
    let users: Vec<User> = state
        .iter()
        .filter_map(|entry| {
            let user = entry.value();
            if query.is_active.is_none() || query.is_active == Some(user.is_active) {
                Some(user.clone())
            } else {
                None
            }
        })
        .collect();

    let page = query.page.unwrap_or(1);
    let limit = query.limit.unwrap_or(10);
    let offset = (page - 1) * limit;

    let paginated_users: Vec<_> = users.into_iter().skip(offset as usize).take(limit as usize).collect();

    Ok(HttpResponse::Ok().json(paginated_users))
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let db: Arc<DashMap<Uuid, User>> = Arc::new(DashMap::new());
    
    // Pre-populate with some data
    let user_one_id = Uuid::new_v4();
    db.insert(user_one_id, User {
        id: user_one_id,
        email: "jane.doe@example.com".to_string(),
        password_hash: "secret1".to_string(),
        role: Role::USER,
        is_active: true,
        created_at: Utc::now(),
    });

    let app_state = web::Data::new(db);

    println!("Server running at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(app_state.clone())
            .service(
                web::scope("/users")
                    .route("", web::post().to(create_user_endpoint))
                    .route("", web::get().to(list_users_endpoint))
                    .route("/{user_id}", web::get().to(get_user_endpoint))
                    .route("/{user_id}", web::put().to(update_user_endpoint))
                    .route("/{user_id}", web::delete().to(delete_user_endpoint)),
            )
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}