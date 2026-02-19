/*
"The Modular Enthusiast" - Feature-based Module Structure

This variation organizes the codebase by feature (`users`), promoting high cohesion
and low coupling. Each feature is a self-contained module with its own models,
routes, handlers, and storage abstraction (`UserStore` trait). The `main` function
becomes a simple assembler of these feature modules. This pattern is extremely
scalable and is well-suited for large monolithic applications or for codebases
that might be split into microservices later.

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
async-trait = "0.1"
*/

use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Router, Json,
};
use std::{net::SocketAddr, sync::Arc};
use thiserror::Error;
use tower_http::trace::{DefaultMakeSpan, TraceLayer};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

// --- Shared Error Module (src/error.rs) ---
pub mod error {
    use super::*;

    #[derive(Debug, Error)]
    pub enum AppError {
        #[error("User not found")]
        UserNotFound,
        #[error("Email already exists")]
        EmailConflict,
        #[error("An internal error occurred")]
        Internal,
    }

    pub type AppResult<T> = Result<T, AppError>;

    impl IntoResponse for AppError {
        fn into_response(self) -> Response {
            let (status, msg) = match self {
                AppError::UserNotFound => (StatusCode::NOT_FOUND, self.to_string()),
                AppError::EmailConflict => (StatusCode::CONFLICT, self.to_string()),
                AppError::Internal => (StatusCode::INTERNAL_SERVER_ERROR, self.to_string()),
            };
            (status, Json(serde_json::json!({ "error": msg }))).into_response()
        }
    }
}

// --- User Feature Module (src/users/mod.rs) ---
pub mod users {
    use super::error::{AppError, AppResult};
    use axum::{
        async_trait,
        extract::{Path, Query, State},
        routing::{delete, get, patch, post},
        Json, Router,
    };
    use chrono::{DateTime, Utc};
    use serde::{Deserialize, Serialize};
    use std::{
        collections::HashMap,
        sync::{Arc, RwLock},
    };
    use uuid::Uuid;

    // --- Models (src/users/models.rs) ---
    #[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
    pub enum UserRole {
        ADMIN,
        USER,
    }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        pub password_hash: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }
    
    // The Post model is defined to meet schema requirements, but not used in this module.
    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub enum PostStatus { DRAFT, PUBLISHED }
    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }

    #[derive(Deserialize)]
    pub struct CreateUserPayload {
        email: String,
        password: String,
        role: UserRole,
    }

    #[derive(Deserialize, Default)]
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

    // --- Storage Abstraction (src/users/storage.rs) ---
    #[async_trait]
    pub trait UserStore: Send + Sync {
        async fn create(&self, user: User) -> AppResult<User>;
        async fn get(&self, id: Uuid) -> AppResult<User>;
        async fn list(&self, params: ListUsersParams) -> AppResult<Vec<User>>;
        async fn update(&self, id: Uuid, payload: UpdateUserPayload) -> AppResult<User>;
        async fn delete(&self, id: Uuid) -> AppResult<()>;
        async fn email_exists(&self, email: &str) -> AppResult<bool>;
    }

    type Db = Arc<RwLock<HashMap<Uuid, User>>>;
    pub struct InMemoryUserStore(Db);

    impl InMemoryUserStore {
        pub fn new() -> Self {
            Self(Db::default())
        }
        pub fn with_data(db: Db) -> Self {
            Self(db)
        }
    }

    #[async_trait]
    impl UserStore for InMemoryUserStore {
        async fn create(&self, user: User) -> AppResult<User> {
            let mut db = self.0.write().map_err(|_| AppError::Internal)?;
            db.insert(user.id, user.clone());
            Ok(user)
        }
        async fn get(&self, id: Uuid) -> AppResult<User> {
            let db = self.0.read().map_err(|_| AppError::Internal)?;
            db.get(&id).cloned().ok_or(AppError::UserNotFound)
        }
        async fn list(&self, params: ListUsersParams) -> AppResult<Vec<User>> {
            let db = self.0.read().map_err(|_| AppError::Internal)?;
            Ok(db.values()
                .filter(|u| params.role.as_ref().map_or(true, |r| &u.role == r))
                .filter(|u| params.is_active.map_or(true, |a| u.is_active == a))
                .cloned()
                .skip(params.offset.unwrap_or(0))
                .take(params.limit.unwrap_or(10))
                .collect())
        }
        async fn update(&self, id: Uuid, payload: UpdateUserPayload) -> AppResult<User> {
            let mut db = self.0.write().map_err(|_| AppError::Internal)?;
            let user = db.get_mut(&id).ok_or(AppError::UserNotFound)?;
            if let Some(email) = payload.email { user.email = email; }
            if let Some(role) = payload.role { user.role = role; }
            if let Some(is_active) = payload.is_active { user.is_active = is_active; }
            Ok(user.clone())
        }
        async fn delete(&self, id: Uuid) -> AppResult<()> {
            let mut db = self.0.write().map_err(|_| AppError::Internal)?;
            db.remove(&id).map(|_| ()).ok_or(AppError::UserNotFound)
        }
        async fn email_exists(&self, email: &str) -> AppResult<bool> {
            let db = self.0.read().map_err(|_| AppError::Internal)?;
            Ok(db.values().any(|u| u.email == email))
        }
    }

    // --- Handlers (src/users/handlers.rs) ---
    type UserStoreState = State<Arc<dyn UserStore>>;

    async fn create_user(
        State(store): UserStoreState,
        Json(payload): Json<CreateUserPayload>,
    ) -> AppResult<(axum::http::StatusCode, Json<UserResponse>)> {
        if store.email_exists(&payload.email).await? {
            return Err(AppError::EmailConflict);
        }
        let user = User {
            id: Uuid::new_v4(),
            email: payload.email,
            password_hash: format!("hashed_{}", payload.password), // Hash properly
            role: payload.role,
            is_active: true,
            created_at: Utc::now(),
        };
        let created_user = store.create(user).await?;
        Ok((axum::http::StatusCode::CREATED, Json(created_user.into())))
    }

    async fn get_user(State(store): UserStoreState, Path(id): Path<Uuid>) -> AppResult<Json<UserResponse>> {
        let user = store.get(id).await?;
        Ok(Json(user.into()))
    }

    async fn list_users(State(store): UserStoreState, Query(params): Query<ListUsersParams>) -> AppResult<Json<Vec<UserResponse>>> {
        let users = store.list(params).await?;
        Ok(Json(users.into_iter().map(Into::into).collect()))
    }

    async fn update_user(
        State(store): UserStoreState,
        Path(id): Path<Uuid>,
        Json(payload): Json<UpdateUserPayload>,
    ) -> AppResult<Json<UserResponse>> {
        let user = store.update(id, payload).await?;
        Ok(Json(user.into()))
    }

    async fn delete_user(State(store): UserStoreState, Path(id): Path<Uuid>) -> AppResult<axum::http::StatusCode> {
        store.delete(id).await?;
        Ok(axum::http::StatusCode::NO_CONTENT)
    }

    // --- Router (src/users/routes.rs) ---
    pub fn create_router(store: Arc<dyn UserStore>) -> Router {
        Router::new()
            .route("/", post(create_user).get(list_users))
            .route(
                "/:id",
                get(get_user)
                    .patch(update_user)
                    .delete(delete_user),
            )
            .with_state(store)
    }
}

// --- Application State (src/state.rs) ---
// In this modular approach, state is often constructed and passed
// directly to the feature routers, so a single AppState struct is less common.

// --- Main Application (src/main.rs) ---
#[tokio::main]
async fn main() {
    tracing_subscriber::registry()
        .with(tracing_subscriber::EnvFilter::new("info,tower_http=debug"))
        .with(tracing_subscriber::fmt::layer())
        .init();

    // --- Dependency Injection ---
    // Create the in-memory store and populate it.
    let in_memory_db = Arc::new(std::sync::RwLock::new(std::collections::HashMap::new()));
    populate_db(in_memory_db.clone());
    
    // The `UserStore` trait object allows for easy swapping of implementations.
    let user_store: Arc<dyn users::UserStore> = Arc::new(users::InMemoryUserStore::with_data(in_memory_db));

    // --- Router Assembly ---
    // Each feature module provides its own router.
    let user_routes = users::create_router(user_store);

    let app = Router::new()
        .nest("/users", user_routes)
        // .nest("/posts", posts::create_router(post_store)) // Other features would be added here
        .layer(
            TraceLayer::new_for_http()
                .make_span_with(DefaultMakeSpan::default().include_headers(true)),
        );

    let addr = SocketAddr::from(([127, 0, 0, 1], 3000));
    tracing::debug!("listening on {}", addr);
    axum::Server::bind(&addr)
        .serve(app.into_make_service())
        .await
        .unwrap();
}

fn populate_db(db: Arc<std::sync::RwLock<std::collections::HashMap<uuid::Uuid, users::User>>>) {
    let mut db_lock = db.write().unwrap();
    let admin_id = uuid::Uuid::new_v4();
    let user_id = uuid::Uuid::new_v4();

    db_lock.insert(
        admin_id,
        users::User {
            id: admin_id,
            email: "admin@example.com".to_string(),
            password_hash: "hashed_admin_pass".to_string(),
            role: users::UserRole::ADMIN,
            is_active: true,
            created_at: chrono::Utc::now(),
        },
    );
    db_lock.insert(
        user_id,
        users::User {
            id: user_id,
            email: "user@example.com".to_string(),
            password_hash: "hashed_user_pass".to_string(),
            role: users::UserRole::USER,
            is_active: false,
            created_at: chrono::Utc::now(),
        },
    );
}