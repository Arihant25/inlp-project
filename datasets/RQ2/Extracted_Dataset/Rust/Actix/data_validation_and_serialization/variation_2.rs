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

use actix_web::{web, App, HttpServer, HttpResponse, Responder};
use serde::{Serialize, Deserialize};
use std::collections::HashMap;

// --- MODULE: models ---
mod models {
    use super::*;
    use chrono::{DateTime, Utc};
    use uuid::Uuid;

    #[derive(Serialize, Deserialize, Debug, Clone)]
    #[serde(rename_all = "UPPERCASE")]
    pub enum Role { Admin, User }

    #[derive(Serialize, Deserialize, Debug, Clone)]
    #[serde(rename_all = "UPPERCASE")]
    pub enum Status { Draft, Published }

    #[derive(Serialize, Debug, Clone)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        #[serde(skip_serializing)]
        pub password_hash: String,
        pub role: Role,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Serialize, Deserialize, Debug, Clone)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: Status,
    }
}

// --- MODULE: dtos ---
mod dtos {
    use super::*;
    use validator::{Validate, ValidationError};
    use uuid::Uuid;

    // --- Custom Validators Sub-module ---
    mod validators {
        use super::*;
        pub fn block_profane_titles(title: &str) -> Result<(), ValidationError> {
            if title.to_lowercase().contains("profane") {
                let mut err = ValidationError::new("profane_title");
                err.message = Some("Title cannot contain profane words.".into());
                return Err(err);
            }
            Ok(())
        }
    }

    #[derive(Deserialize, Validate)]
    pub struct UserCreateDto {
        #[validate(email)]
        pub email: String,
        #[validate(length(min = 8))]
        pub password: String,
        #[validate(phone)]
        pub contact_phone: String,
    }

    #[derive(Deserialize, Validate, Debug)]
    pub struct PostCreateDto {
        pub user_id: Uuid,
        #[validate(length(min = 1), custom(function = "validators::block_profane_titles"))]
        pub title: String,
        #[validate(length(min = 10))]
        pub content: String,
    }
}

// --- MODULE: errors ---
mod errors {
    use super::*;
    use actix_web::{ResponseError, HttpResponse};
    use thiserror::Error;
    use validator::ValidationErrors;

    #[derive(Error, Debug)]
    pub enum AppError {
        #[error("Validation failed")]
        ValidationError(#[from] ValidationErrors),
        #[error("XML processing error")]
        XmlError(#[from] serde_xml_rs::Error),
        #[error("Internal server error")]
        InternalError,
    }

    #[derive(Serialize)]
    struct ErrorPayload {
        error: String,
        details: Option<HashMap<String, Vec<String>>>,
    }

    impl ResponseError for AppError {
        fn error_response(&self) -> HttpResponse {
            match self {
                AppError::ValidationError(e) => {
                    let mut details = HashMap::new();
                    for (field, errors) in e.field_errors() {
                        let messages = errors.iter()
                            .filter_map(|err| err.message.as_ref().map(|m| m.to_string()))
                            .collect();
                        details.insert(field.to_string(), messages);
                    }
                    HttpResponse::BadRequest().json(ErrorPayload {
                        error: "Validation failed".to_string(),
                        details: Some(details),
                    })
                }
                AppError::XmlError(e) => HttpResponse::BadRequest().json(ErrorPayload {
                    error: "Invalid XML format".to_string(),
                    details: Some(HashMap::from([("xml_error".to_string(), vec![e.to_string()])])),
                }),
                AppError::InternalError => HttpResponse::InternalServerError().json(ErrorPayload {
                    error: "An unexpected error occurred".to_string(),
                    details: None,
                }),
            }
        }
    }
}

// --- MODULE: handlers ---
mod handlers {
    use super::*;
    use models::{User, Post, Role, Status};
    use dtos::{UserCreateDto, PostCreateDto};
    use errors::AppError;
    use chrono::Utc;
    use uuid::Uuid;
    use validator::Validate;

    pub async fn register_user(
        dto: web::Json<UserCreateDto>
    ) -> Result<impl Responder, AppError> {
        dto.validate()?; // Manually trigger validation to use our custom AppError
        
        let user = User {
            id: Uuid::new_v4(),
            email: dto.email.clone(),
            password_hash: format!("hashed:{}", dto.password),
            role: Role::User,
            is_active: false,
            created_at: Utc::now(),
        };

        Ok(HttpResponse::Created().json(user))
    }

    pub async fn submit_post_as_xml(
        xml_body: String
    ) -> Result<HttpResponse, AppError> {
        let dto: PostCreateDto = serde_xml_rs::from_str(&xml_body)?;
        dto.validate()?;

        let post = Post {
            id: Uuid::new_v4(),
            user_id: dto.user_id,
            title: dto.title.clone(),
            content: dto.content.clone(),
            status: Status::Draft,
        };

        let response_xml = serde_xml_rs::to_string(&post)
            .map_err(|_| AppError::InternalError)?;

        Ok(HttpResponse::Created()
            .content_type("application/xml; charset=utf-t")
            .body(response_xml))
    }
}

// --- MAIN APPLICATION ---
#[actix_web::main]
async fn main() -> std::io::Result<()> {
    HttpServer::new(|| {
        App::new()
            .service(
                web::scope("/api")
                    .route("/users", web::post().to(handlers::register_user))
                    .route("/posts/xml", web::post().to(handlers::submit_post_as_xml))
            )
    })
    .bind("127.0.0.1:8080")?
    .run()
    .await
}