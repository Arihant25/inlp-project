// Variation 1: The "Straightforward Functional" Developer
// All logic is contained within the route handlers in a single file.
// This approach is simple and direct, making it easy to understand for small projects,
// but can become difficult to maintain and test as the application grows.

/*
--- Cargo.toml dependencies ---
[package]
name = "rocket_file_ops_v1"
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

use rocket::data::{Data, ToByteUnit};
use rocket::fs::TempFile;
use rocket::http::{ContentType, Status};
use rocket::response::stream::ReaderStream;
use rocket::serde::json::Json;
use rocket::serde::{Deserialize, Serialize};
use rocket::tokio::fs::File;
use std::io::Cursor;
use uuid::Uuid;
use chrono::{DateTime, Utc};

// --- Domain Models ---

#[derive(Debug, Serialize, Deserialize)]
#[serde(crate = "rocket::serde")]
pub enum Role {
    ADMIN,
    USER,
}

#[derive(Debug, Serialize)]
#[serde(crate = "rocket::serde")]
pub enum PostStatus {
    DRAFT,
    PUBLISHED,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(crate = "rocket::serde")]
pub struct User {
    id: Uuid,
    email: String,
    // password_hash is omitted for simplicity
    role: Role,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize)]
#[serde(crate = "rocket::serde")]
pub struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Route Handlers ---

/// Handles CSV file uploads to bulk-import users.
#[post("/users/import_csv", format = "csv", data = "<file>")]
async fn upload_and_parse_user_csv(mut file: TempFile<'_>) -> Result<Json<Vec<User>>, (Status, String)> {
    let path = file.path().ok_or((Status::InternalServerError, "Could not get temp file path.".to_string()))?;

    // Use the `csv` crate to parse the file directly from its temporary path.
    let mut reader = csv::Reader::from_path(path)
        .map_err(|e| (Status::InternalServerError, format!("Failed to create CSV reader: {}", e)))?;

    let mut imported_users = Vec::new();
    for result in reader.deserialize() {
        let user: User = result.map_err(|e| (Status::BadRequest, format!("Failed to deserialize user record: {}", e)))?;
        imported_users.push(user);
    }

    // In a real application, you would now persist these users to a database.
    Ok(Json(imported_users))
}

/// Handles image uploads for a post, resizes it, and returns the processed image.
#[post("/posts/<_post_id>/image", data = "<data>")]
async fn upload_and_resize_post_image(_post_id: Uuid, data: Data<'_>) -> Result<(ContentType, Vec<u8>), (Status, String)> {
    // Persist the uploaded data to a temporary file for processing.
    let temp_file_path = std::env::temp_dir().join(format!("{}.upload", Uuid::new_v4()));
    let write_result = data.open(10.mebibytes()).into_file(&temp_file_path).await;

    if let Err(e) = write_result {
        return Err((Status::InternalServerError, format!("Failed to save upload: {}", e)));
    } else if !write_result.unwrap().is_complete() {
        let _ = tokio::fs::remove_file(&temp_file_path).await;
        return Err((Status::PayloadTooLarge, "File exceeds 10 MiB limit.".to_string()));
    }

    // Use the `image` crate to open, resize, and re-encode the image.
    let img = image::open(&temp_file_path)
        .map_err(|e| (Status::BadRequest, format!("Failed to open image: {}", e)))?;

    let resized_img = img.resize(300, 300, image::imageops::FilterType::Lanczos3);

    // Write the processed image into an in-memory buffer to return in the response.
    let mut buffer = Cursor::new(Vec::new());
    resized_img.write_to(&mut buffer, image::ImageOutputFormat::Png)
        .map_err(|e| (Status::InternalServerError, format!("Failed to encode resized image: {}", e)))?;

    // Clean up the temporary file from disk.
    let _ = tokio::fs::remove_file(&temp_file_path).await;

    Ok((ContentType::PNG, buffer.into_inner()))
}

/// Streams a large file to the client as a download.
#[get("/posts/<_post_id>/content/download")]
async fn download_post_content_stream(_post_id: Uuid) -> Result<ReaderStream![File], (Status, String)> {
    // In a real app, you'd fetch a file path from a database.
    // Here, we create a dummy file on-the-fly for demonstration.
    let dummy_file_path = std::env::temp_dir().join("dummy_post_content.txt");
    let large_content = "This is the long content of a blog post that we are streaming to the client. ".repeat(10000);
    tokio::fs::write(&dummy_file_path, large_content).await
        .map_err(|e| (Status::InternalServerError, format!("Failed to create dummy file: {}", e)))?;

    let file = File::open(&dummy_file_path).await
        .map_err(|_| (Status::NotFound, "File not found.".to_string()))?;

    // Rocket's `ReaderStream` handles the streaming response efficiently.
    // Note: File cleanup for such temporary downloads should be handled carefully,
    // perhaps with a background job or a custom `Responder` that cleans up on drop.
    Ok(ReaderStream::new(file))
}

#[launch]
fn rocket() -> _ {
    rocket::build().mount("/", routes![
        upload_and_parse_user_csv,
        upload_and_resize_post_image,
        download_post_content_stream
    ])
}