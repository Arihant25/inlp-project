// Variation 2: The OOP Enthusiast - Struct-based Handlers
// Groups related handlers as methods on a resource struct.
// Introduces a custom error type that implements ResponseError for cleaner error handling.
//
// Cargo.toml dependencies:
// actix-web = "4"
// serde = { version = "1.0", features = ["derive"] }
// serde_json = "1.0"
// uuid = { version = "1", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }
// tokio = { version = "1", features = ["full"] }

use actix_web::{web, App, HttpResponse, HttpServer, Responder, ResponseError, http::StatusCode};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use uuid::Uuid;
use std::fmt::{Display, Formatter, Result as FmtResult};

// --- Domain Models ---

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
enum UserRole {
    ADMIN,
    USER,
}

#[derive(Debug, Serialize, Clone)]
struct User {
    id: Uuid,
    email: String,
    #[serde(skip_serializing)]
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: DateTime<Utc>,
}

// --- DTOs ---

#[derive(Deserialize)]
struct UserCreateRequest {
    email: String,
    password: String,
}

#[derive(Deserialize)]
struct UserUpdateRequest {
    email: Option<String>,
    role: Option<UserRole>,
    is_active: Option<bool>,
}

#[derive(Deserialize)]
struct UserListParams {
    page: Option<u64>,
    per_page: Option<u64>,
    role: Option<UserRole>,
}

// --- Custom Error Type ---

#[derive(Debug)]
enum ApiError {
    NotFound(String),
    Conflict(String),
    InternalError,
}

impl Display for ApiError {
    fn fmt(&self, f: &mut Formatter) -> FmtResult {
        match self {
            ApiError::NotFound(msg) => write!(f, "{}", msg),
            ApiError::Conflict(msg) => write!(f, "{}", msg),
            ApiError::InternalError => write!(f, "An internal error occurred"),
        }
    }
}

impl ResponseError for ApiError {
    fn status_code(&self) -> StatusCode {
        match self {
            ApiError::NotFound(_) => StatusCode::NOT_FOUND,
            ApiError::Conflict(_) => StatusCode::CONFLICT,
            ApiError::InternalError => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }

    fn error_response(&self) -> HttpResponse {
        HttpResponse::build(self.status_code()).json(serde_json::json!({ "error": self.to_string() }))
    }
}

// --- Application State ---

type UserDb = Arc<Mutex<HashMap<Uuid, User>>>;

// --- Resource/Controller Struct ---

struct UserResource;

impl UserResource {
    async fn create(
        db: web::Data<UserDb>,
        payload: web::Json<UserCreateRequest>,
    ) -> Result<impl Responder, ApiError> {
        let mut users = db.lock().map_err(|_| ApiError::InternalError)?;

        if users.values().any(|u| u.email == payload.email) {
            return Err(ApiError::Conflict("Email already in use".to_string()));
        }

        let new_user = User {
            id: Uuid::new_v4(),
            email: payload.email.clone(),
            password_hash: format!("hashed:{}", payload.password),
            role: UserRole::USER,
            is_active: true,
            created_at: Utc::now(),
        };

        users.insert(new_user.id, new_user.clone());
        Ok(HttpResponse::Created().json(new_user))
    }

    async fn get_by_id(
        db: web::Data<UserDb>,
        user_id: web::Path<Uuid>,
    ) -> Result<impl Responder, ApiError> {
        let users = db.lock().map_err(|_| ApiError::InternalError)?;
        let user = users
            .get(&user_id)
            .cloned()
            .ok_or_else(|| ApiError::NotFound("User not found".to_string()))?;
        Ok(HttpResponse::Ok().json(user))
    }

    async fn update(
        db: web::Data<UserDb>,
        user_id: web::Path<Uuid>,
        payload: web::Json<UserUpdateRequest>,
    ) -> Result<impl Responder, ApiError> {
        let mut users = db.lock().map_err(|_| ApiError::InternalError)?;
        let user = users
            .get_mut(&user_id)
            .ok_or_else(|| ApiError::NotFound("User not found".to_string()))?;

        if let Some(email) = &payload.email {
            user.email = email.clone();
        }
        if let Some(role) = &payload.role {
            user.role = role.clone();
        }
        if let Some(is_active) = payload.is_active {
            user.is_active = is_active;
        }

        Ok(HttpResponse::Ok().json(user.clone()))
    }

    async fn delete(
        db: web::Data<UserDb>,
        user_id: web::Path<Uuid>,
    ) -> Result<impl Responder, ApiError> {
        let mut users = db.lock().map_err(|_| ApiError::InternalError)?;
        if users.remove(&user_id).is_some() {
            Ok(HttpResponse::NoContent().finish())
        } else {
            Err(ApiError::NotFound("User not found".to_string()))
        }
    }

    async fn list(
        db: web::Data<UserDb>,
        params: web::Query<UserListParams>,
    ) -> Result<impl Responder, ApiError> {
        let users = db.lock().map_err(|_| ApiError::InternalError)?;
        
        let mut results: Vec<_> = users.values().cloned().collect();

        if let Some(role_filter) = &params.role {
            results.retain(|u| u.role == *role_filter);
        }

        let page = params.page.unwrap_or(1);
        let per_page = params.per_page.unwrap_or(10);
        let offset = (page - 1) * per_page;

        let paginated_results: Vec<_> = results.into_iter().skip(offset as usize).take(per_page as usize).collect();

        Ok(HttpResponse::Ok().json(paginated_results))
    }
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let mut user_db_init = HashMap::new();
    let user = User {
        id: Uuid::new_v4(),
        email: "test.user@example.com".to_string(),
        password_hash: "hashed_pass".to_string(),
        role: UserRole::USER,
        is_active: false,
        created_at: Utc::now(),
    };
    user_db_init.insert(user.id, user);

    let db: UserDb = Arc::new(Mutex::new(user_db_init));
    let app_data = web::Data::from(db);

    println!("Server running at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(app_data.clone())
            .service(
                web::scope("/users")
                    .route("", web::post().to(UserResource::create))
                    .route("", web::get().to(UserResource::list))
                    .route("/{user_id}", web::get().to(UserResource::get_by_id))
                    .route("/{user_id}", web::put().to(UserResource::update))
                    .route("/{user_id}", web::delete().to(UserResource::delete)),
            )
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}