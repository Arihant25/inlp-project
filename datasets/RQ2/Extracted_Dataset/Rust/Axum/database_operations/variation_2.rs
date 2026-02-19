// Variation 2: "Fat Handler" / Functional Style
// This approach co-locates database logic directly within the Axum handlers.
// It's simpler and quicker for smaller projects or APIs where business logic
// is not complex, reducing boilerplate. It can be less testable and harder
// to maintain as the application grows.

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
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
anyhow = "1"
*/

use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use sqlx::{sqlite::SqlitePoolOptions, SqlitePool};
use std::net::SocketAddr;
use uuid::Uuid;

// --- Models ---
mod domain {
    use super::*;
    use chrono::{DateTime, Utc};

    #[derive(Debug, Clone, Serialize, sqlx::Type)]
    #[sqlx(rename_all = "UPPERCASE")]
    pub enum UserRole {
        ADMIN,
        USER,
    }

    #[derive(Debug, Clone, Serialize, sqlx::Type)]
    #[sqlx(rename_all = "UPPERCASE")]
    pub enum PublicationStatus {
        DRAFT,
        PUBLISHED,
    }

    #[derive(Debug, Clone, Serialize, sqlx::FromRow)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Clone, Serialize, sqlx::FromRow)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PublicationStatus,
    }

    #[derive(Debug, Serialize)]
    pub struct UserProfile {
        #[serde(flatten)]
        pub user_data: User,
        pub posts: Vec<Post>,
        pub assigned_roles: Vec<String>,
    }
}

// --- Custom Error Type ---
struct AppError(anyhow::Error);

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        tracing::error!("Application error: {:#}", self.0);
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

// --- API Handlers ---
mod api_handlers {
    use super::domain::{Post, User, UserProfile};
    use super::AppError;
    use axum::{
        extract::{Path, Query, State},
        http::StatusCode,
        Json,
    };
    use serde::Deserialize;
    use sqlx::SqlitePool;
    use uuid::Uuid;

    #[derive(Deserialize)]
    pub struct UserQuery {
        active: Option<bool>,
    }

    // Query building with filters directly in the handler
    pub async fn list_users(
        State(db_pool): State<SqlitePool>,
        Query(params): Query<UserQuery>,
    ) -> Result<Json<Vec<User>>, AppError> {
        let mut query_builder = sqlx::QueryBuilder::new("SELECT id, email, role, is_active, created_at FROM users WHERE 1=1");

        if let Some(is_active) = params.active {
            query_builder.push(" AND is_active = ");
            query_builder.push_bind(is_active);
        }

        let users = query_builder
            .build_query_as()
            .fetch_all(&db_pool)
            .await?;
        Ok(Json(users))
    }

    // One-to-many and Many-to-many data fetching in one handler
    pub async fn get_user_profile(
        State(db_pool): State<SqlitePool>,
        Path(user_id): Path<Uuid>,
    ) -> Result<Json<UserProfile>, AppError> {
        let user_data = sqlx::query_as!(
            User,
            "SELECT id, email, role as \"role: _\", is_active, created_at FROM users WHERE id = ?",
            user_id
        )
        .fetch_one(&db_pool)
        .await?;

        let posts = sqlx::query_as!(
            Post,
            "SELECT id, user_id, title, content, status as \"status: _\" FROM posts WHERE user_id = ?",
            user_id
        )
        .fetch_all(&db_pool)
        .await?;

        let assigned_roles: Vec<(String,)> = sqlx::query_as(
            "SELECT r.name FROM roles r JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = ?"
        )
        .bind(user_id)
        .fetch_all(&db_pool)
        .await?;

        let profile = UserProfile {
            user_data,
            posts,
            assigned_roles: assigned_roles.into_iter().map(|(name,)| name).collect(),
        };

        Ok(Json(profile))
    }

    #[derive(Deserialize)]
    pub struct CreateUserPayload {
        email: String,
        pass: String,
    }

    // Transactional operation directly in the handler
    pub async fn create_user_and_assign_roles(
        State(db_pool): State<SqlitePool>,
        Json(payload): Json<CreateUserPayload>,
    ) -> Result<StatusCode, AppError> {
        let user_id = Uuid::new_v4();
        let password_hash = format!("hashed:{}", payload.pass);

        let mut tx = db_pool.begin().await?;

        sqlx::query!(
            "INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, 'USER')",
            user_id,
            payload.email,
            password_hash
        )
        .execute(&mut *tx)
        .await?;

        // Assume 'USER_ROLE_ID' is a known UUID for the 'USER' role
        let user_role_id: Uuid = sqlx::query_scalar("SELECT id FROM roles WHERE name = 'USER'")
            .fetch_one(&mut *tx)
            .await?;

        sqlx::query!(
            "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)",
            user_id,
            user_role_id
        )
        .execute(&mut *tx)
        .await?;

        tx.commit().await?;

        Ok(StatusCode::CREATED)
    }
}

// --- Database Setup ---
async fn database_setup() -> SqlitePool {
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

    // Seed roles
    sqlx::query("INSERT INTO roles (id, name) VALUES (?, 'USER')")
        .bind(Uuid::new_v4().to_string())
        .execute(&pool).await.unwrap();
    sqlx::query("INSERT INTO roles (id, name) VALUES (?, 'ADMIN')")
        .bind(Uuid::new_v4().to_string())
        .execute(&pool).await.unwrap();

    pool
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter("info")
        .init();

    let db_connection_pool = database_setup().await;

    let app_router = Router::new()
        .route("/users", get(api_handlers::list_users).post(api_handlers::create_user_and_assign_roles))
        .route("/users/:user_id/profile", get(api_handlers::get_user_profile))
        .with_state(db_connection_pool);

    let addr = SocketAddr::from(([127, 0, 0, 1], 3000));
    tracing::info!("Server running at http://{}", addr);
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app_router).await.unwrap();
}