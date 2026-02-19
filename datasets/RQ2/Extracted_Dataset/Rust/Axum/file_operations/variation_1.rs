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
bytes = "1"
*/

use axum::{
    body::Body,
    extract::{DefaultBodyLimit, Multipart, Path, State},
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use bytes::Bytes;
use chrono::{DateTime, Utc};
use futures_util::stream::{self, Stream};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    io::Cursor,
    net::SocketAddr,
    path::PathBuf,
    sync::{Arc, Mutex},
};
use tempfile::NamedTempFile;
use tokio::{fs::File, io::AsyncWriteExt};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};
use uuid::Uuid;

// --- DOMAIN MODELS ---

#[derive(Debug, Clone, Serialize, Deserialize)]
enum UserRole {
    ADMIN,
    USER,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct User {
    id: Uuid,
    email: String,
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
enum PostStatus {
    DRAFT,
    PUBLISHED,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- APP STATE & ERROR HANDLING ---

type Db = Arc<Mutex<AppStateData>>;

#[derive(Default)]
struct AppStateData {
    users: HashMap<Uuid, User>,
    posts: HashMap<Uuid, Post>,
}

struct AppState {
    db: Db,
    storage_path: PathBuf,
}

struct AppError(anyhow::Error);

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        tracing::error!("Application error: {:?}", self.0);
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Something went wrong: {}", self.0),
        )
            .into_response()
    }
}

impl<E> From<E> for AppError
where
    E: Into<anyhow::Error>,
{
    fn from(err: E) -> Self {
        Self(err.into())
    }
}

// --- MAIN & ROUTER SETUP ---

#[tokio::main]
async fn main() {
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "file_operations_demo=debug".into()),
        )
        .with(tracing_subscriber::fmt::layer())
        .init();

    let db = Db::default();
    // Pre-populate with a post for image upload testing
    let user_id = Uuid::new_v4();
    let post_id = Uuid::new_v4();
    db.lock().unwrap().posts.insert(
        post_id,
        Post {
            id: post_id,
            user_id,
            title: "Sample Post".to_string(),
            content: "Content".to_string(),
            status: PostStatus::DRAFT,
        },
    );

    let storage_path = std::env::temp_dir().join("app_storage");
    tokio::fs::create_dir_all(&storage_path).await.unwrap();
    tracing::info!("Image storage path: {}", storage_path.display());

    let app_state = Arc::new(AppState { db, storage_path });

    let app = Router::new()
        .route("/users/upload/csv", post(upload_users_csv))
        .route("/posts/:post_id/image", post(upload_post_image))
        .route("/posts/download/csv", get(download_posts_csv))
        .route("/images/:image_name", get(serve_image))
        .with_state(app_state)
        .layer(DefaultBodyLimit::max(10 * 1024 * 1024)); // 10 MB limit

    let addr = SocketAddr::from(([127, 0, 0, 1], 3000));
    tracing::debug!("listening on {}", addr);
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

// --- HANDLERS (FUNCTIONAL STYLE) ---

#[derive(Deserialize)]
struct UserCsvRecord {
    email: String,
    role: String,
}

async fn upload_users_csv(
    State(state): State<Arc<AppState>>,
    mut multipart: Multipart,
) -> Result<Json<Vec<Uuid>>, AppError> {
    let mut new_user_ids = Vec::new();
    while let Some(field) = multipart.next_field().await? {
        if field.name() == Some("users_file") {
            let data = field.bytes().await?;
            let mut rdr = csv::Reader::from_reader(data.as_ref());

            let mut db_lock = state.db.lock().unwrap();
            for result in rdr.deserialize::<UserCsvRecord>() {
                let record = result?;
                let user = User {
                    id: Uuid::new_v4(),
                    email: record.email,
                    password_hash: "default_hash".to_string(), // In real app, hash a random password
                    role: if record.role.to_uppercase() == "ADMIN" {
                        UserRole::ADMIN
                    } else {
                        UserRole::USER
                    },
                    is_active: true,
                    created_at: Utc::now(),
                };
                new_user_ids.push(user.id);
                db_lock.users.insert(user.id, user);
            }
            // Only process the first valid file field
            break;
        }
    }
    Ok(Json(new_user_ids))
}

async fn upload_post_image(
    State(state): State<Arc<AppState>>,
    Path(post_id): Path<Uuid>,
    mut multipart: Multipart,
) -> Result<Json<String>, AppError> {
    // Check if post exists
    if !state.db.lock().unwrap().posts.contains_key(&post_id) {
        return Err(AppError(anyhow::anyhow!("Post not found")));
    }

    while let Some(field) = multipart.next_field().await? {
        if field.name() == Some("image") {
            let content_type = field.content_type().unwrap_or("").to_string();
            let extension = match content_type.as_str() {
                "image/jpeg" => "jpg",
                "image/png" => "png",
                _ => return Err(AppError(anyhow::anyhow!("Unsupported image type"))),
            };

            let data = field.bytes().await?;
            let image = image::load_from_memory(&data)?;

            // Resize the image
            let resized_image = image.resize(300, 300, image::imageops::FilterType::Lanczos3);

            // Save to a temporary file first, then move to final destination
            let mut temp_file = NamedTempFile::new_in(&state.storage_path)?;
            resized_image.write_to(&mut temp_file, image::ImageFormat::from_extension(extension).unwrap())?;
            
            let image_name = format!("{}.{}", post_id, extension);
            let final_path = state.storage_path.join(&image_name);
            
            temp_file.persist(&final_path)?;
            
            let image_url = format!("/images/{}", image_name);
            return Ok(Json(image_url));
        }
    }
    Err(AppError(anyhow::anyhow!("Image field not found")))
}

async fn download_posts_csv(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let posts = state.db.lock().unwrap().posts.values().cloned().collect::<Vec<_>>();

    let stream = stream::once(async move {
        let mut wtr = csv::WriterBuilder::new().from_writer(vec![]);
        wtr.write_record(&["id", "user_id", "title", "status"]).unwrap();
        for post in posts {
            wtr.serialize((
                post.id,
                post.user_id,
                &post.title,
                serde_json::to_string(&post.status).unwrap(),
            )).unwrap();
        }
        let data = wtr.into_inner().unwrap();
        Ok::<_, std::io::Error>(Bytes::from(data))
    });

    let headers = [
        (header::CONTENT_TYPE, "text/csv; charset=utf-8"),
        (
            header::CONTENT_DISPOSITION,
            "attachment; filename=\"posts.csv\"",
        ),
    ];

    (headers, Body::from_stream(stream))
}

async fn serve_image(
    State(state): State<Arc<AppState>>,
    Path(image_name): Path<String>,
) -> Result<impl IntoResponse, AppError> {
    let path = state.storage_path.join(&image_name);
    if !path.exists() {
        return Err(AppError(anyhow::anyhow!("Image not found")));
    }

    let file = File::open(path).await?;
    let content_type = match image_name.split('.').last() {
        Some("jpg") | Some("jpeg") => "image/jpeg",
        Some("png") => "image/png",
        _ => "application/octet-stream",
    };

    let stream = tokio_util::io::ReaderStream::new(file);
    let body = Body::from_stream(stream);

    let headers = [
        (header::CONTENT_TYPE, content_type),
    ];

    Ok((headers, body))
}