// Variation 4: The "Minimalist & Macro-Heavy" Developer
// This developer values conciseness and DRY (Don't Repeat Yourself) principles.
// They use macros to reduce boilerplate, especially for error handling (`map_err!`).
// A custom `FromData` implementation is used to create a single, unified upload
// endpoint that dispatches logic based on the file's `Content-Type`,
// avoiding multiple similar route definitions.

/*
--- Cargo.toml dependencies ---
[package]
name = "rocket_file_ops_v4"
version = "0.1.0"
edition = "2021"

[dependencies]
rocket = { version = "0.5.0", features = ["json", "tempfile"] }
serde = { version = "1.0", features = ["derive"] }
uuid = { version = "1.6", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
tokio = { version = "1", features = ["fs", "io"] }
csv = "1.3"
image = "0.24"
*/

#[macro_use]
extern crate rocket;

use rocket::data::{self, FromData, Outcome};
use rocket::fs::TempFile;
use rocket::http::{ContentType, Status};
use rocket::response::{self, Responder, Response};
use rocket::serde::json::{json, Json, Value};
use rocket::serde::{Deserialize, Serialize};
use rocket::response::stream::ReaderStream;
use rocket::Request;
use tokio::fs::File;
use std::io::Cursor;
use uuid::Uuid;
use chrono::{DateTime, Utc};

// --- Domain Models ---
#[derive(Debug, Serialize, Deserialize)] #[serde(crate = "rocket::serde")] pub enum Role { ADMIN, USER }
#[derive(Debug, Serialize, Deserialize)] #[serde(crate = "rocket::serde")] pub enum PostStatus { DRAFT, PUBLISHED }
#[derive(Debug, Serialize, Deserialize)] #[serde(crate = "rocket::serde")]
pub struct User { id: Uuid, email: String, role: Role, is_active: bool, created_at: DateTime<Utc> }

// --- Minimalist Error Handling ---
#[derive(Debug)]
struct ApiError(Status, String);

impl<'r> Responder<'r, 'static> for ApiError {
    fn respond_to(self, req: &'r Request<'_>) -> response::Result<'static> {
        Response::build_from(json!({ "error": self.1 }).respond_to(req)?)
            .status(self.0)
            .ok()
    }
}

// A macro to convert any error into our ApiError, reducing boilerplate.
macro_rules! map_err {
    ($result:expr, $status:expr) => {
        $result.map_err(|e| ApiError($status, e.to_string()))
    };
}

// --- Generic Upload Handler using a custom FromData implementation ---
enum UploadedContent {
    UserCsv(TempFile<'static>),
    PostImage(TempFile<'static>),
}

#[rocket::async_trait]
impl<'r> FromData<'r> for UploadedContent {
    type Error = ApiError;

    async fn from_data(req: &'r Request<'_>, data: data::Data<'r>) -> data::Outcome<'r, Self> {
        let content_type = req.content_type().ok_or(ApiError(Status::BadRequest, "No Content-Type provided.".into()))?;
        
        let temp_file = match TempFile::from_data(req, data).await {
            Outcome::Success(f) => f,
            Outcome::Failure((s, e)) => return Outcome::Failure((s, ApiError(s, e.to_string()))),
            Outcome::Forward(d) => return Outcome::Forward(d),
        };

        match (content_type.media_type().essense(), content_type.media_type().sub()) {
            ("text", "csv") => Outcome::Success(UploadedContent::UserCsv(temp_file)),
            ("image", "png") | ("image", "jpeg") => Outcome::Success(UploadedContent::PostImage(temp_file)),
            _ => Outcome::Failure((Status::UnsupportedMediaType, ApiError(Status::UnsupportedMediaType, "Unsupported file type.".into()))),
        }
    }
}

/// A single endpoint to handle different types of file uploads.
#[post("/upload", data = "<upload>")]
async fn unified_upload_handler(upload: UploadedContent) -> Result<Json<Value>, ApiError> {
    match upload {
        UploadedContent::UserCsv(file) => {
            let path = file.path().ok_or(ApiError(Status::InternalServerError, "Temporary file path is missing.".into()))?;
            let users: Vec<User> = map_err!(csv::Reader::from_path(path), Status::InternalServerError)?
                .deserialize()
                .collect::<Result<_,_>>()
                .map_err(|e| ApiError(Status::BadRequest, e.to_string()))?;
            
            Ok(json!({ "status": "ok", "imported_users": users.len() }))
        }
        UploadedContent::PostImage(file) => {
            let path = file.path().ok_or(ApiError(Status::InternalServerError, "Temporary file path is missing.".into()))?;
            let new_path = path.with_extension("thumbnail.jpg");
            
            let img = map_err!(image::open(path), Status::BadRequest)?;
            let thumb = img.thumbnail(150, 150);
            map_err!(thumb.save(&new_path), Status::InternalServerError)?;

            Ok(json!({ "status": "ok", "thumbnail_path": new_path }))
        }
    }
}

/// A generic download endpoint.
#[get("/download/<file_key>")]
async fn generic_download(file_key: &str) -> Result<ReaderStream![File], ApiError> {
    // In a real app, `file_key` would be an ID to look up a path in a database.
    // Here, we mock it and sanitize the input.
    let file_name = match file_key {
        "user_report" => "report.csv",
        "post_content" => "content.txt",
        _ => return Err(ApiError(Status::NotFound, "File key not found.".into())),
    };

    let path = std::env::temp_dir().join(file_name);
    let content = format!("This is the content for {}. ", file_key).repeat(5000);
    map_err!(tokio::fs::write(&path, content).await, Status::InternalServerError)?;

    let file = map_err!(File::open(&path).await, Status::NotFound)?;
    Ok(ReaderStream::new(file))
}

#[launch]
fn rocket() -> _ {
    rocket::build().mount("/", routes![unified_upload_handler, generic_download])
}