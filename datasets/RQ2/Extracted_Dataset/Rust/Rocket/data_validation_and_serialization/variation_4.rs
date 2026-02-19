<pre>
// Variation 4: The "Domain-Driven" Developer
// Style: Focuses on a rich domain model where entities enforce their own invariants (rules).
// Validation logic resides within the domain objects themselves, ensuring they are always in a
// valid state. Handlers are responsible for orchestrating DTO-to-Domain conversion.

// --- Cargo.toml dependencies ---
// rocket = { version = "0.5.0", features = ["json", "uuid"] }
// serde = { version = "1.0", features = ["derive"] }
// serde_json = "1.0"
// serde_xml_rs = "0.6"
// validator = { version = "0.16", features = ["derive"] }
// uuid = { version = "1.4", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }
// thiserror = "1.0"

#![allow(dead_code)]
#![allow(unused_imports)]

#[macro_use] extern crate rocket;

use rocket::http::{ContentType, Status};
use rocket::response::{self, Responder};
use rocket::serde::{json::{Json, Value, json}, Deserialize, Serialize};
use rocket::serde::uuid::Uuid;
use rocket::{Request, Response};
use chrono::{DateTime, Utc};
use thiserror::Error;
use std::io::Cursor;
use std::convert::TryFrom;

// --- Domain Layer ---
mod domain {
    use super::*;

    // --- Shared Enums ---
    #[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
    pub enum UserRole { ADMIN, USER }
    #[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
    pub enum PostStatus { DRAFT, PUBLISHED }

    // --- Domain Errors ---
    #[derive(Error, Debug, PartialEq)]
    pub enum DomainError {
        #[error("Invalid email format: {0}")]
        InvalidEmail(String),
        #[error("Password is too short (minimum 8 characters)")]
        PasswordTooShort,
        #[error("Title cannot be empty")]
        EmptyTitle,
        #[error("Content cannot be empty")]
        EmptyContent,
    }

    // --- Rich User Entity ---
    #[derive(Debug, Serialize, Clone)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        #[serde(skip_serializing)]
        password_hash: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    impl User {
        pub fn new(email: String, password_plaintext: String) -> Result<Self, DomainError> {
            if !validator::validate_email(&email) {
                return Err(DomainError::InvalidEmail(email));
            }
            if password_plaintext.len() < 8 {
                return Err(DomainError::PasswordTooShort);
            }

            Ok(Self {
                id: Uuid::new_v4(),
                email,
                password_hash: format!("hashed::{}", password_plaintext), // Hashing logic here
                role: UserRole::USER,
                is_active: true,
                created_at: Utc::now(),
            })
        }
    }

    // --- Rich Post Entity ---
    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }

    impl Post {
        pub fn new(user_id: Uuid, title: String, content: String) -> Result<Self, DomainError> {
            if title.trim().is_empty() {
                return Err(DomainError::EmptyTitle);
            }
            if content.trim().is_empty() {
                return Err(DomainError::EmptyContent);
            }
            Ok(Self {
                id: Uuid::new_v4(),
                user_id,
                title,
                content,
                status: PostStatus::DRAFT,
            })
        }
    }
}

// --- Web Layer (Handlers, DTOs, Responders) ---
mod web {
    use super::*;
    use domain::{DomainError, Post, User};

    // --- DTOs for Deserialization ---
    #[derive(Deserialize)]
    pub struct CreateUserDto {
        email: String,
        password: String,
    }

    #[derive(Deserialize)]
    pub struct CreatePostDto {
        title: String,
        content: String,
    }

    // --- DTO to Domain Conversion ---
    impl TryFrom<CreateUserDto> for User {
        type Error = DomainError;
        fn try_from(dto: CreateUserDto) -> Result<Self, Self::Error> {
            User::new(dto.email, dto.password)
        }
    }

    // --- Custom Error Responder ---
    pub struct ApiError(Status, Value);

    impl<'r, 'o: 'r> Responder<'r, 'o> for ApiError {
        fn respond_to(self, req: &'r Request<'_>) -> response::Result<'o> {
            Json(self.1).respond_to(req).map(|mut res| {
                res.set_status(self.0);
                res
            })
        }
    }

    impl From<DomainError> for ApiError {
        fn from(err: DomainError) -> Self {
            let status = Status::UnprocessableEntity;
            let body = json!({ "error": err.to_string() });
            ApiError(status, body)
        }
    }

    // --- Custom XML Responder ---
    pub struct Xml<T>(pub T);
    impl<'r, 'o: 'r, T: Serialize> Responder<'r, 'o> for Xml<T> {
        fn respond_to(self, _: &'r Request<'_>) -> response::Result<'o> {
            match serde_xml_rs::to_string(&self.0) {
                Ok(s) => Response::build().header(ContentType::XML).sized_body(s.len(), Cursor::new(s)).ok(),
                Err(_) => Err(Status::InternalServerError),
            }
        }
    }

    // --- Handlers ---
    #[post("/users", format = "json", data = "<dto>")]
    pub fn create_user(dto: Json<CreateUserDto>) -> Result<Json<User>, ApiError> {
        let user = User::try_from(dto.into_inner())?;
        // In a real app, you would save the user to a database here
        Ok(Json(user))
    }

    #[post("/posts/xml", format = "xml", data = "<data>")]
    pub async fn create_post_from_xml(data: rocket::Data<'_>) -> Result<Xml<Post>, ApiError> {
        let body_str = data.open(2.mebibytes()).into_string().await
            .map_err(|_| ApiError(Status::BadRequest, json!({"error": "Bad XML data"})))?
            .into_inner();
        
        let dto: CreatePostDto = serde_xml_rs::from_str(&body_str)
            .map_err(|_| ApiError(Status::UnprocessableEntity, json!({"error": "Cannot parse XML"})))?;

        let post = Post::new(Uuid::new_v4(), dto.title, dto.content)?;
        // Save post to DB
        Ok(Xml(post))
    }

    #[get("/posts/<id>/xml")]
    pub fn get_post_as_xml(id: Uuid) -> Xml<Post> {
        // Mock fetch from DB
        Xml(Post {
            id,
            user_id: Uuid::new_v4(),
            title: "Domain-Driven Post".to_string(),
            content: "This post was validated by its own domain model.".to_string(),
            status: domain::PostStatus::PUBLISHED,
        })
    }
}

#[launch]
fn rocket() -> _ {
    rocket::build().mount("/", routes![
        web::create_user,
        web::create_post_from_xml,
        web::get_post_as_xml
    ])
}
</pre>