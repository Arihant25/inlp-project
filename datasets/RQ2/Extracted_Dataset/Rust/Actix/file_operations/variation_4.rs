/*
[dependencies]
actix-web = "4"
actix-multipart = "0.6"
tokio = { version = "1", features = ["macros", "rt-multi-thread"] }
futures-util = "0.3"
uuid = { version = "1", features = ["v4", "serde"] }
serde = { version = "1", features = ["derive"] }
chrono = { version = "0.4", features = ["serde"] }
image = "0.24"
tempfile = "3.3"
bytes = "1"
dashmap = "5"
csv-async = "1.2"
async-stream = "0.3"
*/

use actix_multipart::Multipart;
use actix_web::{web, App, Error, HttpResponse, HttpServer};
use async_stream::stream;
use bytes::Bytes;
use chrono::{DateTime, Utc};
use csv_async::{AsyncReaderBuilder, Trim};
use dashmap::DashMap;
use futures_util::stream::StreamExt;
use futures_util::TryStreamExt;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use uuid::Uuid;

// --- Domain Models (concise) ---

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

// --- App State using DashMap for concurrent access ---
type AppDb = Arc<DashMap<Uuid, User>>;

// --- Minimalist, Stream-focused Handlers ---

/// Processes a CSV upload stream directly into the in-memory DB without saving to a temp file.
async fn handle_csv_upload(mut payload: Multipart, db: web::Data<AppDb>) -> Result<HttpResponse, Error> {
    let mut field = payload.try_next().await?
        .ok_or_else(|| actix_web::error::ErrorBadRequest("No field in multipart"))?;

    let mut reader = AsyncReaderBuilder::new()
        .trim(Trim::All)
        .create_deserializer(field);

    let mut records = reader.deserialize::<User>();
    let mut count = 0;
    while let Some(record) = records.next().await {
        let user: User = record.map_err(|e| actix_web::error::ErrorBadRequest(e.to_string()))?;
        db.insert(user.id, user);
        count += 1;
    }

    Ok(HttpResponse::Ok().json(serde_json::json!({"imported": count})))
}

/// Processes an image upload in-memory and saves a resized version.
async fn handle_image_upload(mut payload: Multipart) -> Result<HttpResponse, Error> {
    let field = payload.try_next().await?
        .ok_or_else(|| actix_web::error::ErrorBadRequest("No field in multipart"))?;

    let bytes = field
        .try_fold(web::BytesMut::new(), |mut acc, chunk| async move {
            acc.extend_from_slice(&chunk);
            Ok(acc)
        })
        .await?
        .freeze();

    let image = image::load_from_memory(&bytes)
        .map_err(actix_web::error::ErrorInternalServerError)?;
    
    let resized = image.resize(200, 200, image::imageops::FilterType::Nearest);
    
    let temp_path = tokio::task::spawn_blocking(move || {
        let mut temp_file = tempfile::Builder::new().suffix(".webp").tempfile().unwrap();
        resized.write_to(&mut temp_file, image::ImageOutputFormat::WebP).unwrap();
        temp_file.keep()
    }).await.unwrap().map_err(actix_web::error::ErrorInternalServerError)?;

    Ok(HttpResponse::Ok().json(serde_json::json!({
        "path": temp_path.1.to_str()
    })))
}

/// Streams a CSV report generated on-the-fly without creating a file.
async fn stream_user_report(db: web::Data<AppDb>) -> HttpResponse {
    let db_clone = db.clone();

    let body = stream! {
        // CSV header
        let header = "id,email,role,is_active,created_at\n";
        yield Ok::<_, Error>(Bytes::from(header));

        for item in db_clone.iter() {
            let user = item.value();
            // This is a simplified CSV serialization for demonstration.
            // A proper async CSV writer would be better for production.
            let role_str = match user.role {
                UserRole::ADMIN => "ADMIN",
                UserRole::USER => "USER",
            };
            let line = format!(
                "{},{},{},{},{}\n",
                user.id, user.email, role_str, user.is_active, user.created_at
            );
            yield Ok::<_, Error>(Bytes::from(line));
        }
    };

    HttpResponse::Ok()
        .content_type("text/csv")
        .insert_header(("Content-Disposition", "attachment; filename=\"live_report.csv\""))
        .streaming(body)
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let db: AppDb = Arc::new(DashMap::new());
    
    // Add a sample user
    let sample_user = User {
        id: Uuid::new_v4(),
        email: "test@example.com".to_string(),
        role: UserRole::ADMIN,
        is_active: true,
        created_at: Utc::now(),
    };
    db.insert(sample_user.id, sample_user);

    println!("Server running at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(web::Data::new(db.clone()))
            .route("/ingest/users", web::post().to(handle_csv_upload))
            .route("/ingest/image", web::post().to(handle_image_upload))
            .route("/export/users", web::get().to(stream_user_report))
    })
    .bind("127.0.0.1:8080")?
    .run()
    .await
}