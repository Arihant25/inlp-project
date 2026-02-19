/*
"The Pragmatist" - Functional Handlers in a Single Module

This variation uses a straightforward, functional approach. All logic is contained
within handler functions, state is managed via a shared Arc<RwLock<...>>, and
error handling is centralized with a custom `AppError` type. It's simple,
effective, and easy to understand for small to medium-sized projects.

To run this code, add the following to your Cargo.toml:
----------------------------------------------------------
[dependencies]
axum = "0.6"
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
thiserror = "1"
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
tower-http = { version = "0.4", features = ["trace"] }
*/

use axum::{
    async_trait,
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
use thiserror::Error;
use tower_http::trace::{DefaultMakeSpan, TraceLayer};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};
use uuid::Uuid;

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
    #[serde(skip_serializing)]
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

// --- DTOs for API Layer ---

#[derive(Deserialize)]
pub struct CreateUserPayload {
    email: String,
    password: String,
    role: UserRole,
}

#[derive(Deserialize)]
pub struct UpdateUserPayload {
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

// The response model for a user, excluding sensitive fields like password_hash.
#[derive(Serialize)]
pub struct UserResponse {
    id: Uuid,
    email: String,
    role: UserRole,
    is_active: bool,
    created_at: DateTime<Utc>,
}

impl From<User> for UserResponse {
    fn from(user: User) -> Self {
        Self {
            id: user.id,
            email: user.email,
            role: user.role,
            is_active: user.is_active,
            created_at: user.created_at,
        }
    }
}

// --- ERROR HANDLING ---

#[derive(Debug, Error)]
pub enum AppError {
    #[error("User not found")]
    UserNotFound,
    #[error("Email already exists")]
    EmailAlreadyExists,
    #[error("Internal server error")]
    InternalServerError,
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, error_message) = match self {
            AppError::UserNotFound => (StatusCode::NOT_FOUND, self.to_string()),
            AppError::EmailAlreadyExists => (StatusCode::CONFLICT, self.to_string()),
            AppError::InternalServerError => (StatusCode::INTERNAL_SERVER_ERROR, self.to_string()),
        };
        (status, Json(serde_json::json!({ "error": error_message }))).into_response()
    }
}

// --- APPLICATION STATE ---

type Db = Arc<RwLock<HashMap<Uuid, User>>>;

#[derive(Clone)]
pub struct AppState {
    db: Db,
}

// --- API HANDLERS ---

async fn create_user(
    State(state): State<AppState>,
    Json(payload): Json<CreateUserPayload>,
) -> Result<(StatusCode, Json<UserResponse>), AppError> {
    let db = state.db.read().map_err(|_| AppError::InternalServerError)?;
    if db.values().any(|u| u.email == payload.email) {
        return Err(AppError::EmailAlreadyExists);
    }
    drop(db);

    let user = User {
        id: Uuid::new_v4(),
        email: payload.email,
        // In a real app, hash the password securely
        password_hash: format!("hashed_{}", payload.password),
        role: payload.role,
        is_active: true,
        created_at: Utc::now(),
    };

    let mut db = state.db.write().map_err(|_| AppError::InternalServerError)?;
    db.insert(user.id, user.clone());

    Ok((StatusCode::CREATED, Json(user.into())))
}

async fn get_user_by_id(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<Json<UserResponse>, AppError> {
    let db = state.db.read().map_err(|_| AppError::InternalServerError)?;
    let user = db.get(&id).cloned().ok_or(AppError::UserNotFound)?;
    Ok(Json(user.into()))
}

async fn list_users(
    State(state): State<AppState>,
    Query(params): Query<ListUsersParams>,
) -> Result<Json<Vec<UserResponse>>, AppError> {
    let db = state.db.read().map_err(|_| AppError::InternalServerError)?;
    
    let users: Vec<UserResponse> = db
        .values()
        .filter(|user| params.role.as_ref().map_or(true, |role| &user.role == role))
        .filter(|user| params.is_active.map_or(true, |is_active| user.is_active == is_active))
        .cloned()
        .map(Into::into)
        .skip(params.offset.unwrap_or(0))
        .take(params.limit.unwrap_or(10))
        .collect();

    Ok(Json(users))
}

async fn update_user(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
    Json(payload): Json<UpdateUserPayload>,
) -> Result<Json<UserResponse>, AppError> {
    let mut db = state.db.write().map_err(|_| AppError::InternalServerError)?;
    let user = db.get_mut(&id).ok_or(AppError::UserNotFound)?;

    if let Some(email) = payload.email {
        user.email = email;
    }
    if let Some(role) = payload.role {
        user.role = role;
    }
    if let Some(is_active) = payload.is_active {
        user.is_active = is_active;
    }

    Ok(Json(user.clone().into()))
}

async fn delete_user(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<StatusCode, AppError> {
    let mut db = state.db.write().map_err(|_| AppError::InternalServerError)?;
    if db.remove(&id).is_some() {
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(AppError::UserNotFound)
    }
}

// --- MAIN & ROUTING ---

#[tokio::main]
async fn main() {
    // Setup logging
    tracing_subscriber::registry()
        .with(tracing_subscriber::EnvFilter::new(
            std::env::var("RUST_LOG").unwrap_or_else(|_| "example_axum_rest=debug,tower_http=debug".into()),
        ))
        .with(tracing_subscriber::fmt::layer())
        .init();

    // Setup in-memory database
    let db = Db::default();
    populate_db(db.clone());

    let app_state = AppState { db };

    // Build our application with a route
    let app = Router::new()
        .route("/users", post(create_user).get(list_users))
        .route(
            "/users/:id",
            get(get_user_by_id)
                .patch(update_user)
                .delete(delete_user),
        )
        .with_state(app_state)
        .layer(
            TraceLayer::new_for_http()
                .make_span_with(DefaultMakeSpan::default().include_headers(true)),
        );

    // Run it
    let addr = SocketAddr::from(([127, 0, 0, 1], 3000));
    tracing::debug!("listening on {}", addr);
    axum::Server::bind(&addr)
        .serve(app.into_make_service())
        .await
        .unwrap();
}

fn populate_db(db: Db) {
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