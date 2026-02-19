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
tower-http = { version = "0.5.0", features = ["cors"] }
once_cell = "1.19"
dashmap = "5.5"
async-trait = "0.1.77"
thiserror = "1.0"
axum::extract::Extension
*/

use axum::{
    extract::{Request, State},
    http::{header, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response, Json},
    routing::{get, post},
    Extension, Router,
};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use thiserror::Error;
use uuid::Uuid;

// --- Domain Models ---
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
enum Role { ADMIN, USER }

#[derive(Debug, Clone, Serialize, Deserialize)]
struct User {
    id: Uuid,
    email: String,
    #[serde(skip_serializing)]
    password_hash: String,
    role: Role,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Centralized Application State ---
#[derive(Clone)]
struct AppState {
    user_repo: UserRepository,
    auth_service: AuthService,
}

impl AppState {
    fn new() -> Self {
        let user_repo = UserRepository::new();
        let auth_service = AuthService::new("jwt_super_secret_key_for_state_driven_approach".to_string());

        // Seed data
        let admin_pass = "strongpassword123";
        let admin_hash = auth_service.hash_password(admin_pass).unwrap();
        let admin = User {
            id: Uuid::new_v4(),
            email: "admin@example.com".to_string(),
            password_hash: admin_hash,
            role: Role::ADMIN,
            is_active: true,
            created_at: Utc::now(),
        };
        user_repo.save_user(admin);

        Self { user_repo, auth_service }
    }
}

// --- Data Repository Layer ---
#[derive(Clone)]
struct UserRepository(Arc<DashMap<Uuid, User>>);

impl UserRepository {
    fn new() -> Self { Self(Arc::new(DashMap::new())) }
    fn find_by_email(&self, email: &str) -> Option<User> {
        self.0.iter().find(|entry| entry.value().email == email).map(|entry| entry.value().clone())
    }
    fn find_by_id(&self, id: Uuid) -> Option<User> { self.0.get(&id).map(|u| u.value().clone()) }
    fn save_user(&self, user: User) { self.0.insert(user.id, user); }
}

// --- Service Layer ---
#[derive(Clone)]
struct AuthService {
    jwt_secret: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct Claims { sub: Uuid, role: Role, exp: i64 }

impl AuthService {
    fn new(jwt_secret: String) -> Self { Self { jwt_secret } }

    fn hash_password(&self, password: &str) -> Result<String, argon2::password_hash::Error> {
        let salt = argon2::password_hash::rand_core::OsRng.gen_salt();
        let argon2 = argon2::Argon2::default();
        argon2.hash_password_simple(password.as_bytes(), salt.as_ref()).map(|h| h.to_string())
    }

    fn verify_password(&self, hash: &str, password: &str) -> bool {
        argon2::Argon2::default().verify_password(password.as_bytes(), &argon2::PasswordHash::new(hash).unwrap()).is_ok()
    }

    fn generate_token(&self, user: &User) -> String {
        let claims = Claims {
            sub: user.id,
            role: user.role.clone(),
            exp: (Utc::now() + chrono::Duration::hours(24)).timestamp(),
        };
        jsonwebtoken::encode(&jsonwebtoken::Header::default(), &claims, &jsonwebtoken::EncodingKey::from_secret(self.jwt_secret.as_ref())).unwrap()
    }

    fn validate_token(&self, token: &str) -> Option<Claims> {
        jsonwebtoken::decode::<Claims>(token, &jsonwebtoken::DecodingKey::from_secret(self.jwt_secret.as_ref()), &jsonwebtoken::Validation::default())
            .map(|data| data.claims)
            .ok()
    }
}

// --- API Error Type ---
#[derive(Debug, Error)]
enum ApiError {
    #[error("Unauthorized")]
    Unauthorized,
    #[error("Forbidden")]
    Forbidden,
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let status = match self {
            ApiError::Unauthorized => StatusCode::UNAUTHORIZED,
            ApiError::Forbidden => StatusCode::FORBIDDEN,
        };
        (status, self.to_string()).into_response()
    }
}

// --- Middleware using Request Extensions ---
async fn auth_middleware(
    State(state): State<AppState>,
    mut request: Request,
    next: Next,
) -> Result<Response, ApiError> {
    let token = request.headers()
        .get(header::AUTHORIZATION)
        .and_then(|auth_header| auth_header.to_str().ok())
        .and_then(|auth_value| auth_value.strip_prefix("Bearer "));

    let token = token.ok_or(ApiError::Unauthorized)?;
    let claims = state.auth_service.validate_token(token).ok_or(ApiError::Unauthorized)?;
    let user = state.user_repo.find_by_id(claims.sub).ok_or(ApiError::Unauthorized)?;

    request.extensions_mut().insert(user);
    Ok(next.run(request).await)
}

async fn admin_only_middleware(
    Extension(user): Extension<User>,
    request: Request,
    next: Next,
) -> Result<Response, ApiError> {
    if user.role != Role::ADMIN {
        return Err(ApiError::Forbidden);
    }
    Ok(next.run(request).await)
}

// --- API Handlers ---
#[derive(Deserialize)]
struct LoginPayload { email: String, password: String }

async fn login_handler(
    State(state): State<AppState>,
    Json(payload): Json<LoginPayload>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let user = state.user_repo.find_by_email(&payload.email).ok_or(ApiError::Unauthorized)?;
    if !state.auth_service.verify_password(&user.password_hash, &payload.password) {
        return Err(ApiError::Unauthorized);
    }
    let token = state.auth_service.generate_token(&user);
    Ok(Json(serde_json::json!({ "token": token })))
}

async fn profile_handler(Extension(user): Extension<User>) -> Json<User> {
    Json(user)
}

#[derive(Deserialize)]
struct CreatePostPayload { title: String, content: String }

async fn create_post_handler(
    Extension(user): Extension<User>,
    Json(payload): Json<CreatePostPayload>,
) -> Json<Post> {
    let post = Post {
        id: Uuid::new_v4(),
        user_id: user.id,
        title: payload.title,
        content: payload.content,
        status: PostStatus::DRAFT,
    };
    // In a real app, we'd use a PostRepository here.
    Json(post)
}

async fn admin_dashboard_handler(Extension(user): Extension<User>) -> String {
    format!("Welcome to the admin dashboard, {}!", user.email)
}

// --- Main Function ---
#[tokio::main]
async fn main() {
    let app_state = AppState::new();

    let admin_routes = Router::new()
        .route("/dashboard", get(admin_dashboard_handler))
        .route_layer(middleware::from_fn(admin_only_middleware));

    let protected_routes = Router::new()
        .route("/profile", get(profile_handler))
        .route("/posts", post(create_post_handler))
        .nest("/admin", admin_routes)
        .route_layer(middleware::from_fn_with_state(app_state.clone(), auth_middleware));

    let app = Router::new()
        .route("/login", post(login_handler))
        .merge(protected_routes)
        .with_state(app_state);

    println!("State-driven server listening on http://127.0.0.1:3000");
    let listener = tokio::net::TcpListener::bind("127.0.0.1:3000").await.unwrap();
    axum::serve(listener, app).await.unwrap();
}