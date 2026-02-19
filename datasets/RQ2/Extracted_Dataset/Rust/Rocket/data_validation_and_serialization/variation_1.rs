<pre>
// Variation 1: The "By-the-Book" Developer
// Style: Follows official guides, well-structured with clear separation of concerns
// into modules (models, dtos, errors, routes). Uses a custom request guard for validation.

// --- Cargo.toml dependencies ---
// rocket = { version = "0.5.0", features = ["json", "uuid"] }
// serde = { version = "1.0", features = ["derive"] }
// serde_json = "1.0"
// serde_xml_rs = "0.6"
// validator = { version = "0.16", features = ["derive"] }
// uuid = { version = "1.4", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }

#![allow(dead_code)]
#![allow(unused_imports)]

#[macro_use] extern crate rocket;

use rocket::{Request, Response, State};
use rocket::data::{Data, ToByteUnit};
use rocket::http::{ContentType, Status};
use rocket::request::{self, FromRequest};
use rocket::response::{self, Responder, content};
use rocket::serde::{json::Json, Deserialize, Serialize};
use rocket::serde::uuid::Uuid;
use chrono::{DateTime, Utc};
use validator::{Validate, ValidationError, ValidationErrors};
use std::io::Cursor;
use std::collections::HashMap;

// --- Module: models.rs ---
mod models {
    use super::*;

    #[derive(Debug, Serialize, Deserialize, Clone)]
    #[serde(rename_all = "camelCase")]
    pub enum UserRole {
        ADMIN,
        USER,
    }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    #[serde(rename_all = "camelCase")]
    pub enum PostStatus {
        DRAFT,
        PUBLISHED,
    }

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

// --- Module: dtos.rs ---
mod dtos {
    use super::*;

    #[derive(Debug, Deserialize, Validate)]
    pub struct CreateUserDto {
        #[validate(email(message = "Must be a valid email address."))]
        pub email: String,
        #[validate(length(min = 8, message = "Password must be at least 8 characters long."))]
        pub password: String,
        // Custom validator for phone number format (e.g., +1234567890)
        #[validate(phone(message = "Phone number must be in E.164 format."))]
        pub phone_number: String,
    }

    // Custom phone validator function
    fn validate_phone(phone: &str) -> Result<(), ValidationError> {
        if phone.starts_with('+') && phone[1..].chars().all(char::is_numeric) && phone.len() > 8 {
            Ok(())
        } else {
            Err(ValidationError::new("invalid_phone_format"))
        }
    }
}

// --- Module: errors.rs ---
mod errors {
    use super::*;

    #[derive(Debug, Serialize)]
    pub struct ErrorResponse {
        pub message: String,
        pub details: Option<HashMap<String, Vec<String>>>,
    }

    pub fn format_validation_errors(errors: &ValidationErrors) -> (Status, Json<ErrorResponse>) {
        let mut details = HashMap::new();
        for (field, field_errors) in errors.field_errors() {
            let messages = field_errors.iter()
                .map(|e| e.message.as_ref().map_or_else(|| e.code.to_string(), |m| m.to_string()))
                .collect();
            details.insert(field.to_string(), messages);
        }

        let response = ErrorResponse {
            message: "Validation failed".to_string(),
            details: Some(details),
        };

        (Status::UnprocessableEntity, Json(response))
    }
}

// --- Module: validation.rs (Custom Request Guard) ---
mod validation {
    use super::*;

    #[derive(Debug)]
    pub struct ValidatedJson<T>(pub T);

    #[rocket::async_trait]
    impl<'r, T: Validate + Deserialize<'r>> FromRequest<'r> for ValidatedJson<T> {
        type Error = (Status, Json<errors::ErrorResponse>);

        async fn from_request(req: &'r Request<'_>) -> request::Outcome<Self, Self::Error> {
            let json_res = Json::<T>::from_request(req).await;
            
            match json_res {
                request::Outcome::Success(json) => {
                    let data = json.into_inner();
                    if let Err(e) = data.validate() {
                        request::Outcome::Error(errors::format_validation_errors(&e))
                    } else {
                        request::Outcome::Success(ValidatedJson(data))
                    }
                }
                request::Outcome::Error((status, _)) => {
                    let response = errors::ErrorResponse {
                        message: "Invalid JSON body".to_string(),
                        details: None,
                    };
                    request::Outcome::Error((status, Json(response)))
                }
                request::Outcome::Forward(f) => request::Outcome::Forward(f),
            }
        }
    }
}

// --- Module: serialization.rs (Custom XML Responder) ---
mod serialization {
    use super::*;

    pub struct Xml<T>(pub T);

    impl<'r, 'o: 'r, T: Serialize> Responder<'r, 'o> for Xml<T> {
        fn respond_to(self, _: &'r Request<'_>) -> response::Result<'o> {
            match serde_xml_rs::to_string(&self.0) {
                Ok(xml_string) => {
                    Response::build()
                        .header(ContentType::XML)
                        .sized_body(xml_string.len(), Cursor::new(xml_string))
                        .ok()
                }
                Err(_) => Err(Status::InternalServerError),
            }
        }
    }

    #[derive(Debug)]
    pub struct XmlData<T>(pub T);

    #[rocket::async_trait]
    impl<'r, T: Deserialize<'r>> FromRequest<'r> for XmlData<T> {
        type Error = ();

        async fn from_request(req: &'r Request<'_>) -> request::Outcome<Self, Self::Error> {
            let data = match req.data(2.mebibytes()).open().into_string().await {
                Ok(s) if s.is_complete() => s.into_inner(),
                _ => return request::Outcome::Error((Status::BadRequest, ())),
            };

            match serde_xml_rs::from_str(&data) {
                Ok(deserialized) => request::Outcome::Success(XmlData(deserialized)),
                Err(_) => request::Outcome::Error((Status::UnprocessableEntity, ())),
            }
        }
    }
}

// --- Module: routes.rs ---
mod routes {
    use super::*;
    use models::{Post, PostStatus, User, UserRole};
    use dtos::CreateUserDto;
    use validation::ValidatedJson;
    use serialization::{Xml, XmlData};

    #[post("/users", format = "json", data = "<user_dto>")]
    pub fn create_user(user_dto: ValidatedJson<CreateUserDto>) -> Json<User> {
        let new_user = User {
            id: Uuid::new_v4(),
            email: user_dto.0.email,
            password_hash: format!("hashed_{}", user_dto.0.password), // In real app, use a proper hash
            role: UserRole::USER,
            is_active: true,
            created_at: Utc::now(),
        };
        Json(new_user)
    }

    #[derive(Deserialize)]
    struct CreatePostXml {
        title: String,
        content: String,
    }

    #[post("/posts/xml", format = "xml", data = "<post_data>")]
    pub fn create_post_from_xml(post_data: XmlData<CreatePostXml>) -> Xml<Post> {
        let new_post = Post {
            id: Uuid::new_v4(),
            user_id: Uuid::new_v4(), // Mock user_id
            title: post_data.0.title,
            content: post_data.0.content,
            status: PostStatus::DRAFT,
        };
        Xml(new_post)
    }

    #[get("/posts/<id>/xml")]
    pub fn get_post_as_xml(id: Uuid) -> Xml<Post> {
        let mock_post = Post {
            id,
            user_id: Uuid::new_v4(),
            title: "An XML Post".to_string(),
            content: "This is the content, served as XML.".to_string(),
            status: PostStatus::PUBLISHED,
        };
        Xml(mock_post)
    }
}

#[launch]
fn rocket() -> _ {
    rocket::build().mount("/", routes![
        routes::create_user,
        routes::create_post_from_xml,
        routes::get_post_as_xml
    ])
}
</pre>