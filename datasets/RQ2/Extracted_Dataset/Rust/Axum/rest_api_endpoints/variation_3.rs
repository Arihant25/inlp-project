/*
"The Minimalist" - Inline Handlers and Type-Safe State

This variation prioritizes conciseness and developer velocity. It uses `anyhow` for
terse error handling, `validator` for declarative validation, and defines some
handlers as inline closures directly in the router. The state is a simple type
alias, avoiding structural boilerplate. This style is excellent for prototypes,
small services, or developers who prefer a more direct, less-abstracted approach.

To run this code, add the following to your Cargo.toml:
----------------------------------------------------------
[dependencies]
axum = "0.6"
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
anyhow = "1"
validator = { version = "0.16", features = ["derive"] }
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
tower-http = { version = "0.4", features = ["trace"] }
*/

use anyhow::{anyhow, Context, Result};
use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{delete, get, patch, post},
    Json, Router,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::{Arc, RwLock},
};
use tower_http::trace::{DefaultMakeSpan, TraceLayer};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};
use uuid::Uuid;
use validator::Validate;

// --- DOMAIN MODELS ---

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
pub enum UserRole {
    ADMIN,
    USER,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct User {
    id: Uuid,
    email: String,
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum PostStatus {
    DRAFT,
    PUBLISHED,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- DTOs with Validation ---

#[derive(Deserialize, Validate)]
pub struct CreateUserPayload {
    #[validate(email)]
    email: String,
    #[validate(length(min = 8))]
    password: String,
    role: UserRole,
}

#[derive(Deserialize, Validate)]
pub struct UpdateUserPayload {
    #[validate(email)]
    email: Option<String>,
    role: Option<UserRole>,
    is_active: Option<bool>,
}

#[derive(Deserialize, Debug, Default)]
pub struct ListUsersParams {
    offset: Option<usize>,
    limit: Option<usize>,
    role: Option<UserRole>,
    is_active: Option<bool>,
}

#[derive(Serialize)]
pub struct UserResponse {
    id: Uuid,
    email: String,
    role: UserRole,
    is_active: bool,
    created_at: DateTime<Utc>,
}

// --- STATE & ERROR HANDLING ---

// A simple type alias for our in-memory database state.
type AppState = Arc<RwLock<HashMap<Uuid, User>>>;

// Generic error handler using anyhow, converting errors into HTTP responses.
impl IntoResponse for anyhow::Error {
    fn into_response(self) -> Response {
        // Log the full error for debugging
        tracing::error!("Error: {:?}", self);

        // Provide a generic error response to the client
        let body = Json(serde_json::json!({ "error": "Internal server error" }));
        (StatusCode::INTERNAL_SERVER_ERROR, body).into_response()
    }
}

// --- API HANDLERS ---

async fn create_user(
    State(db): State<AppState>,
    Json(payload): Json<CreateUserPayload>,
) -> Result<(StatusCode, Json<UserResponse>), anyhow::Error> {
    payload.validate()?;

    let mut db_write = db.write().expect("Failed to acquire write lock");

    if db_write.values().any(|u| u.email == payload.email) {
        return Err(anyhow!("Email already exists")).map_err(|e| {
            (StatusCode::CONFLICT, Json(serde_json::json!({ "error": e.to_string() }))).into_response().into()
        });
    }

    let user = User {
        id: Uuid::new_v4(),
        email: payload.email,
        password_hash: format!("hashed_{}", payload.password), // Hash properly
        role: payload.role,
        is_active: true,
        created_at: Utc::now(),
    };

    db_write.insert(user.id, user.clone());

    let user_response = UserResponse {
        id: user.id,
        email: user.email,
        role: user.role,
        is_active: user.is_active,
        created_at: user.created_at,
    };

    Ok((StatusCode::CREATED, Json(user_response)))
}

async fn update_user(
    State(db): State<AppState>,
    Path(id): Path<Uuid>,
    Json(payload): Json<UpdateUserPayload>,
) -> Result<Json<UserResponse>, anyhow::Error> {
    payload.validate()?;
    let mut db_write = db.write().expect("Failed to acquire write lock");
    let user = db_write.get_mut(&id).context("User not found")?;

    if let Some(email) = payload.email { user.email = email; }
    if let Some(role) = payload.role { user.role = role; }
    if let Some(is_active) = payload.is_active { user.is_active = is_active; }

    let user_response = UserResponse {
        id: user.id,
        email: user.email.clone(),
        role: user.role.clone(),
        is_active: user.is_active,
        created_at: user.created_at,
    };

    Ok(Json(user_response))
}

// --- MAIN & ROUTING ---

#[tokio::main]
async fn main() {
    tracing_subscriber::registry()
        .with(tracing_subscriber::EnvFilter::new("info,tower_http=debug"))
        .with(tracing_subscriber::fmt::layer())
        .init();

    let db: AppState = Arc::new(RwLock::new(HashMap::new()));
    populate_db(db.clone());

    let app = Router::new()
        .route("/users", post(create_user).get(
            // Inline handler for listing users
            |State(db): State<AppState>, Query(params): Query<ListUsersParams>| async move {
                let db_read = db.read().expect("Failed to acquire read lock");
                let users: Vec<UserResponse> = db_read
                    .values()
                    .filter(|user| params.role.as_ref().map_or(true, |role| &user.role == role))
                    .filter(|user| params.is_active.map_or(true, |is_active| user.is_active == is_active))
                    .cloned()
                    .map(|u| UserResponse {
                        id: u.id,
                        email: u.email,
                        role: u.role,
                        is_active: u.is_active,
                        created_at: u.created_at,
                    })
                    .skip(params.offset.unwrap_or(0))
                    .take(params.limit.unwrap_or(10))
                    .collect();
                Ok::<_, anyhow::Error>(Json(users))
            }
        ))
        .route("/users/:id", 
            get(|State(db): State<AppState>, Path(id): Path<Uuid>| async move {
                let db_read = db.read().expect("Failed to acquire read lock");
                db_read.get(&id).cloned()
                    .map(|u| Json(UserResponse {
                        id: u.id,
                        email: u.email,
                        role: u.role,
                        is_active: u.is_active,
                        created_at: u.created_at,
                    }))
                    .ok_or_else(|| (StatusCode::NOT_FOUND, "User not found").into_response())
            })
            .patch(update_user)
            .delete(|State(db): State<AppState>, Path(id): Path<Uuid>| async move {
                let mut db_write = db.write().expect("Failed to acquire write lock");
                if db_write.remove(&id).is_some() {
                    Ok(StatusCode::NO_CONTENT)
                } else {
                    Err((StatusCode::NOT_FOUND, "User not found").into_response())
                }
            })
        )
        .with_state(db)
        .layer(TraceLayer::new_for_http().make_span_with(DefaultMakeSpan::default()));

    let addr = SocketAddr::from(([127, 0, 0, 1], 3000));
    tracing::debug!("listening on {}", addr);
    axum::Server::bind(&addr)
        .serve(app.into_make_service())
        .await
        .unwrap();
}

fn populate_db(db: AppState) {
    let mut db_lock = db.write().unwrap();
    let admin_id = Uuid::new_v4();
    let user_id = Uuid::new_v4();

    db_lock.insert(
        admin_id,
        User {
            id: admin_id,
            email: "admin@example.com".to_string(),
            password_hash: "hashed_admin_pass".to_string(),
            role: UserRole::ADMIN,
            is_active: true,
            created_at: Utc::now(),
        },
    );
    db_lock.insert(
        user_id,
        User {
            id: user_id,
            email: "user@example.com".to_string(),
            password_hash: "hashed_user_pass".to_string(),
            role: UserRole::USER,
            is_active: false,
            created_at: Utc::now(),
        },
    );
}