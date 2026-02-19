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
*/

use actix_web::{web, App, HttpServer, HttpResponse, Responder, ResponseError};
use serde::{Deserialize, Serialize};
use validator::Validate;
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::sync::Arc;
use thiserror::Error;

// --- Domain Models ---
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "UPPERCASE")]
pub enum Role { Admin, User }

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "UPPERCASE")]
pub enum Status { Draft, Published }

#[derive(Serialize, Debug, Clone)]
pub struct User {
    id: Uuid,
    email: String,
    role: Role,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: Status,
}

// --- DTOs ---
#[derive(Deserialize, Validate)]
pub struct UserCreationPayload {
    #[validate(email)]
    pub email: String,
    #[validate(length(min = 8, message = "password is too short"))]
    pub password: String,
    #[validate(phone)]
    pub phone: String,
}

#[derive(Deserialize, Validate)]
pub struct PostCreationPayload {
    pub user_id: Uuid,
    #[validate(length(min = 1, max = 100))]
    pub title: String,
    #[validate(length(min = 1))]
    pub content: String,
}

// --- Error Handling ---
#[derive(Error, Debug)]
pub enum ServiceError {
    #[error("Validation Error: {0}")]
    InvalidData(#[from] validator::ValidationErrors),
    #[error("XML Parse Error: {0}")]
    XmlParseError(#[from] serde_xml_rs::Error),
    #[error("Internal Logic Error: {0}")]
    Internal(String),
}

impl ResponseError for ServiceError {
    fn status_code(&self) -> actix_web::http::StatusCode {
        match self {
            ServiceError::InvalidData(_) => actix_web::http::StatusCode::BAD_REQUEST,
            ServiceError::XmlParseError(_) => actix_web::http::StatusCode::BAD_REQUEST,
            ServiceError::Internal(_) => actix_web::http::StatusCode::INTERNAL_SERVER_ERROR,
        }
    }
}

// --- Service Layer ---
// In a real app, this would hold a DB connection pool.
#[derive(Clone)]
pub struct AppState {
    user_service: Arc<UserService>,
    post_service: Arc<PostService>,
}

pub struct UserService;
impl UserService {
    pub fn new() -> Self { Self }
    pub fn create_user(&self, payload: UserCreationPayload) -> Result<User, ServiceError> {
        payload.validate()?; // Business logic can re-validate if needed.
        
        // Mock password hashing and DB insertion
        println!("Hashing password and creating user: {}", payload.email);
        
        Ok(User {
            id: Uuid::new_v4(),
            email: payload.email,
            role: Role::User,
            is_active: true,
            created_at: Utc::now(),
        })
    }
}

pub struct PostService;
impl PostService {
    pub fn new() -> Self { Self }
    pub fn create_post_from_xml(&self, xml_data: &str) -> Result<Post, ServiceError> {
        let payload: PostCreationPayload = serde_xml_rs::from_str(xml_data)?;
        payload.validate()?;

        println!("Creating post with title: {}", payload.title);

        Ok(Post {
            id: Uuid::new_v4(),
            user_id: payload.user_id,
            title: payload.title,
            content: payload.content,
            status: Status::Draft,
        })
    }
}

// --- API Handlers (Controllers) ---
mod api_handlers {
    use super::*;

    pub async fn handle_create_user(
        app_state: web::Data<AppState>,
        payload: web::Json<UserCreationPayload>,
    ) -> Result<impl Responder, ServiceError> {
        let created_user = app_state.user_service.create_user(payload.into_inner())?;
        Ok(HttpResponse::Created().json(created_user))
    }

    pub async fn handle_create_post_xml(
        app_state: web::Data<AppState>,
        xml_body: String,
    ) -> Result<HttpResponse, ServiceError> {
        let created_post = app_state.post_service.create_post_from_xml(&xml_body)?;
        let response_xml = serde_xml_rs::to_string(&created_post)
            .map_err(|e| ServiceError::Internal(e.to_string()))?;
        
        Ok(HttpResponse::Created()
            .content_type("application/xml")
            .body(response_xml))
    }
}

// --- Main Application Setup ---
#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let app_state = web::Data::new(AppState {
        user_service: Arc::new(UserService::new()),
        post_service: Arc::new(PostService::new()),
    });

    HttpServer::new(move || {
        // Global JSON validation error configuration
        let json_config = web::JsonConfig::default()
            .error_handler(|err, _req| {
                let service_err = match err {
                    actix_web::error::JsonPayloadError::Validate(e) => ServiceError::InvalidData(e),
                    _ => ServiceError::Internal(err.to_string()),
                };
                actix_web::error::InternalError::from_response(err, service_err.error_response()).into()
            });

        App::new()
            .app_data(app_state.clone())
            .app_data(json_config)
            .service(
                web::scope("/api/v1")
                    .service(
                        web::resource("/users")
                            .route(web::post().to(api_handlers::handle_create_user))
                    )
                    .service(
                        web::resource("/posts/xml")
                            .route(web::post().to(api_handlers::handle_create_post_xml))
                    )
            )
    })
    .bind("127.0.0.1:8080")?
    .run()
    .await
}