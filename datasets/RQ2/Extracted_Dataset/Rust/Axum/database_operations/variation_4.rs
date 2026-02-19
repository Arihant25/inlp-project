// Variation 4: CQRS-inspired (Command Query Responsibility Segregation) Style
// This pattern separates read operations (Queries) from write operations (Commands).
// This leads to a clear and intentional design. Handlers become simple translators
// from HTTP requests to Commands or Queries, which are then processed by dedicated
// handlers. This improves maintainability and reasoning about state changes.

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

// --- Shared Models and Error Handling ---
mod shared {
    use super::*;
    use anyhow::anyhow;
    use chrono::{DateTime, Utc};

    // --- Models ---
    #[derive(Debug, Serialize, sqlx::Type, Clone, Copy)]
    #[sqlx(rename_all = "UPPERCASE")]
    pub enum Role { ADMIN, USER }

    #[derive(Debug, Serialize, sqlx::Type, Clone, Copy)]
    #[sqlx(rename_all = "UPPERCASE")]
    pub enum PostStatus { DRAFT, PUBLISHED }

    #[derive(Debug, Serialize, sqlx::FromRow, Clone)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        pub role: Role,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Serialize, sqlx::FromRow, Clone)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }

    // --- Error Handling ---
    pub struct AppError(pub anyhow::Error);
    impl IntoResponse for AppError {
        fn into_response(self) -> Response {
            tracing::error!("Error occurred: {:?}", self.0);
            (StatusCode::INTERNAL_SERVER_ERROR, "Internal Server Error").into_response()
        }
    }
    impl<E> From<E> for AppError where E: Into<anyhow::Error> {
        fn from(err: E) -> Self { Self(err.into()) }
    }
}

// --- Query Side (Reading Data) ---
mod queries {
    use super::shared::{AppError, Post, User};
    use serde::Deserialize;
    use sqlx::SqlitePool;
    use uuid::Uuid;

    // Query message for filtering users
    #[derive(Deserialize)]
    pub struct ListUsersQuery {
        pub is_active: Option<bool>,
    }

    // Query handler for listing users
    pub async fn list_users_handler(
        db: &SqlitePool,
        query: ListUsersQuery,
    ) -> Result<Vec<User>, AppError> {
        let mut sql = "SELECT id, email, role, is_active, created_at FROM users WHERE 1=1".to_string();
        if query.is_active.is_some() {
            sql.push_str(" AND is_active = ?");
        }
        
        let users = if let Some(active) = query.is_active {
            sqlx::query_as::<_, User>(&sql).bind(active).fetch_all(db).await?
        } else {
            sqlx::query_as::<_, User>(&sql).fetch_all(db).await?
        };
        
        Ok(users)
    }

    // A more complex query result combining multiple entities
    #[derive(Serialize)]
    pub struct UserDetails {
        #[serde(flatten)]
        user: User,
        posts: Vec<Post>,
        roles: Vec<String>,
    }

    // Query handler for getting detailed user info
    pub async fn get_user_details_handler(
        db: &SqlitePool,
        user_id: Uuid,
    ) -> Result<UserDetails, AppError> {
        let user = sqlx::query_as!(User, "SELECT id, email, role as \"role: _\", is_active, created_at FROM users WHERE id = ?", user_id)
            .fetch_one(db).await?;
        
        let posts = sqlx::query_as!(Post, "SELECT id, user_id, title, content, status as \"status: _\" FROM posts WHERE user_id = ?", user_id)
            .fetch_all(db).await?;

        let roles: Vec<(String,)> = sqlx::query_as("SELECT r.name FROM roles r JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = ?")
            .bind(user_id).fetch_all(db).await?;

        Ok(UserDetails {
            user,
            posts,
            roles: roles.into_iter().map(|(name,)| name).collect(),
        })
    }
}

// --- Command Side (Writing Data) ---
mod commands {
    use super::shared::{AppError, Post, PostStatus, Role, User};
    use serde::Deserialize;
    use sqlx::SqlitePool;
    use uuid::Uuid;

    // Command message for creating a user
    #[derive(Deserialize)]
    pub struct CreateUserCommand {
        pub email: String,
        pub password: String,
    }

    // Command handler for creating a user
    pub async fn create_user_handler(
        db: &SqlitePool,
        cmd: CreateUserCommand,
    ) -> Result<User, AppError> {
        let password_hash = format!("hashed:{}", cmd.password);
        let user = User {
            id: Uuid::new_v4(),
            email: cmd.email,
            role: Role::USER,
            is_active: true,
            created_at: chrono::Utc::now(),
        };

        sqlx::query("INSERT INTO users (id, email, password_hash, role, is_active, created_at) VALUES (?, ?, ?, ?, ?, ?)")
            .bind(user.id).bind(&user.email).bind(password_hash).bind(user.role).bind(user.is_active).bind(user.created_at)
            .execute(db).await?;
        
        Ok(user)
    }

    // Transactional command
    #[derive(Deserialize)]
    pub struct CreateUserWithPostCommand {
        pub email: String,
        pub password: String,
        pub post_title: String,
        pub post_content: String,
    }

    // Transactional command handler
    pub async fn create_user_with_post_handler(
        db: &SqlitePool,
        cmd: CreateUserWithPostCommand,
    ) -> Result<(User, Post), AppError> {
        let mut tx = db.begin().await?;

        let password_hash = format!("hashed:{}", cmd.password);
        let user = User {
            id: Uuid::new_v4(),
            email: cmd.email,
            role: Role::USER,
            is_active: true,
            created_at: chrono::Utc::now(),
        };
        sqlx::query("INSERT INTO users (id, email, password_hash, role, is_active, created_at) VALUES (?, ?, ?, ?, ?, ?)")
            .bind(user.id).bind(&user.email).bind(password_hash).bind(user.role).bind(user.is_active).bind(user.created_at)
            .execute(&mut *tx).await?;

        let post = Post {
            id: Uuid::new_v4(),
            user_id: user.id,
            title: cmd.post_title,
            content: cmd.post_content,
            status: PostStatus::DRAFT,
        };
        sqlx::query("INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)")
            .bind(post.id).bind(post.user_id).bind(&post.title).bind(&post.content).bind(post.status)
            .execute(&mut *tx).await?;

        tx.commit().await?;
        Ok((user, post))
    }
}

// --- HTTP Handlers (Thin Layer) ---
mod http_handlers {
    use super::commands::{self, CreateUserCommand, CreateUserWithPostCommand};
    use super::queries::{self, ListUsersQuery};
    use super::shared::AppError;
    use axum::{
        extract::{Path, Query, State},
        http::StatusCode,
        Json,
    };
    use sqlx::SqlitePool;
    use uuid::Uuid;

    pub async fn handle_list_users(
        State(db): State<SqlitePool>,
        Query(query): Query<ListUsersQuery>,
    ) -> Result<Json<Vec<super::shared::User>>, AppError> {
        let users = queries::list_users_handler(&db, query).await?;
        Ok(Json(users))
    }

    pub async fn handle_get_user_details(
        State(db): State<SqlitePool>,
        Path(user_id): Path<Uuid>,
    ) -> Result<Json<queries::UserDetails>, AppError> {
        let details = queries::get_user_details_handler(&db, user_id).await?;
        Ok(Json(details))
    }

    pub async fn handle_create_user(
        State(db): State<SqlitePool>,
        Json(cmd): Json<CreateUserCommand>,
    ) -> Result<(StatusCode, Json<super::shared::User>), AppError> {
        let user = commands::create_user_handler(&db, cmd).await?;
        Ok((StatusCode::CREATED, Json(user)))
    }

    pub async fn handle_create_user_with_post(
        State(db): State<SqlitePool>,
        Json(cmd): Json<CreateUserWithPostCommand>,
    ) -> Result<(StatusCode, Json<(super::shared::User, super::shared::Post)>), AppError> {
        let result = commands::create_user_with_post_handler(&db, cmd).await?;
        Ok((StatusCode::CREATED, Json(result)))
    }
}

// --- Database Setup ---
async fn setup_database() -> SqlitePool {
    let pool = SqlitePoolOptions::new().connect("sqlite::memory:").await.unwrap();
    sqlx::query("CREATE TABLE users (id TEXT PRIMARY KEY, email TEXT UNIQUE, password_hash TEXT, role TEXT, is_active BOOLEAN, created_at TIMESTAMP);").execute(&pool).await.unwrap();
    sqlx::query("CREATE TABLE posts (id TEXT PRIMARY KEY, user_id TEXT, title TEXT, content TEXT, status TEXT, FOREIGN KEY(user_id) REFERENCES users(id));").execute(&pool).await.unwrap();
    sqlx::query("CREATE TABLE roles (id TEXT PRIMARY KEY, name TEXT UNIQUE);").execute(&pool).await.unwrap();
    sqlx::query("CREATE TABLE user_roles (user_id TEXT, role_id TEXT, PRIMARY KEY (user_id, role_id));").execute(&pool).await.unwrap();
    pool
}

// --- Main Entry Point ---
#[tokio::main]
async fn main() {
    tracing_subscriber::fmt().with_env_filter("info").init();
    let db_pool = setup_database().await;

    let app = Router::new()
        .route("/users", get(http_handlers::handle_list_users).post(http_handlers::handle_create_user))
        .route("/users/with-post", post(http_handlers::handle_create_user_with_post))
        .route("/users/:user_id", get(http_handlers::handle_get_user_details))
        .with_state(db_pool);

    let addr = SocketAddr::from(([127, 0, 0, 1], 3000));
    tracing::info!("CQRS-style server listening on {}", addr);
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}