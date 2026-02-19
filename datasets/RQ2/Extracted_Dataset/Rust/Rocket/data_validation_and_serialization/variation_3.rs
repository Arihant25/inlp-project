<pre>
// Variation 3: The "Service-Oriented" Developer
// Style: Emphasizes a layered architecture. Thin controllers (handlers) delegate business
// logic to a dedicated service layer. Uses Rocket's state management for dependency injection.
// Errors are clearly defined at each layer.

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

use rocket::{Request, Response, State};
use rocket::http::{ContentType, Status};
use rocket::response::{self, Responder};
use rocket::serde::{json::Json, Deserialize, Serialize};
use rocket::serde::uuid::Uuid;
use chrono::{DateTime, Utc};
use validator::{Validate, ValidationErrors};
use thiserror::Error;
use std::io::Cursor;
use std::sync::Arc;

// --- models.rs ---
mod models {
    use super::*;
    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub enum UserRole { ADMIN, USER }
    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub enum PostStatus { DRAFT, PUBLISHED }

    #[derive(Debug, Serialize, Deserialize, Clone)]
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
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }
}

// --- dtos.rs ---
mod dtos {
    use super::*;
    #[derive(Deserialize, Validate)]
    pub struct CreateUserPayload {
        #[validate(email)]
        pub email: String,
        #[validate(length(min = 8))]
        pub password: String,
    }

    #[derive(Deserialize)]
    pub struct CreatePostPayload {
        pub title: String,
        pub content: String,
    }
}

// --- services.rs ---
mod services {
    use super::*;
    use models::{Post, PostStatus, User, UserRole};
    use dtos::{CreateUserPayload, CreatePostPayload};

    #[derive(Error, Debug)]
    pub enum ServiceError {
        #[error("Validation error: {0}")]
        Validation(#[from] ValidationErrors),
        #[error("User with email '{0}' already exists")]
        UserAlreadyExists(String),
        #[error("Resource not found")]
        NotFound,
        #[error("Internal server error")]
        Internal,
    }

    pub struct UserService;
    impl UserService {
        pub fn create_user(&self, payload: CreateUserPayload) -> Result<User, ServiceError> {
            payload.validate()?;
            // Mock logic: check for existing user
            if payload.email == "exists@example.com" {
                return Err(ServiceError::UserAlreadyExists(payload.email));
            }
            Ok(User {
                id: Uuid::new_v4(),
                email: payload.email,
                password_hash: format!("hashed:{}", payload.password),
                role: UserRole::USER,
                is_active: true,
                created_at: Utc::now(),
            })
        }
    }

    pub struct PostService;
    impl PostService {
        pub fn create_post_from_xml(&self, payload: CreatePostPayload) -> Result<Post, ServiceError> {
            Ok(Post {
                id: Uuid::new_v4(),
                user_id: Uuid::new_v4(), // Mocked
                title: payload.title,
                content: payload.content,
                status: PostStatus::DRAFT,
            })
        }
        pub fn find_post_by_id(&self, id: Uuid) -> Result<Post, ServiceError> {
            // Mock logic
            Ok(Post {
                id,
                user_id: Uuid::new_v4(),
                title: "Service-Oriented Post".to_string(),
                content: "Content from the PostService layer.".to_string(),
                status: PostStatus::PUBLISHED,
            })
        }
    }
}

// --- controllers.rs ---
mod controllers {
    use super::*;
    use services::{ServiceError, UserService, PostService};
    use dtos::{CreateUserPayload, CreatePostPayload};
    use models::{User, Post};

    // --- Custom Responder for Service Errors ---
    impl<'r, 'o: 'r> Responder<'r, 'o> for ServiceError {
        fn respond_to(self, _: &'r Request<'_>) -> response::Result<'o> {
            let (status, message) = match self {
                ServiceError::Validation(e) => (Status::UnprocessableEntity, e.to_string()),
                ServiceError::UserAlreadyExists(email) => (Status::Conflict, format!("User with email '{}' already exists", email)),
                ServiceError::NotFound => (Status::NotFound, "Resource not found".to_string()),
                ServiceError::Internal => (Status::InternalServerError, "An internal error occurred".to_string()),
            };
            Response::build()
                .status(status)
                .header(ContentType::JSON)
                .sized_body(message.len(), Cursor::new(message))
                .ok()
        }
    }

    // --- Custom XML Responder ---
    pub struct XmlResponse<T>(pub T);
    impl<'r, 'o: 'r, T: Serialize> Responder<'r, 'o> for XmlResponse<T> {
        fn respond_to(self, _: &'r Request<'_>) -> response::Result<'o> {
            serde_xml_rs::to_string(&self.0)
                .map_err(|_| Status::InternalServerError)
                .and_then(|xml| Response::build()
                    .header(ContentType::XML)
                    .sized_body(xml.len(), Cursor::new(xml))
                    .ok())
        }
    }

    #[post("/users", format = "json", data = "<payload>")]
    pub fn create_user(
        payload: Json<CreateUserPayload>,
        user_service: &State<UserService>,
    ) -> Result<Json<User>, ServiceError> {
        user_service.create_user(payload.into_inner()).map(Json)
    }

    #[post("/posts/xml", format = "xml", data = "<data>")]
    pub async fn create_post_xml(
        data: rocket::Data<'_>,
        post_service: &State<PostService>,
    ) -> Result<XmlResponse<Post>, Status> {
        let body_str = data.open(2.mebibytes()).into_string().await
            .map_err(|_| Status::BadRequest)?
            .into_inner();
        let payload: CreatePostPayload = serde_xml_rs::from_str(&body_str)
            .map_err(|_| Status::UnprocessableEntity)?;
        
        post_service.create_post_from_xml(payload)
            .map(XmlResponse)
            .map_err(|_| Status::InternalServerError)
    }

    #[get("/posts/<id>/xml")]
    pub fn get_post_xml(id: Uuid, post_service: &State<PostService>) -> Result<XmlResponse<Post>, ServiceError> {
        post_service.find_post_by_id(id).map(XmlResponse)
    }
}

#[launch]
fn rocket() -> _ {
    rocket::build()
        .mount("/", routes![
            controllers::create_user,
            controllers::create_post_xml,
            controllers::get_post_xml
        ])
        .manage(services::UserService)
        .manage(services::PostService)
}
</pre>