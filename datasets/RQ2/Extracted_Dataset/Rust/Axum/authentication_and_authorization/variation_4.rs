/*
--- CARGO.TOML ---
[dependencies]
axum = { version = "0.7", features = ["macros"] }
tokio = { version = "1", features = ["full"] }
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
uuid = { version = "1.6", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
argon2 = "0.5"
rand_core = { version = "0.6", features = ["std"] }
tower-sessions = { version = "0.12.2", features = ["memory-store"] }
tower-http = { version = "0.5.0", features = ["cors"] }
oauth2 = { version = "4.4", features = ["reqwest"] }
url = "2.5"
reqwest = "0.12"
async-trait = "0.1.77"
axum::extract::FromRef
*/

use axum::{
    async_trait,
    extract::{FromRequestParts, Query, State},
    http::{request::Parts, StatusCode},
    response::{IntoResponse, Redirect, Response, Json},
    routing::{get, post},
    Router,
};
use chrono::{DateTime, Utc};
use oauth2::{
    basic::BasicClient, reqwest::async_http_client, AuthUrl, AuthorizationCode, ClientId,
    ClientSecret, CsrfToken, RedirectUrl, Scope, TokenResponse, TokenUrl,
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, RwLock};
use tower_sessions::{Expiry, Session, SessionManagerLayer};
use tower_sessions::memory_store::MemoryStore;
use uuid::Uuid;

// --- 1. Domain Schema ---
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
enum Role { ADMIN, USER }

#[derive(Debug, Clone, Serialize, Deserialize)]
struct User {
    id: Uuid,
    email: String,
    #[serde(skip_serializing)]
    password_hash: Option<String>, // Optional for OAuth users
    role: Role,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Post { id: Uuid, user_id: Uuid, title: String, content: String }

// --- 2. Application State ---
#[derive(Clone)]
struct AppState {
    db: Arc<RwLock<HashMap<Uuid, User>>>,
    oauth_client: BasicClient,
}

// --- 3. Custom Auth Extractor (Session-based) ---
struct AuthenticatedUser(User);

#[async_trait]
impl<S> FromRequestParts<S> for AuthenticatedUser
where
    S: Send + Sync,
    AppState: FromRef<S>,
{
    type Rejection = AuthError;

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
        let app_state = AppState::from_ref(state);
        let session = Session::from_request_parts(parts, state)
            .await
            .map_err(|_| AuthError::SessionError)?;

        let user_id: Uuid = session.get("user_id").await?.ok_or(AuthError::NotLoggedIn)?;
        
        let db = app_state.db.read().map_err(|_| AuthError::InternalError)?;
        let user = db.get(&user_id).cloned().ok_or(AuthError::UserNotFound)?;

        if !user.is_active {
            return Err(AuthError::Forbidden);
        }

        Ok(AuthenticatedUser(user))
    }
}

// --- 4. Error Handling ---
enum AuthError {
    NotLoggedIn,
    UserNotFound,
    Forbidden,
    InvalidCredentials,
    SessionError,
    OAuthError(String),
    InternalError,
}

impl IntoResponse for AuthError {
    fn into_response(self) -> Response {
        let (status, message) = match self {
            AuthError::NotLoggedIn => (StatusCode::UNAUTHORIZED, "Not logged in"),
            AuthError::UserNotFound => (StatusCode::NOT_FOUND, "User not found"),
            AuthError::Forbidden => (StatusCode::FORBIDDEN, "Access denied"),
            AuthError::InvalidCredentials => (StatusCode::UNAUTHORIZED, "Invalid credentials"),
            AuthError::SessionError => (StatusCode::INTERNAL_SERVER_ERROR, "Session error"),
            AuthError::OAuthError(msg) => (StatusCode::BAD_GATEWAY, &msg),
            AuthError::InternalError => (StatusCode::INTERNAL_SERVER_ERROR, "Internal server error"),
        };
        (status, message.to_string()).into_response()
    }
}

impl<E> From<tower_sessions::session::Error> for AuthError {
    fn from(_: tower_sessions::session::Error) -> Self {
        AuthError::SessionError
    }
}

// --- 5. Handlers ---
// Password-based login
#[derive(Deserialize)]
struct LoginData { email: String, password: String }

async fn password_login(
    State(state): State<AppState>,
    session: Session,
    Json(payload): Json<LoginData>,
) -> Result<impl IntoResponse, AuthError> {
    let db = state.db.read().map_err(|_| AuthError::InternalError)?;
    let user = db.values()
        .find(|u| u.email == payload.email)
        .ok_or(AuthError::InvalidCredentials)?;

    let hash = user.password_hash.as_ref().ok_or(AuthError::InvalidCredentials)?;
    if argon2::verify_encoded(hash, payload.password.as_bytes()).unwrap_or(false) {
        session.insert("user_id", user.id).await?;
        Ok(StatusCode::OK)
    } else {
        Err(AuthError::InvalidCredentials)
    }
}

// OAuth2 login start
async fn oauth_login(State(state): State<AppState>) -> impl IntoResponse {
    let (auth_url, _csrf_token) = state.oauth_client
        .authorize_url(CsrfToken::new_random)
        .add_scope(Scope::new("read:user".to_string()))
        .add_scope(Scope::new("user:email".to_string()))
        .url();
    Redirect::to(auth_url.as_str())
}

// OAuth2 callback
#[derive(Deserialize)]
struct AuthRequest { code: String, state: String }

async fn oauth_callback(
    State(state): State<AppState>,
    session: Session,
    Query(query): Query<AuthRequest>,
) -> Result<impl IntoResponse, AuthError> {
    // This is a mock. In reality, you'd exchange the code for a token.
    // let token = state.oauth_client.exchange_code(AuthorizationCode::new(query.code)).request_async(async_http_client).await;
    // Then fetch user info with the token.
    let user_email = "oauth_user@example.com".to_string(); // Mocked response

    let mut db = state.db.write().map_err(|_| AuthError::InternalError)?;
    let user = db.values_mut().find(|u| u.email == user_email).map(|u| u.clone());

    let user_to_log_in = match user {
        Some(u) => u,
        None => { // Create new user for OAuth login
            let new_user = User {
                id: Uuid::new_v4(),
                email: user_email,
                password_hash: None,
                role: Role::USER,
                is_active: true,
                created_at: Utc::now(),
            };
            db.insert(new_user.id, new_user.clone());
            new_user
        }
    };

    session.insert("user_id", user_to_log_in.id).await?;
    Ok(Redirect::to("/profile"))
}

async fn logout(session: Session) -> impl IntoResponse {
    session.delete().await.unwrap();
    Redirect::to("/")
}

// Protected route
async fn profile(AuthenticatedUser(user): AuthenticatedUser) -> impl IntoResponse {
    Json(user)
}

// Admin-only route (using a simple check in the handler)
async fn admin_panel(AuthenticatedUser(user): AuthenticatedUser) -> Result<impl IntoResponse, AuthError> {
    if user.role != Role::ADMIN {
        return Err(AuthError::Forbidden);
    }
    Ok(format!("Welcome to the admin panel, {}!", user.email))
}

// --- 6. Main Setup ---
#[tokio::main]
async fn main() {
    let session_store = MemoryStore::default();
    let session_layer = SessionManagerLayer::new(session_store)
        .with_secure(false) // for local dev
        .with_expiry(Expiry::OnInactivity(tower_sessions::cookie::time::Duration::days(1)));

    // Mock DB
    let db = Arc::new(RwLock::new(HashMap::new()));
    let admin_id = Uuid::new_v4();
    let admin_pass = "admin123";
    let admin_hash = argon2::hash_encoded(admin_pass.as_bytes(), &rand_core::OsRng.gen_salt(), &argon2::Config::default()).unwrap();
    let admin = User {
        id: admin_id,
        email: "admin@example.com".to_string(),
        password_hash: Some(admin_hash),
        role: Role::ADMIN,
        is_active: true,
        created_at: Utc::now(),
    };
    db.write().unwrap().insert(admin_id, admin);

    // Mock OAuth2 Client
    let oauth_client = BasicClient::new(
        ClientId::new("mock_client_id".to_string()),
        Some(ClientSecret::new("mock_client_secret".to_string())),
        AuthUrl::new("https://github.com/login/oauth/authorize".to_string()).unwrap(),
        Some(TokenUrl::new("https://github.com/login/oauth/access_token".to_string()).unwrap()),
    )
    .set_redirect_uri(RedirectUrl::new("http://127.0.0.1:3000/auth/callback".to_string()).unwrap());

    let app_state = AppState { db, oauth_client };

    let app = Router::new()
        .route("/", get(|| async { "Home Page" }))
        .route("/login", post(password_login))
        .route("/logout", get(logout))
        .route("/auth/login", get(oauth_login))
        .route("/auth/callback", get(oauth_callback))
        .route("/profile", get(profile))
        .route("/admin", get(admin_panel))
        .with_state(app_state)
        .layer(session_layer);

    println!("Session-based server listening on http://127.0.0.1:3000");
    let listener = tokio::net::TcpListener::bind("127.0.0.1:3000").await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

// Helper for argon2 salt generation
trait SaltGen { fn gen_salt(&mut self) -> [u8; 16]; }
impl<T: rand_core::RngCore> SaltGen for T {
    fn gen_salt(&mut self) -> [u8; 16] {
        let mut buf = [0u8; 16];
        self.fill_bytes(&mut buf);
        buf
    }
}