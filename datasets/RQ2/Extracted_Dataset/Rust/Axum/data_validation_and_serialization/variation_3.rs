/*
Variation 3: The "Service Layer" Approach

This variation introduces a service layer, a common pattern for separating business
logic from web-layer concerns (like request/response handling). Handlers are thin:
they parse, validate, and then delegate to a service. The service is shared via
Axum's state management. This improves testability and maintainability.

Dependencies to add in Cargo.toml:
axum = { version = "0.7", features = ["macros"] }
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1.0"
quick-xml = { version = "0.34", features = ["serialize", "deserialize"] }
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
validator = { version = "0.18", features = ["derive"] }
http = "1.1.0"
thiserror = "1.0"
*/

use axum::{
    extract::State,
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::sync::Arc;
use thiserror::Error;
use uuid::Uuid;
use validator::{Validate, ValidationError, ValidationErrors};

// --- Domain Models ---
#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum UserRole { ADMIN, USER }
#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug, Serialize, Clone)]
pub struct User {
    pub id: Uuid,
    pub email: String,
    #[serde(skip_serializing)]
    pub password_hash: String,
    pub role: UserRole,
    pub is_active: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename = "Post")]
pub struct Post {
    pub id: Uuid,
    pub user_id: Uuid,
    pub title: String,
    pub content: String,
    pub status: PostStatus,
}

// --- DTOs with Validation ---
fn validate_phone_not_empty(phone: &str) -> Result<(), ValidationError> {
    if phone.is_empty() {
        Err(ValidationError::new("phone_required"))
    } else {
        Ok(())
    }
}

#[derive(Deserialize, Validate)]
pub struct CreateUserDto {
    #[validate(email)]
    pub email: String,
    #[validate(length(min = 10))]
    pub password: String,
    #[validate(custom = "validate_phone_not_empty")]
    pub phone: String, // Just checking for non-empty
}

#[derive(Deserialize, Validate)]
#[serde(rename = "CreatePostDto")]
pub struct CreatePostDto {
    pub user_id: Uuid,
    #[validate(length(min = 1))]
    pub title: String,
    pub content: String,
}

// --- Error Handling ---
#[derive(Debug, Error)]
pub enum ServiceError {
    #[error("Validation error")]
    ValidationError(#[from] ValidationErrors),
    #[error("XML processing error: {0}")]
    XmlError(String),
    #[error("User already exists: {0}")]
    UserExists(String),
    #[error("Internal server error")]
    Internal(#[from] anyhow::Error),
}

impl IntoResponse for ServiceError {
    fn into_response(self) -> Response {
        let (status, message) = match self {
            ServiceError::ValidationError(ref e) => (StatusCode::UNPROCESSABLE_ENTITY, serde_json::json!({"errors": e})),
            ServiceError::XmlError(ref e) => (StatusCode::BAD_REQUEST, serde_json::json!({"error": e})),
            ServiceError::UserExists(ref e) => (StatusCode::CONFLICT, serde_json::json!({"error": e})),
            ServiceError::Internal(_) => (StatusCode::INTERNAL_SERVER_ERROR, serde_json::json!({"error": "An internal error occurred"})),
        };
        (status, Json(message)).into_response()
    }
}

// --- Service Layer ---
pub struct UserService;
impl UserService {
    pub fn new() -> Self { Self }
    pub async fn create_user(&self, dto: CreateUserDto) -> Result<User, ServiceError> {
        dto.validate()?;
        // Mock logic: check for existing user
        if dto.email == "admin@example.com" {
            return Err(ServiceError::UserExists(dto.email));
        }
        // Mock password hashing and user creation
        Ok(User {
            id: Uuid::new_v4(),
            email: dto.email,
            password_hash: format!("hashed_{}", dto.password),
            role: UserRole::USER,
            is_active: true,
            created_at: Utc::now(),
        })
    }
}

pub struct PostService;
impl PostService {
    pub fn new() -> Self { Self }
    pub async fn create_post_from_xml(&self, xml_body: &str) -> Result<Post, ServiceError> {
        let dto: CreatePostDto = quick_xml::de::from_str(xml_body)
            .map_err(|e| ServiceError::XmlError(e.to_string()))?;
        dto.validate()?;
        Ok(Post {
            id: Uuid::new_v4(),
            user_id: dto.user_id,
            title: dto.title,
            content: dto.content,
            status: PostStatus::DRAFT,
        })
    }
}

// --- Web Layer (Handlers) ---
async fn handle_create_user(
    State(app_state): State<Arc<AppState>>,
    Json(dto): Json<CreateUserDto>,
) -> Result<impl IntoResponse, ServiceError> {
    let new_user = app_state.user_service.create_user(dto).await?;
    Ok((StatusCode::CREATED, Json(new_user)))
}

async fn handle_create_post(
    State(app_state): State<Arc<AppState>>,
    xml_body: String,
) -> Result<impl IntoResponse, ServiceError> {
    let new_post = app_state.post_service.create_post_from_xml(&xml_body).await?;
    let xml_response = quick_xml::se::to_string(&new_post)
        .map_err(|e| ServiceError::XmlError(e.to_string()))?;
    Ok((
        StatusCode::CREATED,
        [(header::CONTENT_TYPE, "application/xml")],
        xml_response,
    ))
}

// --- Application State and Main ---
struct AppState {
    user_service: UserService,
    post_service: PostService,
}

#[tokio::main]
async fn main() {
    let shared_state = Arc::new(AppState {
        user_service: UserService::new(),
        post_service: PostService::new(),
    });

    let app = Router::new()
        .route("/", get(|| async { "Service Layer API is running" }))
        .route("/users", post(handle_create_user))
        .route("/posts", post(handle_create_post))
        .with_state(shared_state);

    let addr = SocketAddr::from(([127, 0, 0, 1], 3000));
    println!("Service-oriented server listening on http://{}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}