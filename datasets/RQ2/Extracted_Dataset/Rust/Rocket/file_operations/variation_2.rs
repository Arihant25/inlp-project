// Variation 2: The "Service Layer Abstraction" Developer
// This developer separates concerns into distinct layers: routes, services, and models.
// Route handlers are thin, delegating all business logic to service modules.
// This pattern improves modularity, testability, and maintainability for larger applications.
// It uses a custom error type for cleaner error handling across layers.

// --- File: Cargo.toml (as a comment) ---
/*
[package]
name = "rocket_file_ops_v2"
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
thiserror = "1.0"
*/

// --- This would be in `src/main.rs` ---
#[macro_use]
extern crate rocket;

mod models;
mod errors;
mod services;
mod routes;

#[launch]
fn rocket() -> _ {
    rocket::build().mount(
        "/api",
        routes![
            routes::user_routes::import_users,
            routes::post_routes::upload_post_image,
            routes::post_routes::download_post_content
        ],
    )
}

// --- This would be in `src/models.rs` ---
pub mod models {
    use rocket::serde::{Deserialize, Serialize};
    use uuid::Uuid;
    use chrono::{DateTime, Utc};

    #[derive(Debug, Serialize, Deserialize, Clone)]
    #[serde(crate = "rocket::serde")]
    pub enum Role { ADMIN, USER }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    #[serde(crate = "rocket::serde")]
    pub enum PostStatus { DRAFT, PUBLISHED }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    #[serde(crate = "rocket::serde")]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        pub role: Role,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Serialize, Clone)]
    #[serde(crate = "rocket::serde")]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content_path: String, // Path to content file
        pub status: PostStatus,
    }
}

// --- This would be in `src/errors.rs` ---
pub mod errors {
    use rocket::http::{ContentType, Status};
    use rocket::response::{self, Responder, Response};
    use rocket::Request;
    use std::io::Cursor;
    use thiserror::Error;

    #[derive(Error, Debug)]
    pub enum AppError {
        #[error("I/O error: {0}")]
        Io(#[from] std::io::Error),
        #[error("CSV parsing error: {0}")]
        Csv(#[from] csv::Error),
        #[error("Image processing error: {0}")]
        Image(#[from] image::ImageError),
        #[error("File not found: {0}")]
        NotFound(String),
        #[error("Invalid input: {0}")]
        BadRequest(String),
        #[error("Internal server error")]
        Internal(String),
    }

    #[rocket::async_trait]
    impl<'r> Responder<'r, 'static> for AppError {
        fn respond_to(self, _: &'r Request<'_>) -> response::Result<'static> {
            let (status, body) = match self {
                AppError::Io(e) => (Status::InternalServerError, format!("IO Error: {}", e)),
                AppError::Internal(msg) => (Status::InternalServerError, msg),
                AppError::Csv(e) => (Status::BadRequest, format!("CSV Error: {}", e)),
                AppError::Image(e) => (Status::BadRequest, format!("Image Error: {}", e)),
                AppError::NotFound(msg) => (Status::NotFound, msg),
                AppError::BadRequest(msg) => (Status::BadRequest, msg),
            };
            Response::build()
                .status(status)
                .header(ContentType::Text)
                .sized_body(body.len(), Cursor::new(body))
                .ok()
        }
    }
}

// --- This would be in `src/services.rs` ---
pub mod services {
    use crate::errors::AppError;
    use crate::models::User;
    use std::path::{Path, PathBuf};
    use uuid::Uuid;
    use rocket::tokio::fs::File;

    pub struct UserService;
    impl UserService {
        pub fn import_from_csv(path: &Path) -> Result<Vec<User>, AppError> {
            let mut rdr = csv::Reader::from_path(path)?;
            let users = rdr.deserialize().collect::<Result<Vec<User>, _>>()?;
            Ok(users)
        }
    }

    pub struct ImageService;
    impl ImageService {
        pub async fn process_and_store(image_path: &Path, post_id: Uuid) -> Result<PathBuf, AppError> {
            let img = image::open(image_path)?;
            let resized = img.thumbnail(800, 800);
            let storage_dir = std::env::temp_dir().join("post_images");
            tokio::fs::create_dir_all(&storage_dir).await?;
            let new_path = storage_dir.join(format!("{}.webp", post_id));
            resized.save_with_format(&new_path, image::ImageFormat::WebP)?;
            Ok(new_path)
        }
    }

    pub struct FileService;
    impl FileService {
        async fn find_post_content_path(_post_id: Uuid) -> Result<PathBuf, AppError> {
            let temp_file_path = std::env::temp_dir().join("mock_post_content.txt");
            if !temp_file_path.exists() {
                let content = "This is content from the service layer. ".repeat(10000);
                tokio::fs::write(&temp_file_path, content).await?;
            }
            Ok(temp_file_path)
        }

        pub async fn get_post_content_stream(post_id: Uuid) -> Result<File, AppError> {
            let path = Self::find_post_content_path(post_id).await?;
            File::open(path).await.map_err(|e| AppError::NotFound(e.to_string()))
        }
    }
}

// --- This would be in `src/routes.rs` ---
pub mod routes {
    // Sub-modules for organization
    pub mod user_routes {
        use crate::errors::AppError;
        use crate::models::User;
        use crate::services::UserService;
        use rocket::fs::TempFile;
        use rocket::serde::json::Json;

        #[post("/users/import", format = "csv", data = "<file>")]
        pub fn import_users(file: TempFile<'_>) -> Result<Json<Vec<User>>, AppError> {
            let path = file.path().ok_or_else(|| AppError::Internal("Temp file path unavailable.".to_string()))?;
            let users = UserService::import_from_csv(path)?;
            Ok(Json(users))
        }
    }

    pub mod post_routes {
        use crate::errors::AppError;
        use crate::services::{FileService, ImageService};
        use rocket::fs::TempFile;
        use rocket::response::stream::ReaderStream;
        use rocket::tokio::fs::File;
        use uuid::Uuid;

        #[post("/posts/<post_id>/image", data = "<file>")]
        pub async fn upload_post_image(post_id: Uuid, file: TempFile<'_>) -> Result<String, AppError> {
            let path = file.path().ok_or_else(|| AppError::Internal("Temp file path unavailable.".to_string()))?;
            let stored_path = ImageService::process_and_store(path, post_id).await?;
            Ok(format!("Image stored at: {:?}", stored_path))
        }

        #[get("/posts/<post_id>/content")]
        pub async fn download_post_content(post_id: Uuid) -> Result<ReaderStream![File], AppError> {
            let file_handle = FileService::get_post_content_stream(post_id).await?;
            Ok(ReaderStream::new(file_handle))
        }
    }
}