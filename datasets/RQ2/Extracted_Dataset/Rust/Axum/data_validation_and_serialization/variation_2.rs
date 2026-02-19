/*
Variation 2: The "Functional/Handler-centric" Approach

This developer prefers to keep related logic colocated. DTOs (`Request` structs)
are defined directly within the handler modules they are used in. This can make
individual endpoints easier to reason about in isolation, at the cost of some
modularity. Error handling is simpler, using a single `ApiError` type.

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
*/

use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
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

// --- Unified Error Handling ---
struct ApiError(StatusCode, String);

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let status = self.0;
        let body = Json(serde_json::json!({ "error": self.1 }));
        (status, body).into_response()
    }
}

impl From<ValidationErrors> for ApiError {
    fn from(errors: ValidationErrors) -> Self {
        ApiError(
            StatusCode::UNPROCESSABLE_ENTITY,
            format!("Validation failed: {}", errors),
        )
    }
}

impl From<serde_json::Error> for ApiError {
    fn from(err: serde_json::Error) -> Self {
        ApiError(StatusCode::BAD_REQUEST, format!("JSON error: {}", err))
    }
}

impl From<quick_xml::DeError> for ApiError {
    fn from(err: quick_xml::DeError) -> Self {
        ApiError(StatusCode::BAD_REQUEST, format!("XML Deserialization Error: {}", err))
    }
}

impl From<quick_xml::SeError> for ApiError {
    fn from(err: quick_xml::SeError) -> Self {
        ApiError(StatusCode::INTERNAL_SERVER_ERROR, format!("XML Serialization Error: {}", err))
    }
}


// --- User Handler and its specific DTO ---
mod user_handler {
    use super::*;

    fn validate_phone_number(phone: &str) -> Result<(), ValidationError> {
        if phone.len() > 8 && phone.chars().all(|c| c.is_ascii_digit() || c == '+') {
            Ok(())
        } else {
            Err(ValidationError::new("bad_phone"))
        }
    }

    #[derive(Deserialize, Validate)]
    struct CreateUserRequest {
        #[validate(email)]
        email: String,
        #[validate(length(min = 8))]
        password: String,
        #[validate(custom = "validate_phone_number")]
        phone: String,
    }

    pub async fn create_user(
        Json(req): Json<CreateUserRequest>,
    ) -> Result<impl IntoResponse, ApiError> {
        req.validate()?;

        let user = User {
            id: Uuid::new_v4(),
            email: req.email,
            role: UserRole::USER,
            is_active: false,
            created_at: Utc::now(),
        };

        Ok((StatusCode::CREATED, Json(user)))
    }
}

// --- Post Handler and its specific DTO ---
mod post_handler {
    use super::*;
    use axum::http::header;

    #[derive(Deserialize, Validate)]
    #[serde(rename = "CreatePostRequest")]
    struct CreatePostRequest {
        user_id: Uuid,
        #[validate(length(min = 5, max = 255))]
        title: String,
        #[validate(required)]
        content: Option<String>,
    }

    pub async fn create_post(body: String) -> Result<impl IntoResponse, ApiError> {
        let req: CreatePostRequest = quick_xml::de::from_str(&body)?;
        req.validate()?;

        let post = Post {
            id: Uuid::new_v4(),
            user_id: req.user_id,
            title: req.title,
            content: req.content.unwrap_or_default(),
            status: PostStatus::DRAFT,
        };

        let xml_body = quick_xml::se::to_string(&post)?;

        Ok((
            StatusCode::CREATED,
            [(header::CONTENT_TYPE, "application/xml")],
            xml_body,
        ))
    }
}

#[tokio::main]
async fn main() {
    let app = Router::new()
        .route("/", get(|| async { "API is running" }))
        .route("/users", post(user_handler::create_user))
        .route("/posts", post(post_handler::create_post));

    let addr = SocketAddr::from(([127, 0, 0, 1], 3000));
    println!("Functional/Handler-centric server listening on http://{}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}