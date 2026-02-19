/*
[dependencies]
actix-web = "4"
actix-multipart = "0.6"
tokio = { version = "1", features = ["macros", "rt-multi-thread", "fs"] }
futures-util = "0.3"
uuid = { version = "1", features = ["v4", "serde"] }
serde = { version = "1", features = ["derive"] }
chrono = { version = "0.4", features = ["serde"] }
csv = "1.1"
image = "0.24"
tempfile = "3.3"
thiserror = "1.0"
bytes = "1"
*/

use actix_web::{web, App, HttpResponse, HttpServer, Responder, ResponseError};
use futures_util::stream::Stream;
use std::fmt::{Display, Formatter};
use std::path::Path;
use std::sync::{Arc, Mutex};

// --- Module: models ---
mod models {
    use super::*;
    use chrono::{DateTime, Utc};
    use serde::{Deserialize, Serialize};
    use uuid::Uuid;

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub enum UserRole { ADMIN, USER }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Serialize, Deserialize)]
    pub enum PostStatus { DRAFT, PUBLISHED }

    #[derive(Debug, Serialize, Deserialize)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }
}

// --- Module: errors ---
mod errors {
    use super::*;

    #[derive(Debug, thiserror::Error)]
    pub enum ServiceError {
        #[error("Internal processing error: {0}")]
        Internal(String),
        #[error("Bad request: {0}")]
        BadRequest(String),
        #[error("IO error: {0}")]
        Io(#[from] std::io::Error),
        #[error("CSV parsing error: {0}")]
        Csv(#[from] csv::Error),
        #[error("Image processing error: {0}")]
        Image(#[from] image::ImageError),
    }

    impl ResponseError for ServiceError {
        fn status_code(&self) -> actix_web::http::StatusCode {
            match self {
                ServiceError::Internal(_) | ServiceError::Io(_) | ServiceError::Csv(_) | ServiceError::Image(_) => {
                    actix_web::http::StatusCode::INTERNAL_SERVER_ERROR
                }
                ServiceError::BadRequest(_) => actix_web::http::StatusCode::BAD_REQUEST,
            }
        }
    }
}

// --- Module: services ---
mod services {
    use super::errors::ServiceError;
    use super::models::User;
    use bytes::Bytes;
    use futures_util::stream::Stream;
    use std::path::{Path, PathBuf};
    use tokio::fs::File;
    use tokio::io::AsyncWriteExt;
    use uuid::Uuid;

    pub struct UserService {
        db: Arc<Mutex<Vec<User>>>,
    }

    impl UserService {
        pub fn new(db: Arc<Mutex<Vec<User>>>) -> Self {
            Self { db }
        }

        pub async fn import_from_csv(&self, file_path: &Path) -> Result<usize, ServiceError> {
            let file = std::fs::File::open(file_path)?;
            let mut rdr = csv::Reader::from_reader(file);
            let mut imported_count = 0;
            let mut users = self.db.lock().map_err(|e| ServiceError::Internal(e.to_string()))?;
            for result in rdr.deserialize() {
                let user: User = result?;
                users.push(user);
                imported_count += 1;
            }
            Ok(imported_count)
        }
    }

    pub struct ImageService;

    impl ImageService {
        pub async fn process_and_save(
            &self,
            image_stream: impl Stream<Item = Result<Bytes, actix_multipart::MultipartError>>,
        ) -> Result<PathBuf, ServiceError> {
            use futures_util::TryStreamExt;
            let body = image_stream.try_to_vec().await.map_err(|e| ServiceError::BadRequest(e.to_string()))?;
            let image = image::load_from_memory(&body)?;
            let resized = image.resize_to_fill(400, 400, image::imageops::FilterType::Triangle);
            
            let temp_dir = std::env::temp_dir();
            let file_name = format!("resized_{}.jpg", Uuid::new_v4());
            let path = temp_dir.join(file_name);
            resized.save(&path)?;
            Ok(path)
        }
    }

    pub struct ReportService {
        db: Arc<Mutex<Vec<User>>>,
    }

    impl ReportService {
        pub fn new(db: Arc<Mutex<Vec<User>>>) -> Self {
            Self { db }
        }

        pub async fn generate_user_report_stream(&self) -> Result<impl Stream<Item = Result<Bytes, ServiceError>>, ServiceError> {
            let users = self.db.lock().map_err(|e| ServiceError::Internal(e.to_string()))?.clone();
            
            let stream = async_stream::stream! {
                let mut wtr = csv::WriterBuilder::new().from_writer(vec![]);
                
                if let Err(e) = wtr.serialize(("id", "email", "role", "is_active", "created_at")) {
                    yield Err(ServiceError::Csv(e));
                    return;
                }

                for user in users {
                    if let Err(e) = wtr.serialize(&user) {
                        // Log error and continue
                        eprintln!("Failed to serialize user: {}", e);
                        continue;
                    }
                }

                match wtr.into_inner() {
                    Ok(data) => yield Ok(Bytes::from(data)),
                    Err(e) => yield Err(ServiceError::Csv(e.into_error())),
                }
            };

            Ok(stream)
        }
    }
}

// --- Module: handlers ---
mod handlers {
    use super::errors::ServiceError;
    use super::services::{ImageService, ReportService, UserService};
    use actix_multipart::Multipart;
    use actix_web::{web, HttpResponse, Responder};
    use futures_util::TryStreamExt;
    use tempfile::Builder;
    use tokio::io::AsyncWriteExt;

    pub async fn import_users_handler(
        mut payload: Multipart,
        user_service: web::Data<UserService>,
    ) -> Result<impl Responder, ServiceError> {
        let temp_dir = Builder::new().prefix("csv_upload").tempdir()?;
        let mut file_path = None;

        while let Some(mut field) = payload.try_next().await.map_err(|e| ServiceError::BadRequest(e.to_string()))? {
            let content_disposition = field.content_disposition();
            let filename = content_disposition.get_filename().unwrap_or("file.csv");
            let path = temp_dir.path().join(filename);
            let mut f = tokio::fs::File::create(&path).await?;
            
            while let Some(chunk) = field.try_next().await.map_err(|e| ServiceError::BadRequest(e.to_string()))? {
                f.write_all(&chunk).await?;
            }
            file_path = Some(path);
            break;
        }

        if let Some(path) = file_path {
            let count = user_service.import_from_csv(&path).await?;
            Ok(HttpResponse::Ok().json(serde_json::json!({ "users_imported": count })))
        } else {
            Err(ServiceError::BadRequest("CSV file not provided".to_string()))
        }
    }

    pub async fn upload_post_thumbnail_handler(
        mut payload: Multipart,
        image_service: web::Data<ImageService>,
    ) -> Result<impl Responder, ServiceError> {
        while let Some(field) = payload.try_next().await.map_err(|e| ServiceError::BadRequest(e.to_string()))? {
            let content_type = field.content_type();
            if content_type.map_or(false, |ct| ct.type_() == "image") {
                let path = image_service.process_and_save(field).await?;
                return Ok(HttpResponse::Ok().json(serde_json::json!({ "resized_image_path": path })));
            }
        }
        Err(ServiceError::BadRequest("Image file not provided".to_string()))
    }

    pub async fn download_user_report_handler(
        report_service: web::Data<ReportService>,
    ) -> Result<impl Responder, ServiceError> {
        let stream = report_service.generate_user_report_stream().await?;
        Ok(HttpResponse::Ok()
            .content_type("text/csv")
            .insert_header(("Content-Disposition", "attachment; filename=user_report.csv"))
            .streaming(stream))
    }
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let user_db = Arc::new(Mutex::new(vec![]));

    let user_service = web::Data::new(services::UserService::new(user_db.clone()));
    let image_service = web::Data::new(services::ImageService);
    let report_service = web::Data::new(services::ReportService::new(user_db.clone()));

    println!("Server running at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(user_service.clone())
            .app_data(image_service.clone())
            .app_data(report_service.clone())
            .service(
                web::scope("/api")
                    .route("/users/import", web::post().to(handlers::import_users_handler))
                    .route("/posts/thumbnail", web::post().to(handlers::upload_post_thumbnail_handler))
                    .route("/reports/users", web::get().to(handlers::download_user_report_handler)),
            )
    })
    .bind("127.0.0.1:8080")?
    .run()
    .await
}