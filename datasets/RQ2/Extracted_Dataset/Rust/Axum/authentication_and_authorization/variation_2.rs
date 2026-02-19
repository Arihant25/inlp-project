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
bcrypt = "0.15"
axum-extra = { version = "0.9", features = ["typed-header"] }
headers = "0.4"
tower-http = { version = "0.5.0", features = ["trace"] }
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
async-trait = "0.1.77"
http = "1.0"
hyper = "1.2"
std::sync::Arc
*/

use axum::{
    async_trait,
    extract::{FromRequestParts, Json, State},
    http::{header, request::Parts, Request, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
    Router,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use uuid::Uuid;

// --- DOMAIN MODELS ---
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum Role {
    ADMIN,
    USER,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct User {
    id: Uuid,
    email: String,
    #[serde(skip_deserializing)]
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

// --- APPLICATION CONTEXT (STATE) ---
type Db = Arc<Mutex<HashMap<Uuid, User>>>;

#[derive(Clone)]
struct AppContext {
    db: Db,
    jwt_secret: String,
}

// --- AUTHENTICATION EXTRACTOR ---
#[derive(Debug, Serialize, Deserialize)]
struct AuthClaims {
    sub: Uuid,
    role: Role,
    exp: i64,
}

// This extractor validates the JWT and provides the authenticated user's ID and role.
struct AuthUser {
    id: Uuid,
    role: Role,
}

#[async_trait]
impl<S> FromRequestParts<S> for AuthUser
where
    S: Send + Sync,
    AppContext: FromRef<S>,
{
    type Rejection = (StatusCode, &'static str);

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
        let context = AppContext::from_ref(state);
        let auth_header = parts
            .headers
            .get(header::AUTHORIZATION)
            .and_then(|value| value.to_str().ok())
            .ok_or((StatusCode::UNAUTHORIZED, "Missing authorization header"))?;

        let token = auth_header
            .strip_prefix("Bearer ")
            .ok_or((StatusCode::UNAUTHORIZED, "Invalid token format"))?;

        let claims = jsonwebtoken::decode::<AuthClaims>(
            token,
            &jsonwebtoken::DecodingKey::from_secret(context.jwt_secret.as_ref()),
            &jsonwebtoken::Validation::new(jsonwebtoken::Algorithm::HS256),
        )
        .map(|data| data.claims)
        .map_err(|_| (StatusCode::UNAUTHORIZED, "Invalid token"))?;

        Ok(AuthUser {
            id: claims.sub,
            role: claims.role,
        })
    }
}

// --- RBAC MIDDLEWARE ---
async fn require_role<B>(
    State(context): State<AppContext>,
    auth_user: AuthUser, // Extractor runs first
    mut req: Request<B>,
    next: Next,
) -> Result<Response, (StatusCode, &'static str)> {
    let required_role = req
        .extensions()
        .get::<Role>()
        .expect("Role not set in require_role middleware extension");

    if &auth_user.role != required_role {
        return Err((StatusCode::FORBIDDEN, "Insufficient permissions"));
    }
    
    // Put the full user object in extensions for the handler to use
    let db_lock = context.db.lock().unwrap();
    if let Some(user) = db_lock.get(&auth_user.id) {
        req.extensions_mut().insert(user.clone());
    } else {
        return Err((StatusCode::UNAUTHORIZED, "User not found"));
    }

    Ok(next.run(req).await)
}

// --- HANDLERS (FUNCTIONAL STYLE) ---
#[derive(Deserialize)]
struct LoginReq {
    email: String,
    password: String,
}

#[derive(Serialize)]
struct LoginRes {
    token: String,
}

async fn login(
    State(context): State<AppContext>,
    Json(payload): Json<LoginReq>,
) -> impl IntoResponse {
    let db_lock = context.db.lock().unwrap();
    let user = db_lock
        .values()
        .find(|u| u.email == payload.email);

    match user {
        Some(user) if bcrypt::verify(&payload.password, &user.password_hash).unwrap_or(false) => {
            let claims = AuthClaims {
                sub: user.id,
                role: user.role.clone(),
                exp: (Utc::now() + chrono::Duration::days(1)).timestamp(),
            };
            let token = jsonwebtoken::encode(
                &jsonwebtoken::Header::default(),
                &claims,
                &jsonwebtoken::EncodingKey::from_secret(context.jwt_secret.as_ref()),
            )
            .unwrap();
            Ok(Json(LoginRes { token }))
        }
        _ => Err((StatusCode::UNAUTHORIZED, "Invalid email or password")),
    }
}

#[derive(Deserialize)]
struct CreatePostReq {
    title: String,
    content: String,
}

async fn create_post(
    auth_user: AuthUser, // Just need the ID to associate the post
    Json(payload): Json<CreatePostReq>,
) -> impl IntoResponse {
    let new_post = Post {
        id: Uuid::new_v4(),
        user_id: auth_user.id,
        title: payload.title,
        content: payload.content,
        status: PostStatus::DRAFT,
    };
    // In a real app, you'd save this to the DB
    (StatusCode::CREATED, Json(new_post))
}

async fn get_admin_report(
    axum::extract::Extension(user): axum::extract::Extension<User>, // User from middleware
) -> impl IntoResponse {
    Json(serde_json::json!({
        "message": format!("This is a secret report for admin: {}", user.email),
        "user_role": user.role,
    }))
}

// --- MAIN SETUP ---
#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::new("info"))
        .init();

    // Mock DB setup
    let mut users = HashMap::new();
    let admin_id = Uuid::new_v4();
    let admin_user = User {
        id: admin_id,
        email: "admin@dev.io".to_string(),
        password_hash: bcrypt::hash("adminpass", bcrypt::DEFAULT_COST).unwrap(),
        role: Role::ADMIN,
        is_active: true,
        created_at: Utc::now(),
    };
    users.insert(admin_id, admin_user);

    let context = AppContext {
        db: Arc::new(Mutex::new(users)),
        jwt_secret: "secret".to_string(),
    };

    // Middleware for RBAC
    let admin_only = middleware::from_fn_with_state(context.clone(), |req, next| async {
        req.extensions_mut().insert(Role::ADMIN);
        require_role(State(context.clone()), AuthUser::from_request_parts(&mut req.into_parts().0, &context).await?, req, next).await
    });

    let app = Router::new()
        .route("/auth/login", post(login))
        .route(
            "/posts/create",
            post(create_post).route_layer(middleware::from_fn_with_state(context.clone(), |req, next| async {
                // A simple auth check layer
                AuthUser::from_request_parts(&mut req.into_parts().0, &context).await?;
                Ok::<_, (StatusCode, &'static str)>(next.run(req).await)
            })),
        )
        .route("/admin/report", get(get_admin_report).route_layer(admin_only))
        .with_state(context)
        .layer(tower_http::trace::TraceLayer::new_for_http());

    let listener = tokio::net::TcpListener::bind("127.0.0.1:3000").await.unwrap();
    tracing::info!("listening on {}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.unwrap();
}