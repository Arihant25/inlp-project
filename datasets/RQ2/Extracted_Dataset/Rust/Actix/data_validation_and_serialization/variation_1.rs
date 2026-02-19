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
*/

use actix_web::{web, App, HttpServer, Responder, HttpResponse, ResponseError};
use serde::{Serialize, Deserialize};
use validator::{Validate, ValidationError, ValidationErrors};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::fmt;

// --- DOMAIN MODELS ---

#[derive(Serialize, Deserialize, Debug)]
#[serde(rename_all = "UPPERCASE")]
pub enum Role {
    Admin,
    User,
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(rename_all = "UPPERCASE")]
pub enum Status {
    Draft,
    Published,
}

#[derive(Serialize, Debug)]
pub struct User {
    id: Uuid,
    email: String,
    #[serde(skip_serializing)]
    password_hash: String,
    role: Role,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: Status,
}

// --- CUSTOM VALIDATOR ---

fn validate_title_not_spam(title: &str) -> Result<(), ValidationError> {
    if title.contains("spam") {
        return Err(ValidationError::new("bad_title"));
    }
    Ok(())
}

// --- DTOs (Data Transfer Objects) for validation ---

#[derive(Deserialize, Validate)]
struct CreateUserRequest {
    #[validate(email(message = "Must be a valid email address."))]
    email: String,
    #[validate(length(min = 8, message = "Password must be at least 8 characters long."))]
    password: String,
    #[validate(phone(message = "Must be a valid phone number."))]
    phone_number: String,
}

#[derive(Deserialize, Validate, Debug)]
struct CreatePostRequest {
    user_id: Uuid,
    #[validate(length(min = 1, message = "Title is required."))]
    #[validate(custom(function = "validate_title_not_spam", message = "Title cannot contain the word 'spam'"))]
    title: String,
    #[validate(length(min = 1, message = "Content is required."))]
    content: String,
}

// --- ERROR HANDLING ---

#[derive(Debug, Serialize)]
struct ErrorResponse {
    status: String,
    message: String,
}

impl fmt::Display for ErrorResponse {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", serde_json::to_string(self).unwrap())
    }
}

impl ResponseError for ErrorResponse {
    fn error_response(&self) -> HttpResponse {
        HttpResponse::BadRequest().json(self)
    }
}

// --- HANDLERS ---

async fn create_user(user_req: web::Json<CreateUserRequest>) -> impl Responder {
    // The `web::Json` extractor automatically validates if the struct derives `Validate`.
    // If validation fails, it returns a 400 Bad Request error.
    // We can proceed assuming the data is valid.

    let new_user = User {
        id: Uuid::new_v4(),
        email: user_req.email.clone(),
        // In a real app, you would hash the password here.
        password_hash: format!("hashed_{}", user_req.password),
        role: Role::User,
        is_active: true,
        created_at: Utc::now(),
    };

    HttpResponse::Created().json(new_user)
}

async fn create_post_xml(post_req_xml: String) -> impl Responder {
    // Manual XML parsing and validation
    match serde_xml_rs::from_str::<CreatePostRequest>(&post_req_xml) {
        Ok(post_req) => {
            if let Err(e) = post_req.validate() {
                // Format validation errors
                let error_message = e.field_errors()
                    .into_iter()
                    .map(|(field, errors)| {
                        let messages: Vec<String> = errors.iter().filter_map(|err| err.message.as_ref().map(|m| m.to_string())).collect();
                        format!("{}: {}", field, messages.join(", "))
                    })
                    .collect::<Vec<String>>()
                    .join("; ");

                let err_resp = ErrorResponse {
                    status: "error".to_string(),
                    message: error_message,
                };
                return HttpResponse::BadRequest().body(serde_xml_rs::to_string(&err_resp).unwrap());
            }

            let new_post = Post {
                id: Uuid::new_v4(),
                user_id: post_req.user_id,
                title: post_req.title.clone(),
                content: post_req.content.clone(),
                status: Status::Draft,
            };

            // Respond with XML
            HttpResponse::Created()
                .content_type("application/xml")
                .body(serde_xml_rs::to_string(&new_post).unwrap())
        }
        Err(e) => {
            let err_resp = ErrorResponse {
                status: "error".to_string(),
                message: format!("XML Deserialization Error: {}", e),
            };
            HttpResponse::BadRequest().body(serde_xml_rs::to_string(&err_resp).unwrap())
        }
    }
}

// --- MAIN APP SETUP ---

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    println!("Server running at http://127.0.0.1:8080");

    HttpServer::new(|| {
        // Configure custom error handler for JSON validation
        let json_config = web::JsonConfig::default()
            .error_handler(|err, _req| {
                let error_message = match &err {
                    actix_web::error::JsonPayloadError::Validate(validation_errors) => {
                        format_validation_errors(validation_errors)
                    }
                    _ => err.to_string(),
                };
                let err_resp = ErrorResponse {
                    status: "error".to_string(),
                    message: error_message,
                };
                actix_web::error::InternalError::from_response(err, err_resp.error_response()).into()
            });

        App::new()
            .app_data(json_config)
            .service(
                web::resource("/users")
                    .route(web::post().to(create_user))
            )
            .service(
                web::resource("/posts/xml")
                    .route(web::post().to(create_post_xml))
            )
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}

fn format_validation_errors(errors: &ValidationErrors) -> String {
    errors.field_errors().iter().map(|(field, errs)| {
        let messages = errs.iter().map(|e| e.message.as_ref().unwrap().to_string()).collect::<Vec<_>>().join(", ");
        format!("{}: {}", field, messages)
    }).collect::<Vec<_>>().join("; ")
}