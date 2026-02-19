/*
Variation 4: The "State-Driven & Verbose" Approach

This developer prefers explicitness and advanced framework features. They use custom
extractors (`ValidatedJson`, `ValidatedXml`) to handle validation before the handler
is even called. This keeps handlers clean and focused purely on business logic. Error
handling is managed through custom "rejections". Variable names are verbose for clarity.
The application state is more structured.

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
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
*/

use axum::{
    async_trait,
    body::Bytes,
    extract::{FromRequest, Request},
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use chrono::{DateTime, Utc};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use std::net::SocketAddr;
use uuid::Uuid;
use validator::{Validate, ValidationError, ValidationErrors};

// --- Core Data Models ---
mod data_models {
    use super::*;

    #[derive(Debug, Serialize, Deserialize, Clone, Copy)]
    pub enum UserRole { ADMIN, USER }
    #[derive(Debug, Serialize, Deserialize, Clone, Copy)]
    pub enum PostStatus { DRAFT, PUBLISHED }

    #[derive(Debug, Serialize)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
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

    // --- DTOs ---
    fn is_strong_password(password: &str) -> Result<(), ValidationError> {
        if password.len() >= 12 && password.chars().any(char::is_uppercase) && password.chars().any(char::is_numeric) {
            Ok(())
        } else {
            let mut err = ValidationError::new("weak_password");
            err.message = Some("Password must be 12+ chars with uppercase and numbers.".into());
            Err(err)
        }
    }

    #[derive(Deserialize, Validate)]
    pub struct UserCreationRequest {
        #[validate(email(message = "A valid email address is required."))]
        pub email_address: String,
        #[validate(custom(function = "is_strong_password"))]
        pub password_plaintext: String,
        #[validate(phone)]
        pub contact_phone: String,
        pub assigned_role: Option<UserRole>,
    }

    #[derive(Deserialize, Validate)]
    #[serde(rename = "PostCreationRequest")]
    pub struct PostCreationRequest {
        pub author_user_id: Uuid,
        #[validate(length(min = 1, message = "Title cannot be empty."))]
        pub post_title: String,
        #[validate(length(min = 1, message = "Content cannot be empty."))]
        pub post_content: String,
    }
}

// --- Custom Rejections and Error Handling ---
mod error_handling {
    use super::*;

    pub enum ApiRejection {
        ValidationFailed(ValidationErrors),
        DeserializationFailed(String),
    }

    impl IntoResponse for ApiRejection {
        fn into_response(self) -> Response {
            #[derive(Serialize)]
            struct ErrorPayload {
                error_code: &'static str,
                message: String,
                details: serde_json::Value,
            }

            let (status, payload) = match self {
                ApiRejection::ValidationFailed(errors) => (
                    StatusCode::UNPROCESSABLE_ENTITY,
                    ErrorPayload {
                        error_code: "VALIDATION_ERROR",
                        message: "One or more fields failed validation.".to_string(),
                        details: serde_json::json!(errors),
                    },
                ),
                ApiRejection::DeserializationFailed(error_message) => (
                    StatusCode::BAD_REQUEST,
                    ErrorPayload {
                        error_code: "DESERIALIZATION_ERROR",
                        message: "The request body could not be parsed.".to_string(),
                        details: serde_json::json!({ "source_error": error_message }),
                    },
                ),
            };
            (status, Json(payload)).into_response()
        }
    }
}

// --- Custom Extractors for Validation ---
mod custom_extractors {
    use super::*;
    use data_models::*;
    use error_handling::ApiRejection;

    // Extractor for JSON
    #[derive(Debug, Clone, Copy, Default)]
    pub struct ValidatedJson<T>(pub T);

    #[async_trait]
    impl<S, T> FromRequest<S> for ValidatedJson<T>
    where
        S: Send + Sync,
        T: DeserializeOwned + Validate,
    {
        type Rejection = ApiRejection;

        async fn from_request(req: Request, state: &S) -> Result<Self, Self::Rejection> {
            let Json(value) = Json::<T>::from_request(req, state)
                .await
                .map_err(|e| ApiRejection::DeserializationFailed(e.to_string()))?;
            value
                .validate()
                .map_err(ApiRejection::ValidationFailed)?;
            Ok(ValidatedJson(value))
        }
    }

    // Extractor for XML
    #[derive(Debug, Clone, Copy, Default)]
    pub struct ValidatedXml<T>(pub T);

    #[async_trait]
    impl<S, T> FromRequest<S> for ValidatedXml<T>
    where
        S: Send + Sync,
        T: DeserializeOwned + Validate,
    {
        type Rejection = ApiRejection;

        async fn from_request(req: Request, state: &S) -> Result<Self, Self::Rejection> {
            let bytes = Bytes::from_request(req, state)
                .await
                .map_err(|e| ApiRejection::DeserializationFailed(e.to_string()))?;
            let value: T = quick_xml::de::from_reader(&bytes[..])
                .map_err(|e| ApiRejection::DeserializationFailed(e.to_string()))?;
            value
                .validate()
                .map_err(ApiRejection::ValidationFailed)?;
            Ok(ValidatedXml(value))
        }
    }
}

// --- API Handlers ---
mod api_handlers {
    use super::custom_extractors::{ValidatedJson, ValidatedXml};
    use super::data_models::*;

    pub async fn process_user_creation(
        ValidatedJson(user_creation_payload): ValidatedJson<UserCreationRequest>,
    ) -> impl IntoResponse {
        // Business logic starts here, validation is already done.
        let new_user_model = User {
            id: Uuid::new_v4(),
            email: user_creation_payload.email_address,
            role: user_creation_payload.assigned_role.unwrap_or(UserRole::USER),
            is_active: true,
            created_at: Utc::now(),
        };
        (StatusCode::CREATED, Json(new_user_model))
    }

    pub async fn process_post_creation(
        ValidatedXml(post_creation_payload): ValidatedXml<PostCreationRequest>,
    ) -> impl IntoResponse {
        let new_post_model = Post {
            id: Uuid::new_v4(),
            user_id: post_creation_payload.author_user_id,
            title: post_creation_payload.post_title,
            content: post_creation_payload.post_content,
            status: PostStatus::DRAFT,
        };
        let xml_response_body = quick_xml::se::to_string(&new_post_model).unwrap_or_default();
        (
            StatusCode::CREATED,
            [(header::CONTENT_TYPE, "application/xml")],
            xml_response_body,
        )
    }
}

// --- Main Application Setup ---
#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter("info")
        .init();

    let app_router = Router::new()
        .route("/", get(|| async { "State-Driven API is operational" }))
        .route("/v1/users", post(api_handlers::process_user_creation))
        .route("/v1/posts", post(api_handlers::process_post_creation));

    let server_address = SocketAddr::from(([127, 0, 0, 1], 3000));
    tracing::info!("Server starting, listening on {}", server_address);

    let listener = tokio::net::TcpListener::bind(server_address).await.unwrap();
    axum::serve(listener, app_router.into_make_service()).await.unwrap();
}