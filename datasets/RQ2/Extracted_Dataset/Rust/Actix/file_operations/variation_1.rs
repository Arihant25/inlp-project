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
serde_json = "1.0"
std::sync::Mutex = "1.0"
*/

use actix_multipart::Multipart;
use actix_web::{web, App, Error, HttpResponse, HttpServer, Responder};
use chrono::{DateTime, Utc};
use futures_util::stream::StreamExt as _;
use serde::{Deserialize, Serialize};
use std::fs::File;
use std::io::Write;
use std::sync::Mutex;
use tempfile::NamedTempFile;
use uuid::Uuid;

// --- Domain Models ---

#[derive(Debug, Serialize, Deserialize, Clone)]
enum UserRole {
    ADMIN,
    USER,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
struct User {
    id: Uuid,
    email: String,
    // password_hash is omitted for this example
    role: UserRole,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize)]
enum PostStatus {
    DRAFT,
    PUBLISHED,
}

#[derive(Debug, Serialize, Deserialize)]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Application State ---

struct AppState {
    users: Mutex<Vec<User>>,
}

// --- Helper for mapping errors ---

fn map_io_error(e: impl std::fmt::Display) -> Error {
    actix_web::error::ErrorInternalServerError(e.to_string())
}

// --- Handlers (Functional Style) ---

async fn upload_users_csv(
    mut payload: Multipart,
    app_state: web::Data<AppState>,
) -> Result<HttpResponse, Error> {
    let mut user_count = 0;
    while let Some(item) = payload.next().await {
        let mut field = item?;
        let content_disposition = field.content_disposition();
        let filename = content_disposition.get_filename().unwrap_or("unknown");

        if filename.ends_with(".csv") {
            let mut temp_file = NamedTempFile::new().map_err(map_io_error)?;
            
            while let Some(chunk) = field.next().await {
                let data = chunk?;
                temp_file.write_all(&data).map_err(map_io_error)?;
            }

            let path = temp_file.path().to_path_buf();
            let file = File::open(path).map_err(map_io_error)?;
            let mut rdr = csv::Reader::from_reader(file);

            let mut users_guard = app_state.users.lock().unwrap();
            for result in rdr.deserialize() {
                let user_record: User = result.map_err(map_io_error)?;
                users_guard.push(user_record);
                user_count += 1;
            }
        }
    }

    Ok(HttpResponse::Ok().json(serde_json::json!({
        "message": format!("Successfully uploaded and processed CSV. Added {} users.", user_count)
    })))
}

async fn upload_post_image(mut payload: Multipart) -> Result<HttpResponse, Error> {
    let mut image_data = Vec::new();
    let mut saved_path = String::new();

    while let Some(item) = payload.next().await {
        let mut field = item?;
        let content_type = field.content_type();

        if content_type.map_or(false, |ct| ct.type_() == "image") {
            while let Some(chunk) = field.next().await {
                image_data.extend_from_slice(&chunk?);
            }

            let img = image::load_from_memory(&image_data).map_err(map_io_error)?;
            let resized_img = img.resize(300, 300, image::imageops::FilterType::Lanczos3);

            let temp_dir = std::env::temp_dir();
            let file_name = format!("{}.png", Uuid::new_v4());
            let path = temp_dir.join(&file_name);
            
            resized_img.save(&path).map_err(map_io_error)?;
            saved_path = path.to_string_lossy().to_string();
            break; // Process only the first image
        }
    }

    if saved_path.is_empty() {
        return Ok(HttpResponse::BadRequest().body("No image found in upload."));
    }

    Ok(HttpResponse::Ok().json(serde_json::json!({
        "message": "Image resized and saved successfully.",
        "path": saved_path
    })))
}

async fn download_user_report(app_state: web::Data<AppState>) -> impl Responder {
    let temp_file = match NamedTempFile::new() {
        Ok(f) => f,
        Err(_) => return HttpResponse::InternalServerError().finish(),
    };

    let file_path = temp_file.path().to_path_buf();
    
    {
        let file = match File::create(&file_path) {
            Ok(f) => f,
            Err(_) => return HttpResponse::InternalServerError().finish(),
        };
        let mut wtr = csv::Writer::from_writer(file);
        let users_guard = app_state.users.lock().unwrap();

        // Write header
        if wtr.write_record(&["id", "email", "role", "is_active", "created_at"]).is_err() {
            return HttpResponse::InternalServerError().finish();
        }

        for user in users_guard.iter() {
            if wtr.serialize(user).is_err() {
                // Log error, but continue if possible
            }
        }
        if wtr.flush().is_err() {
            return HttpResponse::InternalServerError().finish();
        }
    }

    match actix_files::NamedFile::open(file_path) {
        Ok(named_file) => named_file
            .into_response(&actix_web::HttpRequest::new(
                actix_web::http::Method::GET,
                actix_web::http::Uri::default(),
                actix_web::http::Version::HTTP_11,
                actix_web::http::HeaderMap::new(),
                actix_web::dev::Payload::None,
            ))
            .map(|res| {
                res.into_builder()
                    .insert_header(("Content-Disposition", "attachment; filename=\"user_report.csv\""))
                    .finish()
            }),
        Err(_) => HttpResponse::InternalServerError().finish(),
    }
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let app_state = web::Data::new(AppState {
        users: Mutex::new(vec![
            User {
                id: Uuid::new_v4(),
                email: "initial.user@example.com".to_string(),
                role: UserRole::ADMIN,
                is_active: true,
                created_at: Utc::now(),
            }
        ]),
    });

    println!("Server running at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(app_state.clone())
            .route("/users/upload_csv", web::post().to(upload_users_csv))
            .route("/posts/upload_image", web::post().to(upload_post_image))
            .route("/reports/users", web::get().to(download_user_report))
    })
    .bind("127.0.0.1:8080")?
    .run()
    .await
}