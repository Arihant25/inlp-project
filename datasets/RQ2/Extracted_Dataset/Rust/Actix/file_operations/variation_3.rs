/*
[dependencies]
actix-web = "4"
actix-multipart = "0.6"
tokio = { version = "1", features = ["macros", "rt-multi-thread"] }
futures-util = "0.3"
uuid = { version = "1", features = ["v4", "serde"] }
serde = { version = "1", features = ["derive"] }
chrono = { version = "0.4", features = ["serde"] }
csv = "1.1"
image = "0.24"
tempfile = "3.3"
thiserror = "1.0"
anyhow = "1.0"
*/

use actix_multipart::Multipart;
use actix_web::{web, App, HttpResponse, HttpServer, Responder, ResponseError};
use chrono::{DateTime, Utc};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::Write;
use std::sync::{Arc, RwLock};
use thiserror::Error;
use uuid::Uuid;

// --- Domain Models ---

#[derive(Debug, Serialize, Deserialize, Clone)]
enum UserRole { ADMIN, USER }

#[derive(Debug, Serialize, Deserialize, Clone)]
struct User {
    id: Uuid,
    email: String,
    role: UserRole,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize)]
enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug, Serialize, Deserialize)]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Custom Error Type ---

#[derive(Debug, Error)]
enum ApiError {
    #[error("Invalid input: {0}")]
    Validation(String),
    #[error("An internal error occurred. Please try again later.")]
    Internal(#[from] anyhow::Error),
    #[error("Resource not found")]
    NotFound,
}

impl ResponseError for ApiError {
    fn status_code(&self) -> actix_web::http::StatusCode {
        match self {
            ApiError::Validation(_) => actix_web::http::StatusCode::BAD_REQUEST,
            ApiError::Internal(_) => actix_web::http::StatusCode::INTERNAL_SERVER_ERROR,
            ApiError::NotFound => actix_web::http::StatusCode::NOT_FOUND,
        }
    }
}

// --- Application State ---

type Db = Arc<RwLock<HashMap<Uuid, User>>>;

// --- Controller Struct for Handler Organization ---

struct FileApiController;

impl FileApiController {
    /// Handles uploading a CSV of users and populating the database.
    async fn upload_csv(
        mut payload: Multipart,
        db: web::Data<Db>,
    ) -> Result<HttpResponse, ApiError> {
        let field = payload
            .next()
            .await
            .ok_or_else(|| ApiError::Validation("Multipart payload is empty.".to_string()))?
            .map_err(|e| ApiError::Internal(anyhow::anyhow!(e)))?;

        let mut temp_file = tempfile::tempfile()?;
        let mut field_stream = field;
        while let Some(chunk) = field_stream.next().await {
            let data = chunk.map_err(|e| ApiError::Internal(anyhow::anyhow!(e)))?;
            temp_file.write_all(&data)?;
        }
        
        temp_file.seek(std::io::SeekFrom::Start(0))?;
        
        let mut rdr = csv::Reader::from_reader(temp_file);
        let mut users_added = 0;
        let mut db_lock = db.write().map_err(|_| ApiError::Internal(anyhow::anyhow!("DB lock poisoned")))?;

        for result in rdr.deserialize::<User>() {
            let user = result.map_err(|e| ApiError::Validation(format!("CSV parsing error: {}", e)))?;
            db_lock.insert(user.id, user);
            users_added += 1;
        }

        Ok(HttpResponse::Created().json(serde_json::json!({
            "status": "success",
            "users_added": users_added
        })))
    }

    /// Handles uploading an image, resizing it, and storing it temporarily.
    async fn process_image(mut payload: Multipart) -> Result<HttpResponse, ApiError> {
        let field = payload
            .next()
            .await
            .ok_or_else(|| ApiError::Validation("Multipart payload is empty.".to_string()))?
            .map_err(|e| ApiError::Internal(anyhow::anyhow!(e)))?;

        let mut bytes = Vec::new();
        let mut field_stream = field;
        while let Some(chunk) = field_stream.next().await {
            bytes.extend_from_slice(&chunk.map_err(|e| ApiError::Internal(anyhow::anyhow!(e)))?);
        }

        let image = image::load_from_memory(&bytes)
            .map_err(|e| ApiError::Validation(format!("Invalid image format: {}", e)))?;
        
        let thumbnail = image.thumbnail(150, 150);
        let mut temp_file = tempfile::Builder::new().suffix(".png").tempfile()?;
        thumbnail.write_to(&mut temp_file, image::ImageOutputFormat::Png)?;
        
        let path = temp_file.path().to_string_lossy().to_string();

        Ok(HttpResponse::Ok().json(serde_json::json!({
            "status": "success",
            "thumbnail_path": path
        })))
    }

    /// Streams a CSV report of all users in the database.
    async fn stream_download(db: web::Data<Db>) -> Result<impl Responder, ApiError> {
        let users = db.read()
            .map_err(|_| ApiError::Internal(anyhow::anyhow!("DB lock poisoned")))?
            .values()
            .cloned()
            .collect::<Vec<User>>();

        let (tx, rx) = tokio::sync::mpsc::channel::<Result<web::Bytes, std::io::Error>>(10);

        tokio::spawn(async move {
            let mut wtr = csv::WriterBuilder::new().from_writer(Vec::new());
            if wtr.serialize(("id", "email", "role", "is_active", "created_at")).is_err() {
                let _ = tx.send(Err(std::io::Error::new(std::io::ErrorKind::Other, "CSV header write failed"))).await;
                return;
            }
            let header_bytes = wtr.into_inner().unwrap();
            if tx.send(Ok(web::Bytes::from(header_bytes))).await.is_err() { return; }

            for user in users {
                let mut wtr = csv::WriterBuilder::new().from_writer(Vec::new());
                if wtr.serialize(user).is_err() { continue; }
                let record_bytes = wtr.into_inner().unwrap();
                if tx.send(Ok(web::Bytes::from(record_bytes))).await.is_err() {
                    break; // Client disconnected
                }
            }
        });

        let body = actix_web::body::BodyStream::new(rx);

        Ok(HttpResponse::Ok()
            .content_type("text/csv")
            .insert_header(("Content-Disposition", "attachment; filename=\"report.csv\""))
            .body(body))
    }
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let database = web::Data::new(Arc::new(RwLock::new(HashMap::<Uuid, User>::new())));

    println!("Server running at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(database.clone())
            .service(
                web::scope("/files")
                    .route("/upload_csv", web::post().to(FileApiController::upload_csv))
                    .route("/process_image", web::post().to(FileApiController::process_image))
                    .route("/download_report", web::get().to(FileApiController::stream_download)),
            )
    })
    .bind("127.0.0.1:8080")?
    .run()
    .await
}