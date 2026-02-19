// Variation 3: The "Async-First & Modern" Developer
// This developer prioritizes non-blocking I/O and modern async patterns.
// They use `tokio::task::spawn_blocking` to move CPU-bound work (like image resizing)
// off the main async runtime, preventing it from being blocked.
// Asynchronous libraries like `csv-async` are preferred for I/O-bound tasks.
// Application-wide resources are managed via Rocket's `State`.

/*
--- Cargo.toml dependencies ---
[package]
name = "rocket_file_ops_v3"
version = "0.1.0"
edition = "2021"

[dependencies]
rocket = { version = "0.5.0", features = ["json", "tempfile"] }
serde = { version = "1.0", features = ["derive"] }
uuid = { version = "1.6", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
tokio = { version = "1", features = ["full"] }
image = "0.24"
anyhow = "1.0"
csv-async = "1.2"
futures = "0.3"
tokio-util = { version = "0.7", features = ["compat"] }
*/

#[macro_use]
extern crate rocket;

use rocket::fs::TempFile;
use rocket::http::{ContentType, Status};
use rocket::response::{self, Responder, Response};
use rocket::serde::json::{Json, json};
use rocket::serde::{Deserialize, Serialize};
use rocket::{Request, State};
use rocket::response::stream::ReaderStream;
use tokio::fs::File;
use tokio_util::compat::TokioAsyncReadCompatExt;
use uuid::Uuid;
use chrono::{DateTime, Utc};
use futures::stream::TryStreamExt;
use std::io::Cursor;
use std::path::PathBuf;

// --- Custom Error & Responder using `anyhow` ---
#[derive(Debug)]
struct AppError(anyhow::Error);
type AppResult<T> = Result<T, AppError>;

impl<E: Into<anyhow::Error>> From<E> for AppError {
    fn from(err: E) -> Self { Self(err.into()) }
}

#[rocket::async_trait]
impl<'r> Responder<'r, 'static> for AppError {
    fn respond_to(self, _: &'r Request<'_>) -> response::Result<'static> {
        let body = json!({ "error": self.0.to_string() }).to_string();
        Response::build()
            .status(Status::InternalServerError)
            .header(ContentType::JSON)
            .sized_body(body.len(), Cursor::new(body))
            .ok()
    }
}

// --- Domain Models ---
#[derive(Debug, Serialize, Deserialize)] #[serde(crate = "rocket::serde")] enum Role { ADMIN, USER }
#[derive(Debug, Serialize, Deserialize)] #[serde(crate = "rocket::serde")] enum PostStatus { DRAFT, PUBLISHED }
#[derive(Debug, Serialize, Deserialize)] #[serde(crate = "rocket::serde")]
struct User { id: Uuid, email: String, role: Role, is_active: bool, created_at: DateTime<Utc> }

// --- Application State ---
// Manages shared resources, like a (mocked) DB pool or configuration.
struct AppState {
    storage_path: PathBuf,
}

impl AppState {
    fn new() -> Self {
        let storage_path = std::env::temp_dir().join("app_storage_v3");
        std::fs::create_dir_all(&storage_path).expect("Failed to create storage dir");
        AppState { storage_path }
    }
}

// --- Route Handlers ---

/// Parses a CSV of users using a fully asynchronous CSV reader.
#[post("/users/import", format = "csv", data = "<file>")]
async fn import_users_async(file: TempFile<'_>) -> AppResult<Json<Vec<Uuid>>> {
    let file_handle = File::open(file.path().unwrap()).await?;
    let mut rdr = csv_async::AsyncReader::from_reader(file_handle.compat());

    let users: Vec<User> = rdr.deserialize::<User>().try_collect().await?;

    // In a real app, this would be an async DB insert operation.
    let user_ids = users.into_iter().map(|u| u.id).collect();
    Ok(Json(user_ids))
}

/// Processes an image in a non-blocking way.
#[post("/posts/<_post_id>/image", data = "<file>")]
async fn process_image_non_blocking(
    _post_id: Uuid,
    file: TempFile<'_>,
    state: &State<AppState>,
) -> AppResult<(ContentType, Vec<u8>)> {
    let temp_path = file.path().unwrap().to_path_buf();
    let storage_path = state.storage_path.clone();

    // Image processing is CPU-bound. Move it to a blocking thread pool
    // to avoid stalling the async runtime.
    let image_data = tokio::task::spawn_blocking(move || -> anyhow::Result<Vec<u8>> {
        let img = image::open(&temp_path)?;
        let resized = img.resize_to_fill(400, 400, image::imageops::FilterType::Triangle);
        
        let final_path = storage_path.join("processed_image.jpg");
        resized.save(&final_path)?;

        let mut buffer = Cursor::new(Vec::new());
        resized.write_to(&mut buffer, image::ImageOutputFormat::Jpeg(85))?;
        Ok(buffer.into_inner())
    }).await??;

    Ok((ContentType::JPEG, image_data))
}

/// Streams a file from storage managed by the application state.
#[get("/posts/<_post_id>/download")]
async fn download_file_from_state(_post_id: Uuid, state: &State<AppState>) -> AppResult<ReaderStream![File]> {
    // Mock finding a file path from a database.
    let file_path = state.storage_path.join("sample_download.txt");
    if !file_path.exists() {
        let content = "Async streaming content from app state. ".repeat(10000);
        tokio::fs::write(&file_path, content).await?;
    }

    let file = File::open(file_path).await?;
    Ok(ReaderStream::new(file))
}

#[launch]
fn rocket() -> _ {
    rocket::build()
        .manage(AppState::new())
        .mount("/", routes![
            import_users_async,
            process_image_non_blocking,
            download_file_from_state
        ])
}