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
async-trait = "0.1"
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
use std::{net::SocketAddr, path::PathBuf, sync::Arc};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};
use uuid::Uuid;

// --- COMMON ERROR TYPE ---
pub type AppResult<T> = Result<T, AppError>;

#[derive(Debug)]
pub struct AppError(String);

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        (StatusCode::INTERNAL_SERVER_ERROR, self.0).into_response()
    }
}

impl<T: std::error::Error> From<T> for AppError {
    fn from(err: T) -> Self {
        AppError(err.to_string())
    }
}

// --- DOMAIN MODELS ---
mod domain {
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

// --- REPOSITORY TRAITS & IMPLEMENTATIONS (DATA LAYER) ---
mod persistence {
    use super::domain::{Post, User};
    use super::AppResult;
    use async_trait::async_trait;
    use std::{
        collections::HashMap,
        sync::{Arc, Mutex},
    };
    use uuid::Uuid;

    #[async_trait]
    pub trait UserRepository: Send + Sync {
        async fn create_many(&self, users: Vec<User>) -> AppResult<Vec<Uuid>>;
    }

    #[async_trait]
    pub trait PostRepository: Send + Sync {
        async fn find_by_id(&self, id: Uuid) -> AppResult<Option<Post>>;
        async fn find_all(&self) -> AppResult<Vec<Post>>;
    }

    // In-memory implementation for demonstration
    #[derive(Clone, Default)]
    pub struct InMemoryDb {
        users: Arc<Mutex<HashMap<Uuid, User>>>,
        posts: Arc<Mutex<HashMap<Uuid, Post>>>,
    }
    
    impl InMemoryDb {
        pub fn with_post(mut self, post: Post) -> Self {
            self.posts.lock().unwrap().insert(post.id, post);
            self
        }
    }

    #[async_trait]
    impl UserRepository for InMemoryDb {
        async fn create_many(&self, users: Vec<User>) -> AppResult<Vec<Uuid>> {
            let mut lock = self.users.lock().unwrap();
            let ids = users
                .into_iter()
                .map(|user| {
                    let id = user.id;
                    lock.insert(id, user);
                    id
                })
                .collect();
            Ok(ids)
        }
    }

    #[async_trait]
    impl PostRepository for InMemoryDb {
        async fn find_by_id(&self, id: Uuid) -> AppResult<Option<Post>> {
            let lock = self.posts.lock().unwrap();
            Ok(lock.get(&id).cloned())
        }
        async fn find_all(&self) -> AppResult<Vec<Post>> {
            let lock = self.posts.lock().unwrap();
            Ok(lock.values().cloned().collect())
        }
    }
}

// --- FILE PROCESSING SERVICE (BUSINESS LOGIC) ---
mod services {
    use super::domain::{User, UserRole};
    use super::persistence::{PostRepository, UserRepository};
    use super::AppResult;
    use bytes::Bytes;
    use chrono::Utc;
    use serde::Deserialize;
    use std::{io::Write, path::Path, sync::Arc};
    use tempfile::NamedTempFile;
    use uuid::Uuid;

    pub struct FileService {
        user_repo: Arc<dyn UserRepository>,
        post_repo: Arc<dyn PostRepository>,
        storage_path: PathBuf,
    }

    impl FileService {
        pub fn new(
            user_repo: Arc<dyn UserRepository>,
            post_repo: Arc<dyn PostRepository>,
            storage_path: PathBuf,
        ) -> Self {
            Self { user_repo, post_repo, storage_path }
        }

        pub async fn bulk_import_users(&self, csv_data: Bytes) -> AppResult<Vec<Uuid>> {
            #[derive(Deserialize)]
            struct UserCsvRecord { email: String, role: String }

            let mut rdr = csv::Reader::from_reader(csv_data.as_ref());
            let users_to_create = rdr.deserialize::<UserCsvRecord>()
                .map(|result| {
                    let record = result?;
                    Ok(User {
                        id: Uuid::new_v4(),
                        email: record.email,
                        password_hash: "default_hash".to_string(),
                        role: if record.role.to_uppercase() == "ADMIN" { UserRole::ADMIN } else { UserRole::USER },
                        is_active: true,
                        created_at: Utc::now(),
                    })
                })
                .collect::<Result<Vec<User>, csv::Error>>()?;
            
            self.user_repo.create_many(users_to_create).await
        }

        pub async fn process_and_store_image(
            &self,
            post_id: Uuid,
            image_data: Bytes,
            content_type: &str,
        ) -> AppResult<String> {
            if self.post_repo.find_by_id(post_id).await?.is_none() {
                return Err(super::AppError("Post not found".to_string()));
            }

            let extension = match content_type {
                "image/jpeg" => "jpg",
                "image/png" => "png",
                _ => return Err(super::AppError("Unsupported image type".to_string())),
            };

            let image = image::load_from_memory(&image_data)?;
            let resized = image.resize(300, 300, image::imageops::FilterType::Lanczos3);
            
            let mut temp_file = NamedTempFile::new_in(&self.storage_path)?;
            let format = image::ImageFormat::from_extension(extension).unwrap();
            resized.write_to(&mut temp_file, format)?;

            let image_name = format!("{}.{}", post_id, extension);
            let final_path = self.storage_path.join(&image_name);
            temp_file.persist(&final_path)?;

            Ok(format!("/images/{}", image_name))
        }

        pub async fn export_posts_as_csv(&self) -> AppResult<Bytes> {
            let posts = self.post_repo.find_all().await?;
            let mut wtr = csv::WriterBuilder::new().from_writer(vec![]);
            wtr.write_record(&["id", "user_id", "title", "status"])?;
            for post in posts {
                wtr.serialize((post.id, post.user_id, &post.title, &post.status))?;
            }
            Ok(Bytes::from(wtr.into_inner()?))
        }
    }
}

// --- AXUM HANDLERS (PRESENTATION LAYER) ---
mod handlers {
    use super::domain::Post;
    use super::services::FileService;
    use super::AppResult;
    use axum::{
        body::Body,
        extract::{Multipart, Path, State},
        http::header,
        response::IntoResponse,
        Json,
    };
    use std::sync::Arc;
    use uuid::Uuid;

    pub async fn handle_user_csv_upload(
        State(file_service): State<Arc<FileService>>,
        mut multipart: Multipart,
    ) -> AppResult<Json<Vec<Uuid>>> {
        while let Some(field) = multipart.next_field().await? {
            if field.name() == Some("users_file") {
                let data = field.bytes().await?;
                let ids = file_service.bulk_import_users(data).await?;
                return Ok(Json(ids));
            }
        }
        Err(super::AppError("Field 'users_file' not found".into()))
    }

    pub async fn handle_post_image_upload(
        State(file_service): State<Arc<FileService>>,
        Path(post_id): Path<Uuid>,
        mut multipart: Multipart,
    ) -> AppResult<Json<String>> {
        while let Some(field) = multipart.next_field().await? {
            if field.name() == Some("image") {
                let content_type = field.content_type().unwrap_or("").to_string();
                let data = field.bytes().await?;
                let url = file_service.process_and_store_image(post_id, data, &content_type).await?;
                return Ok(Json(url));
            }
        }
        Err(super::AppError("Field 'image' not found".into()))
    }

    pub async fn handle_posts_csv_download(
        State(file_service): State<Arc<FileService>>,
    ) -> AppResult<impl IntoResponse> {
        let csv_data = file_service.export_posts_as_csv().await?;
        let headers = [
            (header::CONTENT_TYPE, "text/csv; charset=utf-8"),
            (header::CONTENT_DISPOSITION, "attachment; filename=\"posts.csv\""),
        ];
        Ok((headers, csv_data))
    }

    pub async fn handle_serve_image(
        State(file_service): State<Arc<FileService>>,
        Path(image_name): Path<String>,
    ) -> AppResult<impl IntoResponse> {
        let path = file_service.storage_path.join(&image_name);
        let file = tokio::fs::File::open(path).await?;
        let stream = tokio_util::io::ReaderStream::new(file);
        let body = Body::from_stream(stream);
        let content_type = mime_guess::from_path(&image_name).first_or_octet_stream().to_string();
        Ok(([(header::CONTENT_TYPE, content_type)], body))
    }
}

// --- MAIN & ROUTER SETUP ---
use domain::{Post, PostStatus};
use persistence::{InMemoryDb, PostRepository, UserRepository};
use services::FileService;

#[tokio::main]
async fn main() {
    tracing_subscriber::registry()
        .with(tracing_subscriber::EnvFilter::new("info"))
        .with(tracing_subscriber::fmt::layer())
        .init();

    let storage_path = std::env::temp_dir().join("app_storage_v3");
    tokio::fs::create_dir_all(&storage_path).await.unwrap();

    // Dependency Injection using trait objects
    let post_id = Uuid::new_v4();
    let db = Arc::new(InMemoryDb::default().with_post(Post {
        id: post_id, user_id: Uuid::new_v4(), title: "Test".to_string(), content: "".to_string(), status: PostStatus::DRAFT
    }));
    let user_repo: Arc<dyn UserRepository> = db.clone();
    let post_repo: Arc<dyn PostRepository> = db;

    let file_service = Arc::new(FileService::new(user_repo, post_repo, storage_path));

    let app = Router::new()
        .route("/users/upload/csv", post(handlers::handle_user_csv_upload))
        .route("/posts/:post_id/image", post(handlers::handle_post_image_upload))
        .route("/posts/download/csv", get(handlers::handle_posts_csv_download))
        .route("/images/:image_name", get(handlers::handle_serve_image))
        .with_state(file_service)
        .layer(DefaultBodyLimit::max(10 * 1024 * 1024));

    let addr = SocketAddr::from(([127, 0, 0, 1], 3002));
    tracing::info!("Trait-based variation listening on {}", addr);
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}