/*
## Cargo.toml dependencies ##

[dependencies]
axum = "0.7"
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
csv = "1.3"
image = "0.25"
tempfile = "3.10"
futures-util = { version = "0.3", default-features = false, features = ["std"] }
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
axum-macros = "0.4"
thiserror = "1.0"
bytes = "1"
tokio-util = { version = "0.7", features = ["io"] }
*/

use axum::{
    body::Body,
    extract::{DefaultBodyLimit, Multipart, Path, State},
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use std::{
    collections::HashMap,
    net::SocketAddr,
    path::PathBuf,
    sync::{Arc, Mutex},
};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};
use uuid::Uuid;

// --- MODULES FOR LAYERED ARCHITECTURE ---

mod models {
    use chrono::{DateTime, Utc};
    use serde::{Deserialize, Serialize};
    use uuid::Uuid;

    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub enum UserRole { ADMIN, USER }

    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        pub password_hash: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub enum PostStatus { DRAFT, PUBLISHED }

    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }
}

mod errors {
    use axum::{http::StatusCode, response::{IntoResponse, Response}, Json};
    use serde_json::json;
    use thiserror::Error;

    #[derive(Error, Debug)]
    pub enum ServiceError {
        #[error("Database error: {0}")]
        Database(String),
        #[error("File processing error: {0}")]
        FileProcessing(#[from] std::io::Error),
        #[error("CSV parsing error: {0}")]
        Csv(#[from] csv::Error),
        #[error("Image processing error: {0}")]
        Image(#[from] image::ImageError),
        #[error("Invalid input: {0}")]
        Validation(String),
        #[error("Not found: {0}")]
        NotFound(String),
        #[error("Multipart error: {0}")]
        Multipart(#[from] axum::extract::multipart::MultipartError),
    }

    impl IntoResponse for ServiceError {
        fn into_response(self) -> Response {
            let (status, error_message) = match self {
                ServiceError::NotFound(msg) => (StatusCode::NOT_FOUND, msg),
                ServiceError::Validation(msg) => (StatusCode::BAD_REQUEST, msg),
                _ => (StatusCode::INTERNAL_SERVER_ERROR, self.to_string()),
            };
            let body = Json(json!({ "error": error_message }));
            (status, body).into_response()
        }
    }
}

mod services {
    use super::{errors::ServiceError, models::{Post, User, UserRole}};
    use bytes::Bytes;
    use chrono::Utc;
    use serde::Deserialize;
    use std::{
        collections::HashMap,
        io::Write,
        path::Path,
        sync::{Arc, Mutex},
    };
    use tempfile::NamedTempFile;
    use uuid::Uuid;

    type DbMock = Arc<Mutex<HashMap<Uuid, User>>>;

    #[derive(Clone)]
    pub struct UserService {
        db: DbMock,
    }

    impl UserService {
        pub fn new(db: DbMock) -> Self {
            Self { db }
        }

        pub fn import_from_csv(&self, csv_data: Bytes) -> Result<Vec<User>, ServiceError> {
            #[derive(Deserialize)]
            struct UserCsvRecord {
                email: String,
                role: String,
            }
            let mut rdr = csv::Reader::from_reader(csv_data.as_ref());
            let mut new_users = Vec::new();
            let mut db_lock = self.db.lock().map_err(|e| ServiceError::Database(e.to_string()))?;

            for result in rdr.deserialize::<UserCsvRecord>() {
                let record = result?;
                let user = User {
                    id: Uuid::new_v4(),
                    email: record.email,
                    password_hash: "default_hash".to_string(),
                    role: if record.role.to_uppercase() == "ADMIN" { UserRole::ADMIN } else { UserRole::USER },
                    is_active: true,
                    created_at: Utc::now(),
                };
                db_lock.insert(user.id, user.clone());
                new_users.push(user);
            }
            Ok(new_users)
        }
    }

    type PostDbMock = Arc<Mutex<HashMap<Uuid, Post>>>;

    #[derive(Clone)]
    pub struct PostService {
        db: PostDbMock,
        storage_path: Arc<Path>,
    }

    impl PostService {
        pub fn new(db: PostDbMock, storage_path: PathBuf) -> Self {
            Self { db, storage_path: Arc::from(storage_path) }
        }

        pub fn process_post_image(&self, post_id: Uuid, image_data: Bytes, content_type: &str) -> Result<String, ServiceError> {
            if !self.db.lock().unwrap().contains_key(&post_id) {
                return Err(ServiceError::NotFound("Post not found".to_string()));
            }

            let extension = match content_type {
                "image/jpeg" => "jpg",
                "image/png" => "png",
                _ => return Err(ServiceError::Validation("Unsupported image type".to_string())),
            };

            let image = image::load_from_memory(&image_data)?;
            let resized = image.resize(300, 300, image::imageops::FilterType::Lanczos3);
            
            let mut temp_file = NamedTempFile::new_in(self.storage_path.as_ref())?;
            let format = image::ImageFormat::from_extension(extension)
                .ok_or_else(|| ServiceError::Validation("Invalid image extension".to_string()))?;
            resized.write_to(&mut temp_file, format)?;

            let image_name = format!("{}.{}", post_id, extension);
            let final_path = self.storage_path.join(&image_name);
            temp_file.persist(&final_path)?;

            Ok(format!("/images/{}", image_name))
        }

        pub fn export_to_csv_stream(&self) -> Result<impl futures_util::Stream<Item = Result<Bytes, std::io::Error>>, ServiceError> {
            let posts = self.db.lock().unwrap().values().cloned().collect::<Vec<_>>();
            
            let (tx, rx) = tokio::sync::mpsc::channel(1);

            tokio::spawn(async move {
                let mut wtr = csv::WriterBuilder::new().from_writer(Vec::new());
                if wtr.write_record(&["id", "user_id", "title", "status"]).is_err() {
                    return;
                }
                if tx.send(Ok(Bytes::from(wtr.into_inner().unwrap()))).await.is_err() {
                    return;
                }

                for post in posts {
                    let mut wtr = csv::WriterBuilder::new().has_headers(false).from_writer(Vec::new());
                    if wtr.serialize((post.id, post.user_id, &post.title, &post.status)).is_err() {
                        continue;
                    }
                    if tx.send(Ok(Bytes::from(wtr.into_inner().unwrap()))).await.is_err() {
                        break;
                    }
                }
            });

            Ok(tokio_stream::wrappers::ReceiverStream::new(rx))
        }
    }
}

mod handlers {
    use super::{
        errors::ServiceError,
        models::{Post, User},
        services::{PostService, UserService},
    };
    use axum::{
        body::Body,
        extract::{Multipart, Path, State},
        http::header,
        response::IntoResponse,
        Json,
    };
    use std::sync::Arc;
    use uuid::Uuid;

    pub struct AppState {
        pub user_service: UserService,
        pub post_service: PostService,
        pub storage_path: PathBuf,
    }

    pub async fn upload_users_csv_handler(
        State(state): State<Arc<AppState>>,
        mut multipart: Multipart,
    ) -> Result<Json<Vec<Uuid>>, ServiceError> {
        while let Some(field) = multipart.next_field().await? {
            if field.name() == Some("users_file") {
                let data = field.bytes().await?;
                let new_users = state.user_service.import_from_csv(data)?;
                let ids = new_users.into_iter().map(|u| u.id).collect();
                return Ok(Json(ids));
            }
        }
        Err(ServiceError::Validation("Field 'users_file' not found".to_string()))
    }

    pub async fn upload_post_image_handler(
        State(state): State<Arc<AppState>>,
        Path(post_id): Path<Uuid>,
        mut multipart: Multipart,
    ) -> Result<Json<String>, ServiceError> {
        while let Some(field) = multipart.next_field().await? {
            if field.name() == Some("image") {
                let content_type = field.content_type().unwrap_or("").to_string();
                let data = field.bytes().await?;
                let image_url = state.post_service.process_post_image(post_id, data, &content_type)?;
                return Ok(Json(image_url));
            }
        }
        Err(ServiceError::Validation("Field 'image' not found".to_string()))
    }

    pub async fn download_posts_csv_handler(
        State(state): State<Arc<AppState>>,
    ) -> Result<impl IntoResponse, ServiceError> {
        let stream = state.post_service.export_to_csv_stream()?;
        let headers = [
            (header::CONTENT_TYPE, "text/csv; charset=utf-8"),
            (header::CONTENT_DISPOSITION, "attachment; filename=\"posts.csv\""),
        ];
        Ok((headers, Body::from_stream(stream)))
    }

    pub async fn serve_image_handler(
        State(state): State<Arc<AppState>>,
        Path(image_name): Path<String>,
    ) -> Result<impl IntoResponse, ServiceError> {
        let path = state.storage_path.join(&image_name);
        if !path.exists() {
            return Err(ServiceError::NotFound("Image not found".to_string()));
        }
        let file = tokio::fs::File::open(path).await?;
        let stream = tokio_util::io::ReaderStream::new(file);
        let body = Body::from_stream(stream);
        let content_type = mime_guess::from_path(&image_name).first_or_octet_stream().to_string();
        let headers = [(header::CONTENT_TYPE, content_type)];
        Ok((headers, body))
    }
}

// --- MAIN & ROUTER SETUP ---

use models::{Post, PostStatus, User};
use services::{PostService, UserService};
use handlers::AppState;
use std::path::PathBuf;

#[tokio::main]
async fn main() {
    tracing_subscriber::registry()
        .with(tracing_subscriber::EnvFilter::new("info"))
        .with(tracing_subscriber::fmt::layer())
        .init();

    // Setup mock DB
    let user_db = Arc::new(Mutex::new(HashMap::<Uuid, User>::new()));
    let post_db = Arc::new(Mutex::new(HashMap::<Uuid, Post>::new()));
    
    // Pre-populate
    let post_id = Uuid::new_v4();
    post_db.lock().unwrap().insert(post_id, Post {
        id: post_id, user_id: Uuid::new_v4(), title: "Test".to_string(), content: "".to_string(), status: PostStatus::DRAFT
    });

    // Setup storage
    let storage_path = std::env::temp_dir().join("app_storage_v2");
    tokio::fs::create_dir_all(&storage_path).await.unwrap();

    // Setup services
    let user_service = UserService::new(user_db.clone());
    let post_service = PostService::new(post_db.clone(), storage_path.clone());

    let app_state = Arc::new(AppState {
        user_service,
        post_service,
        storage_path,
    });

    let app = Router::new()
        .route("/users/upload/csv", post(handlers::upload_users_csv_handler))
        .route("/posts/:post_id/image", post(handlers::upload_post_image_handler))
        .route("/posts/download/csv", get(handlers::download_posts_csv_handler))
        .route("/images/:image_name", get(handlers::serve_image_handler))
        .with_state(app_state)
        .layer(DefaultBodyLimit::max(10 * 1024 * 1024));

    let addr = SocketAddr::from(([127, 0, 0, 1], 3001));
    tracing::info!("Service Layer variation listening on {}", addr);
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}