<pre>
// Variation 2: The "Functional &amp; Concise" Developer
// Style: Prefers a more compact, functional style. Keeps related logic together in a single file,
// using `mod` blocks for namespacing. Leverages Rocket's built-in `Contextual` form type
// for detailed, field-level validation feedback without custom guards.

// --- Cargo.toml dependencies ---
// rocket = { version = "0.5.0", features = ["json", "uuid", "form"] }
// serde = { version = "1.0", features = ["derive"] }
// serde_json = "1.0"
// serde_xml_rs = "0.6"
// validator = { version = "0.16", features = ["derive"] }
// uuid = { version = "1.4", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }

#![allow(dead_code)]
#![allow(unused_imports)]

#[macro_use] extern crate rocket;

use rocket::{Request, Response};
use rocket::form::{self, Contextual, Form};
use rocket::http::{ContentType, Status};
use rocket::response::{self, Responder, content};
use rocket::serde::{json::{Json, Value, json}, Deserialize, Serialize};
use rocket::serde::uuid::Uuid;
use chrono::{DateTime, Utc};
use std::io::Cursor;
use validator::{Validate, validate_email};

// --- Domain Namespace ---
mod domain {
    use super::*;

    #[derive(Debug, Serialize, Deserialize, Clone)]
    #[serde(rename_all = "snake_case")]
    pub enum UserRole { ADMIN, USER }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    #[serde(rename_all = "snake_case")]
    pub enum PostStatus { DRAFT, PUBLISHED }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        #[serde(skip)]
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

// --- API Namespace ---
mod api {
    use super::*;
    use domain::{User, UserRole, Post, PostStatus};

    // --- DTOs with validation rules ---
    #[derive(Debug, FromForm, Deserialize, Validate)]
    pub struct CreateUserRequest<'r> {
        #[field(validate = len(1..))]
        #[field(validate = email())]
        pub email: &'r str,
        #[field(validate = len(8..))]
        pub password: &'r str,
        #[field(validate = contains('+'))]
        #[field(validate = len(9..))]
        pub phone: &'r str,
    }

    #[derive(Debug, Deserialize)]
    pub struct CreatePostXmlRequest {
        pub title: String,
        pub content: String,
    }

    // --- Custom XML Responder ---
    pub struct Xml<T>(pub T);

    impl<'r, 'o: 'r, T: Serialize> Responder<'r, 'o> for Xml<T> {
        fn respond_to(self, _: &'r Request<'_>) -> response::Result<'o> {
            serde_xml_rs::to_string(&self.0)
                .map(|xml| Response::build()
                    .header(ContentType::XML)
                    .sized_body(xml.len(), Cursor::new(xml))
                    .finalize())
                .map_err(|_| Status::InternalServerError)
        }
    }

    // --- Routes ---
    #[post("/users", data = "<form>")]
    pub fn create_user(form: Form<Contextual<'_, CreateUserRequest<'_>>>) -> (Status, Value) {
        match form.value {
            Some(ref user_req) => {
                let new_user = User {
                    id: Uuid::new_v4(),
                    email: user_req.email.to_string(),
                    password_hash: format!("hashed_{}", user_req.password),
                    role: UserRole::USER,
                    is_active: true,
                    created_at: Utc::now(),
                };
                (Status::Created, json!(new_user))
            }
            None => {
                let errors = form.context.errors().map(|e| {
                    json!({ "field": e.name.as_ref().unwrap_or_default(), "error": e.kind.to_string() })
                }).collect::<Vec<_>>();
                (Status::UnprocessableEntity, json!({ "errors": errors }))
            }
        }
    }

    #[post("/posts/xml", format = "xml", data = "<data>")]
    pub async fn create_post_xml(data: rocket::Data<'_>) -> Result<Xml<Post>, Status> {
        let body_str = data.open(2.mebibytes()).into_string().await
            .map_err(|_| Status::BadRequest)?
            .into_inner();

        let req: CreatePostXmlRequest = serde_xml_rs::from_str(&body_str)
            .map_err(|_| Status::UnprocessableEntity)?;

        Ok(Xml(Post {
            id: Uuid::new_v4(),
            user_id: Uuid::new_v4(),
            title: req.title,
            content: req.content,
            status: PostStatus::DRAFT,
        }))
    }

    #[get("/posts/<id>/xml")]
    pub fn get_post_xml(id: Uuid) -> Xml<Post> {
        Xml(Post {
            id,
            user_id: Uuid::new_v4(),
            title: "Concise XML Post".to_string(),
            content: "Functional and concise XML content.".to_string(),
            status: PostStatus::PUBLISHED,
        })
    }
}

#[launch]
fn rocket() -> _ {
    rocket::build().mount("/", routes![
        api::create_user,
        api::create_post_xml,
        api::get_post_xml
    ])
}
</pre>