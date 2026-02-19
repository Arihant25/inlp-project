/*
Variation 1: The "Standard" Modular Approach

This implementation follows a classic, clean, and modular structure.
Logic is separated into distinct modules: `models`, `dtos`, `handlers`, and `errors`.
This is a common and scalable pattern for medium to large applications.

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
    async_trait,
    body::Body,
    extract::{FromRequest, Request},
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use uuid::Uuid;
use validator::{Validate, ValidationError};

// -----------------
// 1. Error Module
// -----------------
mod errors {
    use super::*;
    use validator::ValidationErrors;

    #[derive(Debug)]
    pub enum AppError {
        Validation(ValidationErrors),
        XmlProcessing(String),
        BadRequest(String),
    }

    #[derive(Serialize)]
    struct ErrorResponse {
        status: u16,
        message: String,
        details: Option<serde_json::Value>,
    }

    impl IntoResponse for AppError {
        fn into_response(self) -> Response {
            let (status, message, details) = match self {
                AppError::Validation(e) => (
                    StatusCode::UNPROCESSABLE_ENTITY,
                    "Input validation failed".to_string(),
                    Some(serde_json::json!(e)),
                ),
                AppError::XmlProcessing(e) => (
                    StatusCode::BAD_REQUEST,
                    "XML processing error".to_string(),
                    Some(serde_json::json!({"error": e})),
                ),
                AppError::BadRequest(e) => (
                    StatusCode::BAD_REQUEST,
                    "Bad Request".to_string(),
                    Some(serde_json::json!({"error": e})),
                ),
            };

            let body = Json(ErrorResponse {
                status: status.as_u16(),
                message,
                details,
            });

            (status, body).into_response()
        }
    }

    impl From<ValidationErrors> for AppError {
        fn from(err: ValidationErrors) -> Self {
            AppError::Validation(err)
        }
    }
}

// -----------------
// 2. Models Module
// -----------------
mod models {
    use super::*;

    #[derive(Debug, Serialize, Deserialize)]
    pub enum UserRole {
        ADMIN,
        USER,
    }

    #[derive(Debug, Serialize, Deserialize)]
    pub enum PostStatus {
        DRAFT,
        PUBLISHED,
    }

    #[derive(Debug, Serialize)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        #[serde(skip_serializing)]
        pub password_hash: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Serialize, Deserialize)]
    #[serde(rename = "Post")]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }
}

// -----------------
// 3. DTOs Module
// -----------------
mod dtos {
    use super::*;
    use models::{PostStatus, UserRole};

    // Custom validator for a simple phone number format (e.g., +1234567890)
    pub fn validate_phone(phone: &str) -> Result<(), ValidationError> {
        if phone.starts_with('+') && phone[1..].chars().all(char::is_numeric) && phone.len() > 8 {
            Ok(())
        } else {
            Err(ValidationError::new("invalid_phone_format"))
        }
    }

    #[derive(Deserialize, Validate)]
    pub struct CreateUserPayload {
        #[validate(email)]
        pub email: String,
        #[validate(length(min = 8, message = "Password must be at least 8 characters long"))]
        pub password: String,
        #[validate(custom = "dtos::validate_phone")]
        pub phone: String,
        pub role: Option<UserRole>,
    }

    #[derive(Deserialize, Validate, Debug)]
    #[serde(rename = "CreatePost")]
    pub struct CreatePostPayload {
        pub user_id: Uuid,
        #[validate(length(min = 3, max = 100))]
        pub title: String,
        #[validate(length(min = 10))]
        pub content: String,
        pub status: Option<PostStatus>,
    }
}

// -----------------
// 4. Handlers Module
// -----------------
mod handlers {
    use super::*;
    use dtos::{CreatePostPayload, CreateUserPayload};
    use errors::AppError;
    use models::{Post, PostStatus, User, UserRole};

    pub async fn create_user_json(
        Json(payload): Json<CreateUserPayload>,
    ) -> Result<impl IntoResponse, AppError> {
        payload.validate()?;

        // In a real app, you would hash the password and save to DB
        let new_user = User {
            id: Uuid::new_v4(),
            email: payload.email,
            password_hash: "hashed_password_goes_here".to_string(),
            role: payload.role.unwrap_or(UserRole::USER),
            is_active: true,
            created_at: Utc::now(),
        };

        Ok((StatusCode::CREATED, Json(new_user)))
    }

    // Custom extractor for XML body
    pub struct Xml<T>(pub T);

    #[async_trait]
    impl<S, T> FromRequest<S> for Xml<T>
    where
        S: Send + Sync,
        T: for<'de> Deserialize<'de>,
    {
        type Rejection = AppError;

        async fn from_request(req: Request, state: &S) -> Result<Self, Self::Rejection> {
            let body = String::from_request(req, state)
                .await
                .map_err(|e| AppError::BadRequest(e.to_string()))?;
            
            let data: T = quick_xml::de::from_str(&body)
                .map_err(|e| AppError::XmlProcessing(e.to_string()))?;
            
            Ok(Xml(data))
        }
    }

    pub async fn create_post_xml(
        Xml(payload): Xml<CreatePostPayload>,
    ) -> Result<impl IntoResponse, AppError> {
        payload.validate()?;

        let new_post = Post {
            id: Uuid::new_v4(),
            user_id: payload.user_id,
            title: payload.title,
            content: payload.content,
            status: payload.status.unwrap_or(PostStatus::DRAFT),
        };

        let xml_response = quick_xml::se::to_string(&new_post)
            .map_err(|e| AppError::XmlProcessing(e.to_string()))?;

        Ok((
            StatusCode::CREATED,
            [(header::CONTENT_TYPE, "application/xml")],
            xml_response,
        ))
    }

    pub async fn health_check() -> &'static str {
        "OK"
    }
}

// -----------------
// 5. Main Application
// -----------------
#[tokio::main]
async fn main() {
    let app = Router::new()
        .route("/", get(handlers::health_check))
        .route("/users", post(handlers::create_user_json))
        .route("/posts", post(handlers::create_post_xml));

    let addr = SocketAddr::from(([127, 0, 0, 1], 3000));
    println!("Listening on http://{}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}