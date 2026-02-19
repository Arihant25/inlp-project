/*
--- CARGO.TOML ---
[dependencies]
actix-web = "4"
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
serde_xml_rs = "0.6"
validator = { version = "0.16", features = ["derive"] }
uuid = { version = "1.4", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
thiserror = "1.0"
derive_more = "0.99"
*/

use actix_web::{web, App, HttpServer, HttpResponse, Responder, ResponseError};
use serde::{Serialize, Deserialize, Deserializer};
use validator::Validate;
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::convert::TryFrom;
use derive_more::{Display, From};

// --- DOMAIN MODELS (Unchanged) ---
#[derive(Serialize, Deserialize, Debug)]
#[serde(rename_all = "UPPERCASE")]
pub enum Role { Admin, User }

#[derive(Serialize, Deserialize, Debug)]
#[serde(rename_all = "UPPERCASE")]
pub enum Status { Draft, Published }

#[derive(Serialize)]
pub struct User {
    id: Uuid,
    email: String, // In a real app, this would be the ValidEmail type
    role: Role,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Serialize, Deserialize)]
pub struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: Status,
}

// --- TYPE-DRIVEN VALIDATION ---

#[derive(Debug, Display, From)]
pub enum TypeError {
    #[display(fmt = "Invalid email format.")]
    InvalidEmail,
    #[display(fmt = "Password must be at least 8 characters.")]
    PasswordTooShort,
    #[display(fmt = "Title cannot be empty.")]
    EmptyTitle,
}

// Newtype for a validated email
#[derive(Debug, Serialize)]
pub struct ValidEmail(String);

impl TryFrom<String> for ValidEmail {
    type Error = TypeError;
    fn try_from(value: String) -> Result<Self, Self::Error> {
        if validator::validate_email(&value) {
            Ok(ValidEmail(value))
        } else {
            Err(TypeError::InvalidEmail)
        }
    }
}

// Newtype for a validated password
#[derive(Debug)]
pub struct ValidPassword(String);

impl TryFrom<String> for ValidPassword {
    type Error = TypeError;
    fn try_from(value: String) -> Result<Self, Self::Error> {
        if value.len() >= 8 {
            Ok(ValidPassword(value))
        } else {
            Err(TypeError::PasswordTooShort)
        }
    }
}

// Generic deserialization helper for TryFrom types
fn deserialize_try_from<'de, D, T>(deserializer: D) -> Result<T, D::Error>
where
    D: Deserializer<'de>,
    T: TryFrom<String>,
    T::Error: std::fmt::Display,
{
    let s = String::deserialize(deserializer)?;
    T::try_from(s).map_err(serde::de::Error::custom)
}

// --- DTOs using Type-Driven approach ---

#[derive(Deserialize)]
pub struct CreateUserDto {
    #[serde(deserialize_with = "deserialize_try_from")]
    pub email: ValidEmail,
    #[serde(deserialize_with = "deserialize_try_from")]
    pub password: ValidPassword,
    // Some validation can still use the validator crate
    #[validate(phone)]
    pub phone: String,
}

// We need to add `Validate` for the fields not covered by types
impl Validate for CreateUserDto {
    fn validate(&self) -> Result<(), validator::ValidationErrors> {
        validator::Validate::validate(&self)
    }
}

#[derive(Deserialize, Validate)]
pub struct CreatePostDto {
    pub user_id: Uuid,
    #[validate(length(min = 1, message = "Title is required."))]
    pub title: String,
    #[validate(length(min = 1, message = "Content is required."))]
    pub content: String,
}

// --- ERROR HANDLING ---
#[derive(Debug, Display, From)]
enum ApiError {
    Validation(validator::ValidationErrors),
    Deserialization(String),
}

impl ResponseError for ApiError {
    fn error_response(&self) -> HttpResponse {
        #[derive(Serialize)]
        struct ErrorDetails { message: String }
        let msg = self.to_string();
        HttpResponse::BadRequest().json(ErrorDetails { message: msg })
    }
}

// --- HANDLERS ---

async fn create_user_endpoint(
    dto: web::Json<CreateUserDto>
) -> Result<impl Responder, ApiError> {
    // Type validation happened during deserialization.
    // We still need to call validate() for the `validator` crate fields.
    dto.validate().map_err(ApiError::from)?;

    let new_user = User {
        id: Uuid::new_v4(),
        email: dto.email.0.clone(), // unwrap the newtype
        role: Role::User,
        is_active: true,
        created_at: Utc::now(),
    };

    Ok(HttpResponse::Created().json(new_user))
}

async fn create_post_endpoint_xml(
    body: String
) -> Result<HttpResponse, ApiError> {
    let dto: CreatePostDto = serde_xml_rs::from_str(&body)
        .map_err(|e| ApiError::Deserialization(e.to_string()))?;
    
    dto.validate().map_err(ApiError::from)?;

    let new_post = Post {
        id: Uuid::new_v4(),
        user_id: dto.user_id,
        title: dto.title.clone(),
        content: dto.content.clone(),
        status: Status::Draft,
    };

    let xml_response = serde_xml_rs::to_string(&new_post)
        .map_err(|e| ApiError::Deserialization(e.to_string()))?;

    Ok(HttpResponse::Created()
        .content_type("application/xml")
        .body(xml_response))
}

// --- MAIN APP ---
#[actix_web::main]
async fn main() -> std::io::Result<()> {
    HttpServer::new(|| {
        // Configure error handler for JSON to catch both serde and validator errors
        let json_config = web::JsonConfig::default()
            .error_handler(|err, _req| {
                let api_error = match &err {
                    actix_web::error::JsonPayloadError::Validate(e) => ApiError::Validation(e.clone()),
                    actix_web::error::JsonPayloadError::Deserialize(e) => ApiError::Deserialization(e.to_string()),
                    _ => ApiError::Deserialization("Unknown payload error".to_string()),
                };
                actix_web::error::InternalError::from_response(err, api_error.error_response()).into()
            });

        App::new()
            .app_data(json_config)
            .route("/users", web::post().to(create_user_endpoint))
            .route("/posts/xml", web::post().to(create_post_endpoint_xml))
    })
    .bind("127.0.0.1:8080")?
    .run()
    .await
}