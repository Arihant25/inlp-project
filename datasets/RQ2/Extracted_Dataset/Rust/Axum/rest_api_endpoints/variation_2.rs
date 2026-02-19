/*
"The Architect" - Service/Repository Pattern

This variation demonstrates a layered architecture with clear separation of concerns:
- Handlers: Responsible for HTTP request/response processing.
- Services: Contain the core business logic.
- Repositories: Abstract data access.

This structure is highly testable and scalable, making it ideal for complex,
long-lived applications. It introduces more boilerplate but pays off in maintainability.

To run this code, add the following to your Cargo.toml:
----------------------------------------------------------
[dependencies]
axum = { version = "0.6", features = ["macros"] }
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

// --- 1. Models (domain.rs) ---
mod domain {
    use super::*;

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

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub enum PostStatus {
        DRAFT,
        PUBLISHED,
    }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }
}

// --- 2. DTOs & API Payloads (dtos.rs) ---
mod dtos {
    use super::domain::*;
    use super::*;

    #[derive(Deserialize)]
    pub struct CreateUserPayload {
        pub email: String,
        pub password: String,
        pub role: UserRole,
    }

    #[derive(Deserialize, Default)]
    pub struct UpdateUserPayload {
        pub email: Option<String>,
        pub role: Option<UserRole>,
        pub is_active: Option<bool>,
    }

    #[derive(Deserialize, Debug, Default)]
    pub struct ListUsersParams {
        pub offset: Option<usize>,
        pub limit: Option<usize>,
        pub role: Option<UserRole>,
        pub is_active: Option<bool>,
    }

    #[derive(Serialize)]
    pub struct UserResponse {
        pub id: Uuid,
        pub email: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
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
}

// --- 3. Error Handling (errors.rs) ---
mod errors {
    use super::*;

    #[derive(Debug, Error)]
    pub enum AppError {
        #[error(transparent)]
        Repo(#[from] RepoError),
        #[error("Validation error: {0}")]
        ValidationError(String),
    }

    #[derive(Debug, Error)]
    pub enum RepoError {
        #[error("Not found")]
        NotFound,
        #[error("Conflict: {0}")]
        Conflict(String),
        #[error("Internal database error")]
        Internal,
    }

    impl IntoResponse for AppError {
        fn into_response(self) -> Response {
            let (status, error_message) = match self {
                AppError::Repo(RepoError::NotFound) => (StatusCode::NOT_FOUND, "Resource not found".to_string()),
                AppError::Repo(RepoError::Conflict(msg)) => (StatusCode::CONFLICT, msg),
                AppError::ValidationError(msg) => (StatusCode::BAD_REQUEST, msg),
                _ => (StatusCode::INTERNAL_SERVER_ERROR, "An internal error occurred".to_string()),
            };
            (status, Json(serde_json::json!({ "error": error_message }))).into_response()
        }
    }
}

// --- 4. Repository Layer (user_repository.rs) ---
mod user_repository {
    use super::domain::*;
    use super::dtos::*;
    use super::errors::*;
    use super::*;

    #[async_trait]
    pub trait UserRepository: Send + Sync {
        async fn create(&self, user: User) -> Result<User, RepoError>;
        async fn find_by_id(&self, id: Uuid) -> Result<User, RepoError>;
        async fn find_by_email(&self, email: &str) -> Result<Option<User>, RepoError>;
        async fn find_all(&self, params: ListUsersParams) -> Result<Vec<User>, RepoError>;
        async fn update(&self, id: Uuid, payload: UpdateUserPayload) -> Result<User, RepoError>;
        async fn delete(&self, id: Uuid) -> Result<(), RepoError>;
    }

    type Db = Arc<RwLock<HashMap<Uuid, User>>>;

    #[derive(Clone)]
    pub struct InMemoryUserRepository {
        db: Db,
    }

    impl InMemoryUserRepository {
        pub fn new(db: Db) -> Self {
            Self { db }
        }
    }

    #[async_trait]
    impl UserRepository for InMemoryUserRepository {
        async fn create(&self, user: User) -> Result<User, RepoError> {
            let mut db = self.db.write().map_err(|_| RepoError::Internal)?;
            db.insert(user.id, user.clone());
            Ok(user)
        }

        async fn find_by_id(&self, id: Uuid) -> Result<User, RepoError> {
            let db = self.db.read().map_err(|_| RepoError::Internal)?;
            db.get(&id).cloned().ok_or(RepoError::NotFound)
        }
        
        async fn find_by_email(&self, email: &str) -> Result<Option<User>, RepoError> {
            let db = self.db.read().map_err(|_| RepoError::Internal)?;
            Ok(db.values().find(|u| u.email == email).cloned())
        }

        async fn find_all(&self, params: ListUsersParams) -> Result<Vec<User>, RepoError> {
            let db = self.db.read().map_err(|_| RepoError::Internal)?;
            let users = db
                .values()
                .filter(|user| params.role.as_ref().map_or(true, |role| &user.role == role))
                .filter(|user| params.is_active.map_or(true, |is_active| user.is_active == is_active))
                .cloned()
                .skip(params.offset.unwrap_or(0))
                .take(params.limit.unwrap_or(10))
                .collect();
            Ok(users)
        }

        async fn update(&self, id: Uuid, payload: UpdateUserPayload) -> Result<User, RepoError> {
            let mut db = self.db.write().map_err(|_| RepoError::Internal)?;
            let user = db.get_mut(&id).ok_or(RepoError::NotFound)?;
            if let Some(email) = payload.email { user.email = email; }
            if let Some(role) = payload.role { user.role = role; }
            if let Some(is_active) = payload.is_active { user.is_active = is_active; }
            Ok(user.clone())
        }

        async fn delete(&self, id: Uuid) -> Result<(), RepoError> {
            let mut db = self.db.write().map_err(|_| RepoError::Internal)?;
            if db.remove(&id).is_some() {
                Ok(())
            } else {
                Err(RepoError::NotFound)
            }
        }
    }
}

// --- 5. Service Layer (user_service.rs) ---
mod user_service {
    use super::domain::*;
    use super::dtos::*;
    use super::errors::*;
    use super::user_repository::*;
    use super::*;

    #[derive(Clone)]
    pub struct UserService {
        repo: Arc<dyn UserRepository>,
    }

    impl UserService {
        pub fn new(repo: Arc<dyn UserRepository>) -> Self {
            Self { repo }
        }

        pub async fn create_user(&self, payload: CreateUserPayload) -> Result<User, AppError> {
            if self.repo.find_by_email(&payload.email).await?.is_some() {
                return Err(AppError::Repo(RepoError::Conflict("Email already exists".to_string())));
            }
            let user = User {
                id: Uuid::new_v4(),
                email: payload.email,
                password_hash: format!("hashed_{}", payload.password), // Hash properly in real app
                role: payload.role,
                is_active: true,
                created_at: Utc::now(),
            };
            self.repo.create(user).await.map_err(AppError::from)
        }

        pub async fn get_user(&self, id: Uuid) -> Result<User, AppError> {
            self.repo.find_by_id(id).await.map_err(AppError::from)
        }

        pub async fn list_users(&self, params: ListUsersParams) -> Result<Vec<User>, AppError> {
            self.repo.find_all(params).await.map_err(AppError::from)
        }

        pub async fn update_user(&self, id: Uuid, payload: UpdateUserPayload) -> Result<User, AppError> {
            self.repo.update(id, payload).await.map_err(AppError::from)
        }

        pub async fn delete_user(&self, id: Uuid) -> Result<(), AppError> {
            self.repo.delete(id).await.map_err(AppError::from)
        }
    }
}

// --- 6. Handler Layer (user_handlers.rs) ---
mod user_handlers {
    use super::dtos::*;
    use super::errors::*;
    use super::user_service::*;
    use super::*;

    pub async fn create_user(
        State(service): State<UserService>,
        Json(payload): Json<CreateUserPayload>,
    ) -> Result<(StatusCode, Json<UserResponse>), AppError> {
        let user = service.create_user(payload).await?;
        Ok((StatusCode::CREATED, Json(user.into())))
    }

    pub async fn get_user_by_id(
        State(service): State<UserService>,
        Path(id): Path<Uuid>,
    ) -> Result<Json<UserResponse>, AppError> {
        let user = service.get_user(id).await?;
        Ok(Json(user.into()))
    }

    pub async fn list_users(
        State(service): State<UserService>,
        Query(params): Query<ListUsersParams>,
    ) -> Result<Json<Vec<UserResponse>>, AppError> {
        let users = service.list_users(params).await?;
        let user_responses = users.into_iter().map(Into::into).collect();
        Ok(Json(user_responses))
    }

    pub async fn update_user(
        State(service): State<UserService>,
        Path(id): Path<Uuid>,
        Json(payload): Json<UpdateUserPayload>,
    ) -> Result<Json<UserResponse>, AppError> {
        let user = service.update_user(id, payload).await?;
        Ok(Json(user.into()))
    }

    pub async fn delete_user(
        State(service): State<UserService>,
        Path(id): Path<Uuid>,
    ) -> Result<StatusCode, AppError> {
        service.delete_user(id).await?;
        Ok(StatusCode::NO_CONTENT)
    }
}

// --- 7. Main Application Setup (main.rs) ---
use domain::*;
use user_repository::*;
use user_service::*;
use user_handlers::*;

#[tokio::main]
async fn main() {
    tracing_subscriber::registry()
        .with(tracing_subscriber::EnvFilter::new("info,tower_http=debug"))
        .with(tracing_subscriber::fmt::layer())
        .init();

    // --- Dependency Injection ---
    let db = Arc::new(RwLock::new(HashMap::new()));
    populate_db(db.clone());
    let user_repo = Arc::new(InMemoryUserRepository::new(db.clone()));
    let user_service = UserService::new(user_repo);

    // --- Router Setup ---
    let app = Router::new()
        .route("/users", post(create_user).get(list_users))
        .route(
            "/users/:id",
            get(get_user_by_id)
                .patch(update_user)
                .delete(delete_user),
        )
        .with_state(user_service)
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

fn populate_db(db: Arc<RwLock<HashMap<Uuid, User>>>) {
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