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
futures-util = { version = "0.3", features = ["stream"] }
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
axum-macros = "0.4"
anyhow = "1.0"
bytes = "1"
tokio-util = { version = "0.7", features = ["io"] }
*/

use anyhow::{anyhow, Context, Result};
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
use futures_util::stream::{self, Stream, TryStreamExt};
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, net::SocketAddr, path::PathBuf, sync::Arc};
use tokio::sync::RwLock;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};
use uuid::Uuid;

// --- DOMAIN ---
#[derive(Debug, Clone, Serialize, Deserialize)]
enum UserRole { ADMIN, USER }
#[derive(Debug, Clone, Serialize, Deserialize)]
struct User { id: Uuid, email: String, password_hash: String, role: UserRole, is_active: bool, created_at: DateTime<Utc> }
#[derive(Debug, Clone, Serialize, Deserialize)]
enum PostStatus { DRAFT, PUBLISHED }
#[derive(Debug, Clone, Serialize, Deserialize)]
struct Post { id: Uuid, user_id: Uuid, title: String, content: String, status: PostStatus }

// --- SHARED STATE ---
type Db = Arc<RwLock<Database>>;
#[derive(Default)]
struct Database {
    users: HashMap<Uuid, User>,
    posts: HashMap<Uuid, Post>,
}
#[derive(Clone)]
struct AppContext {
    db: Db,
    storage: Arc<PathBuf>,
}

// --- ERROR HANDLING ---
// Using anyhow for concise error propagation
impl IntoResponse for anyhow::Error {
    fn into_response(self) -> Response {
        tracing::error!("Error: {:?}", self);
        (StatusCode::INTERNAL_SERVER_ERROR, format!("Error: {}", self)).into_response()
    }
}

// --- MAIN & ROUTER ---
#[tokio::main]
async fn main() {
    tracing_subscriber::registry()
        .with(tracing_subscriber::EnvFilter::new("info"))
        .with(tracing_subscriber::fmt::layer())
        .init();

    // Setup state
    let db = Arc::new(RwLock::new(Database::default()));
    let post_id = Uuid::new_v4();
    db.write().await.posts.insert(post_id, Post {
        id: post_id, user_id: Uuid::new_v4(), title: "Test Post".to_string(), content: "Content".to_string(), status: PostStatus::DRAFT
    });

    let storage_path = std::env::temp_dir().join("app_storage_v4");
    tokio::fs::create_dir_all(&storage_path).await.unwrap();
    let context = AppContext { db, storage: Arc::new(storage_path) };

    // Router with inline handlers
    let app = Router::new()
        .route("/users/upload/csv", post(upload_users))
        .route("/posts/:post_id/image", post(upload_image))
        .route("/posts/download/csv", get(download_posts))
        .route("/images/:image_name", get(serve_image))
        .with_state(context)
        .layer(DefaultBodyLimit::max(10 * 1024 * 1024));

    let addr = SocketAddr::from(([127, 0, 0, 1], 3003));
    tracing::info!("Minimalist variation listening on {}", addr);
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

// --- HANDLERS (MINIMALIST STYLE) ---

async fn upload_users(
    State(ctx): State<AppContext>,
    mut multipart: Multipart,
) -> Result<Json<Vec<Uuid>>, anyhow::Error> {
    let field = multipart.next_field().await?
        .ok_or_else(|| anyhow!("No field in multipart request"))?;
    
    if field.name() != Some("users_file") {
        return Err(anyhow!("Expected field 'users_file'"));
    }

    let data = field.bytes().await.context("Failed to read bytes from field")?;
    
    #[derive(Deserialize)]
    struct UserCsv { email: String, role: String }

    let mut rdr = csv::Reader::from_reader(data.as_ref());
    let mut new_ids = Vec::new();
    let mut db = ctx.db.write().await;

    for result in rdr.deserialize::<UserCsv>() {
        let record = result.context("Failed to deserialize CSV record")?;
        let user = User {
            id: Uuid::new_v4(),
            email: record.email,
            password_hash: "default_hash".to_string(),
            role: if record.role.to_uppercase() == "ADMIN" { UserRole::ADMIN } else { UserRole::USER },
            is_active: true,
            created_at: Utc::now(),
        };
        new_ids.push(user.id);
        db.users.insert(user.id, user);
    }

    Ok(Json(new_ids))
}

async fn upload_image(
    State(ctx): State<AppContext>,
    Path(post_id): Path<Uuid>,
    mut multipart: Multipart,
) -> Result<Json<String>, anyhow::Error> {
    if !ctx.db.read().await.posts.contains_key(&post_id) {
        return Err(anyhow!("Post not found"));
    }

    let field = multipart.next_field().await?
        .ok_or_else(|| anyhow!("No image field in request"))?;
    
    let content_type = field.content_type().unwrap_or("").to_string();
    let ext = match content_type.as_str() {
        "image/jpeg" => "jpg",
        "image/png" => "png",
        _ => return Err(anyhow!("Unsupported image type: {}", content_type)),
    };

    let data = field.bytes().await?;
    let img = image::load_from_memory(&data).context("Failed to decode image")?;
    let resized = img.resize(300, 300, image::imageops::FilterType::Lanczos3);

    let img_name = format!("{}.{}", post_id, ext);
    let final_path = ctx.storage.join(&img_name);
    
    // Use a background task for disk I/O to keep handler responsive
    tokio::task::spawn_blocking(move || {
        let mut temp_file = tempfile::NamedTempFile::new_in(&*ctx.storage)?;
        let format = image::ImageFormat::from_extension(ext).unwrap();
        resized.write_to(&mut temp_file, format)?;
        temp_file.persist(&final_path)?;
        Ok::<(), anyhow::Error>(())
    }).await??;

    Ok(Json(format!("/images/{}", img_name)))
}

async fn download_posts(State(ctx): State<AppContext>) -> impl IntoResponse {
    let db = ctx.db.read().await;
    let posts: Vec<Post> = db.posts.values().cloned().collect();

    let stream = stream::once(async {
        let mut wtr = csv::WriterBuilder::new().from_writer(Vec::new());
        wtr.write_record(&["id", "user_id", "title", "status"]).unwrap();
        Ok::<_, anyhow::Error>(Bytes::from(wtr.into_inner().unwrap()))
    })
    .chain(stream::iter(posts.into_iter().map(|post| {
        let mut wtr = csv::WriterBuilder::new().has_headers(false).from_writer(Vec::new());
        wtr.serialize((post.id, post.user_id, post.title, post.status)).unwrap();
        Ok(Bytes::from(wtr.into_inner().unwrap()))
    })));

    let headers = [
        (header::CONTENT_TYPE, "text/csv; charset=utf-8"),
        (header::CONTENT_DISPOSITION, "attachment; filename=\"posts.csv\""),
    ];
    (headers, Body::from_stream(stream))
}

async fn serve_image(
    State(ctx): State<AppContext>,
    Path(image_name): Path<String>,
) -> Result<impl IntoResponse, anyhow::Error> {
    let path = ctx.storage.join(&image_name);
    let file = tokio::fs::File::open(path).await.context("Image file not found")?;
    
    let content_type = mime_guess::from_path(&image_name)
        .first_or_octet_stream()
        .to_string();

    let stream = tokio_util::io::ReaderStream::new(file);
    let body = Body::from_stream(stream);
    
    Ok(([(header::CONTENT_TYPE, content_type)], body))
}