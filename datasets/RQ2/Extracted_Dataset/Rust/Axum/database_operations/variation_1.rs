// Variation 1: Service Layer Pattern
// This pattern emphasizes separation of concerns by dividing the application into
// layers: Handlers (HTTP), Services (Business Logic), and Repositories (Data Access).
// It's robust, testable, and scales well for larger applications.

// --- Cargo.toml dependencies ---
/*
[dependencies]
axum = "0.7"
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
sqlx = { version = "0.7", features = ["runtime-tokio", "sqlite", "uuid", "chrono", "macros"] }
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
thiserror = "1"
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
*/

// --- Main Application File (main.rs) ---

use axum::{
    async_trait,
    extract::{FromRequest, FromRequestParts, Path, Query, State},
    http::{request::Parts, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use sqlx::{sqlite::SqlitePoolOptions, SqlitePool};
use std::net::SocketAddr;
use uuid::Uuid;

// --- Error Handling ---
mod errors {
    use axum::{http::StatusCode, response::IntoResponse, Json};
    use serde_json::json;
    use thiserror::Error;

    #[derive(Error, Debug)]
    pub enum AppError {
        #[error("Database error: {0}")]
        Sqlx(#[from] sqlx::Error),
        #[error("Item not found: {0}")]
        NotFound(String),
        #[error("Validation error: {0}")]
        Validation(String),
        #[error("Internal server error")]
        Internal,
    }

    impl IntoResponse for AppError {
        fn into_response(self) -> axum::response::Response {
            let (status, error_message) = match self {
                AppError::Sqlx(sqlx::Error::RowNotFound) => {
                    (StatusCode::NOT_FOUND, "Resource not found".to_string())
                }
                AppError::Sqlx(e) => {
                    tracing::error!("SQLx error: {:?}", e);
                    (
                        StatusCode::INTERNAL_SERVER_ERROR,
                        "Database error".to_string(),
                    )
                }
                AppError::NotFound(msg) => (StatusCode::NOT_FOUND, msg),
                AppError::Validation(msg) => (StatusCode::BAD_REQUEST, msg),
                AppError::Internal => (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "An internal error occurred".to_string(),
                ),
            };

            let body = Json(json!({ "error": error_message }));
            (status, body).into_response()
        }
    }
}

// --- Domain Models ---
mod models {
    use super::*;
    use chrono::{DateTime, Utc};

    #[derive(Debug, Serialize, sqlx::Type)]
    #[sqlx(rename_all = "UPPERCASE")]
    pub enum Role {
        ADMIN,
        USER,
    }

    #[derive(Debug, Serialize, sqlx::Type)]
    #[sqlx(rename_all = "UPPERCASE")]
    pub enum PostStatus {
        DRAFT,
        PUBLISHED,
    }

    #[derive(Debug, Serialize, sqlx::FromRow)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        #[serde(skip)]
        pub password_hash: String,
        pub role: Role,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Serialize, sqlx::FromRow)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }

    #[derive(Debug, Serialize)]
    pub struct UserWithPosts {
        #[serde(flatten)]
        pub user: User,
        pub posts: Vec<Post>,
    }
}

// --- Data Access Layer (Repositories) ---
mod repositories {
    use super::models::{Post, User};
    use super::errors::AppError;
    use sqlx::SqlitePool;
    use uuid::Uuid;

    #[derive(Debug, Deserialize)]
    pub struct UserFilters {
        pub is_active: Option<bool>,
    }

    pub async fn find_user_by_id(pool: &SqlitePool, id: Uuid) -> Result<User, AppError> {
        sqlx::query_as!(User, "SELECT id, email, password_hash, role as \"role: _\", is_active, created_at FROM users WHERE id = ?", id)
            .fetch_one(pool)
            .await
            .map_err(AppError::from)
    }

    pub async fn find_posts_by_user_id(pool: &SqlitePool, user_id: Uuid) -> Result<Vec<Post>, AppError> {
        sqlx::query_as!(Post, "SELECT id, user_id, title, content, status as \"status: _\" FROM posts WHERE user_id = ?", user_id)
            .fetch_all(pool)
            .await
            .map_err(AppError::from)
    }

    pub async fn find_users_with_filters(pool: &SqlitePool, filters: UserFilters) -> Result<Vec<User>, AppError> {
        let mut query = "SELECT id, email, password_hash, role, is_active, created_at FROM users WHERE 1=1".to_string();
        let mut args = Vec::new();

        if let Some(is_active) = filters.is_active {
            query.push_str(" AND is_active = ?");
            args.push(is_active.to_string());
        }
        
        // This is a simplified example. A real implementation would use a proper query builder.
        // For now, we'll just handle the one filter.
        if filters.is_active.is_some() {
            sqlx::query_as::<_, User>(query.as_str())
                .bind(filters.is_active.unwrap())
                .fetch_all(pool)
                .await
                .map_err(AppError::from)
        } else {
            sqlx::query_as::<_, User>(query.as_str())
                .fetch_all(pool)
                .await
                .map_err(AppError::from)
        }
    }

    // Transactional operation example
    pub async fn create_user_and_post_tx(
        pool: &SqlitePool,
        email: &str,
        password_hash: &str,
        post_title: &str,
        post_content: &str,
    ) -> Result<(Uuid, Uuid), AppError> {
        let mut tx = pool.begin().await?;
        
        let user_id = Uuid::new_v4();
        sqlx::query!(
            "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'USER', true)",
            user_id, email, password_hash
        )
        .execute(&mut *tx)
        .await?;

        let post_id = Uuid::new_v4();
        sqlx::query!(
            "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, 'DRAFT')",
            post_id, user_id, post_title, post_content
        )
        .execute(&mut *tx)
        .await?;

        tx.commit().await?;
        Ok((user_id, post_id))
    }
}

// --- Business Logic Layer (Services) ---
mod services {
    use super::errors::AppError;
    use super::models::{User, UserWithPosts};
    use super::repositories;
    use sqlx::SqlitePool;
    use uuid::Uuid;

    pub async fn fetch_user_with_posts(pool: &SqlitePool, id: Uuid) -> Result<UserWithPosts, AppError> {
        let user = repositories::find_user_by_id(pool, id).await?;
        let posts = repositories::find_posts_by_user_id(pool, user.id).await?;
        Ok(UserWithPosts { user, posts })
    }

    pub async fn fetch_filtered_users(pool: &SqlitePool, filters: repositories::UserFilters) -> Result<Vec<User>, AppError> {
        repositories::find_users_with_filters(pool, filters).await
    }

    pub async fn create_user_with_initial_post(
        pool: &SqlitePool,
        email: &str,
        password: &str,
        post_title: &str,
        post_content: &str,
    ) -> Result<(Uuid, Uuid), AppError> {
        // In a real app, you'd hash the password here
        let password_hash = format!("hashed_{}", password);
        repositories::create_user_and_post_tx(pool, email, &password_hash, post_title, post_content).await
    }
}

// --- API Layer (Handlers) ---
mod handlers {
    use super::errors::AppError;
    use super::models::{User, UserWithPosts};
    use super::repositories::UserFilters;
    use super::services;
    use axum::{
        extract::{Path, Query, State},
        Json,
    };
    use serde::Deserialize;
    use sqlx::SqlitePool;
    use uuid::Uuid;

    #[derive(Deserialize)]
    pub struct CreateUserAndPostPayload {
        pub email: String,
        pub password: String,
        pub post_title: String,
        pub post_content: String,
    }

    pub async fn get_user(
        State(pool): State<SqlitePool>,
        Path(id): Path<Uuid>,
    ) -> Result<Json<UserWithPosts>, AppError> {
        let user_with_posts = services::fetch_user_with_posts(&pool, id).await?;
        Ok(Json(user_with_posts))
    }

    pub async fn list_users(
        State(pool): State<SqlitePool>,
        Query(filters): Query<UserFilters>,
    ) -> Result<Json<Vec<User>>, AppError> {
        let users = services::fetch_filtered_users(&pool, filters).await?;
        Ok(Json(users))
    }

    pub async fn create_user_with_post(
        State(pool): State<SqlitePool>,
        Json(payload): Json<CreateUserAndPostPayload>,
    ) -> Result<Json<(Uuid, Uuid)>, AppError> {
        let ids = services::create_user_with_initial_post(
            &pool,
            &payload.email,
            &payload.password,
            &payload.post_title,
            &payload.post_content,
        )
        .await?;
        Ok(Json(ids))
    }
}

// --- Application State ---
type AppState = SqlitePool;

// --- Database Setup ---
async fn setup_database() -> SqlitePool {
    let pool = SqlitePoolOptions::new()
        .connect("sqlite::memory:")
        .await
        .expect("Failed to connect to in-memory database");

    // Migrations
    sqlx::query(
        "CREATE TABLE users (
            id TEXT PRIMARY KEY NOT NULL,
            email TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            role TEXT NOT NULL,
            is_active BOOLEAN NOT NULL DEFAULT TRUE,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        );",
    )
    .execute(&pool)
    .await
    .expect("Failed to create users table");

    sqlx::query(
        "CREATE TABLE posts (
            id TEXT PRIMARY KEY NOT NULL,
            user_id TEXT NOT NULL,
            title TEXT NOT NULL,
            content TEXT NOT NULL,
            status TEXT NOT NULL,
            FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
        );",
    )
    .execute(&pool)
    .await
    .expect("Failed to create posts table");

    // Mock many-to-many relationship tables
    sqlx::query(
        "CREATE TABLE roles (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT UNIQUE NOT NULL
        );"
    ).execute(&pool).await.unwrap();

    sqlx::query(
        "CREATE TABLE user_roles (
            user_id TEXT NOT NULL,
            role_id TEXT NOT NULL,
            PRIMARY KEY (user_id, role_id),
            FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY(role_id) REFERENCES roles(id) ON DELETE CASCADE
        );"
    ).execute(&pool).await.unwrap();

    pool
}

// --- Main Entry Point ---
#[tokio::main]
async fn main() {
    tracing_subscriber::fmt::init();

    let db_pool = setup_database().await;

    let app = Router::new()
        .route("/users", get(handlers::list_users))
        .route("/users/with_post", post(handlers::create_user_with_post))
        .route("/users/:id", get(handlers::get_user))
        .with_state(db_pool);

    let addr = SocketAddr::from(([127, 0, 0, 1], 3000));
    tracing::info!("listening on {}", addr);
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}