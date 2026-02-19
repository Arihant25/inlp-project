/*
--- CARGO.TOML ---
[dependencies]
axum = { version = "0.7", features = ["macros"] }
tokio = { version = "1", features = ["full"] }
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
uuid = { version = "1.6", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
jsonwebtoken = "9.2"
argon2 = "0.5"
rand_core = { version = "0.6", features = ["std"] }
axum-extra = { version = "0.9", features = ["typed-header"] }
headers = "0.4"
tower-http = { version = "0.5.0", features = ["cors"] }
once_cell = "1.19"
dashmap = "5.5"
async-trait = "0.1.77"
thiserror = "1.0"
*/

use axum::{
    async_trait,
    extract::{FromRequestParts, State},
    http::{request::Parts, StatusCode},
    response::{IntoResponse, Response, Json},
    routing::{get, post},
    Router,
};
use axum_extra::headers::{authorization::Bearer, Authorization};
use axum_extra::TypedHeader;
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use thiserror::Error;
use uuid::Uuid;

// --- 1. Models ---
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum Role {
    ADMIN,
    USER,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct User {
    id: Uuid,
    email: String,
    #[serde(skip_serializing)]
    password_hash: String,
    role: Role,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum PostStatus {
    DRAFT,
    PUBLISHED,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- 2. Application State & DB ---
#[derive(Clone)]
pub struct AppState {
    db: Db,
    jwt_secret: String,
}

type Db = Arc<MockDb>;

pub struct MockDb {
    users: DashMap<Uuid, User>,
    posts: DashMap<Uuid, Post>,
}

impl MockDb {
    fn new() -> Self {
        Self {
            users: DashMap::new(),
            posts: DashMap::new(),
        }
    }
}

// --- 3. Error Handling ---
#[derive(Debug, Error)]
pub enum AppError {
    #[error("Invalid credentials")]
    InvalidCredentials,
    #[error("Invalid token")]
    InvalidToken,
    #[error("User not found")]
    UserNotFound,
    #[error("Access forbidden")]
    Forbidden,
    #[error("Internal server error")]
    InternalServerError,
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, error_message) = match self {
            AppError::InvalidCredentials => (StatusCode::UNAUTHORIZED, self.to_string()),
            AppError::InvalidToken => (StatusCode::UNAUTHORIZED, self.to_string()),
            AppError::Forbidden => (StatusCode::FORBIDDEN, self.to_string()),
            AppError::UserNotFound => (StatusCode::NOT_FOUND, self.to_string()),
            AppError::InternalServerError => (StatusCode::INTERNAL_SERVER_ERROR, self.to_string()),
        };
        (status, Json(serde_json::json!({ "error": error_message }))).into_response()
    }
}

// --- 4. Authentication & Authorization Logic ---
mod auth {
    use super::*;
    use argon2::{
        password_hash::{rand_core::OsRng, PasswordHasher, SaltString},
        Argon2, PasswordVerifier,
    };
    use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};

    #[derive(Debug, Serialize, Deserialize)]
    pub struct Claims {
        pub sub: String, // Subject (user_id)
        pub role: Role,
        pub exp: usize, // Expiration time
    }

    pub fn hash_password(password: &str) -> Result<String, AppError> {
        let salt = SaltString::generate(&mut OsRng);
        Argon2::default()
            .hash_password(password.as_bytes(), &salt)
            .map(|hash| hash.to_string())
            .map_err(|_| AppError::InternalServerError)
    }

    pub fn verify_password(hash: &str, password: &str) -> Result<bool, AppError> {
        let parsed_hash = argon2::PasswordHash::new(hash).map_err(|_| AppError::InvalidCredentials)?;
        Ok(Argon2::default()
            .verify_password(password.as_bytes(), &parsed_hash)
            .is_ok())
    }

    pub fn create_jwt(user_id: Uuid, role: &Role, secret: &str) -> Result<String, AppError> {
        let expiration = Utc::now()
            .checked_add_signed(chrono::Duration::hours(24))
            .expect("valid timestamp")
            .timestamp();

        let claims = Claims {
            sub: user_id.to_string(),
            role: role.clone(),
            exp: expiration as usize,
        };
        let header = Header::new(jsonwebtoken::Algorithm::HS256);
        encode(&header, &claims, &EncodingKey::from_secret(secret.as_ref()))
            .map_err(|_| AppError::InternalServerError)
    }

    pub fn validate_jwt(token: &str, secret: &str) -> Result<Claims, AppError> {
        decode::<Claims>(
            token,
            &DecodingKey::from_secret(secret.as_ref()),
            &Validation::new(jsonwebtoken::Algorithm::HS256),
        )
        .map(|data| data.claims)
        .map_err(|_| AppError::InvalidToken)
    }

    // Auth Guard Extractor
    pub struct AuthenticatedUser(pub User);

    #[async_trait]
    impl<S> FromRequestParts<S> for AuthenticatedUser
    where
        S: Send + Sync,
        AppState: FromRef<S>,
    {
        type Rejection = AppError;

        async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
            let app_state = AppState::from_ref(state);
            let TypedHeader(Authorization(bearer)) =
                TypedHeader::<Authorization<Bearer>>::from_request_parts(parts, state)
                    .await
                    .map_err(|_| AppError::InvalidToken)?;

            let claims = validate_jwt(bearer.token(), &app_state.jwt_secret)?;
            let user_id = Uuid::parse_str(&claims.sub).map_err(|_| AppError::InvalidToken)?;

            let user = app_state
                .db
                .users
                .get(&user_id)
                .map(|u| u.value().clone())
                .ok_or(AppError::UserNotFound)?;

            if !user.is_active {
                return Err(AppError::Forbidden);
            }

            Ok(AuthenticatedUser(user))
        }
    }

    // RBAC Guard Extractor
    pub struct AdminUser(pub User);

    #[async_trait]
    impl<S> FromRequestParts<S> for AdminUser
    where
        S: Send + Sync,
        AppState: FromRef<S>,
    {
        type Rejection = AppError;

        async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
            let AuthenticatedUser(user) = AuthenticatedUser::from_request_parts(parts, state).await?;
            if user.role != Role::ADMIN {
                return Err(AppError::Forbidden);
            }
            Ok(AdminUser(user))
        }
    }
}

// --- 5. Handlers ---
mod handlers {
    use super::*;
    use auth::{AdminUser, AuthenticatedUser};

    #[derive(Deserialize)]
    pub struct LoginPayload {
        email: String,
        password: String,
    }

    pub async fn login(
        State(state): State<AppState>,
        Json(payload): Json<LoginPayload>,
    ) -> Result<Json<serde_json::Value>, AppError> {
        let user = state
            .db
            .users
            .iter()
            .find(|entry| entry.value().email == payload.email)
            .map(|entry| entry.value().clone())
            .ok_or(AppError::InvalidCredentials)?;

        if !auth::verify_password(&user.password_hash, &payload.password)? {
            return Err(AppError::InvalidCredentials);
        }

        let token = auth::create_jwt(user.id, &user.role, &state.jwt_secret)?;
        Ok(Json(serde_json::json!({ "token": token })))
    }

    #[derive(Deserialize)]
    pub struct CreatePostPayload {
        title: String,
        content: String,
    }

    pub async fn create_post(
        State(state): State<AppState>,
        AuthenticatedUser(user): AuthenticatedUser,
        Json(payload): Json<CreatePostPayload>,
    ) -> Result<Json<Post>, AppError> {
        let post = Post {
            id: Uuid::new_v4(),
            user_id: user.id,
            title: payload.title,
            content: payload.content,
            status: PostStatus::DRAFT,
        };
        state.db.posts.insert(post.id, post.clone());
        Ok(Json(post))
    }

    pub async fn get_current_user_profile(
        AuthenticatedUser(user): AuthenticatedUser,
    ) -> Json<User> {
        Json(user)
    }

    pub async fn get_all_posts_admin(
        State(state): State<AppState>,
        _admin: AdminUser,
    ) -> Json<Vec<Post>> {
        let posts = state.db.posts.iter().map(|p| p.value().clone()).collect();
        Json(posts)
    }
}

// --- 6. Main Application Setup ---
#[tokio::main]
async fn main() {
    // Initialize mock database with a user
    let db = Arc::new(MockDb::new());
    let admin_password = "verysecurepassword";
    let admin_password_hash = auth::hash_password(admin_password).unwrap();
    let admin_user = User {
        id: Uuid::new_v4(),
        email: "admin@example.com".to_string(),
        password_hash: admin_password_hash,
        role: Role::ADMIN,
        is_active: true,
        created_at: Utc::now(),
    };
    db.users.insert(admin_user.id, admin_user);

    let app_state = AppState {
        db,
        jwt_secret: "a_very_secret_key".to_string(),
    };

    let app = Router::new()
        .route("/login", post(handlers::login))
        .route("/profile", get(handlers::get_current_user_profile))
        .route("/posts", post(handlers::create_post))
        .route("/admin/posts", get(handlers::get_all_posts_admin))
        .with_state(app_state);

    println!("Server running on http://127.0.0.1:3000");
    let listener = tokio::net::TcpListener::bind("127.0.0.1:3000").await.unwrap();
    axum::serve(listener, app).await.unwrap();
}