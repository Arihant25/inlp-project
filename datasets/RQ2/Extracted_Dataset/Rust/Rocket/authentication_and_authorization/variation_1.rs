/*
--- CARGO.TOML ---
[dependencies]
rocket = { version = "0.5.0", features = ["json", "secrets"] }
serde = { version = "1.0", features = ["derive"] }
uuid = { version = "1.6", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
jsonwebtoken = "8.3"
bcrypt = "0.15"
oauth2 = "4.4"
reqwest = "0.11"
once_cell = "1.18"
dashmap = "5.5"
*/

#[macro_use]
extern crate rocket;

use rocket::{
    http::{CookieJar, Status},
    request::{FromRequest, Outcome, Request},
    response::{Redirect, Flash},
    serde::json::{json, Json, Value},
    State,
};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::collections::HashMap;
use once_cell::sync::Lazy;
use dashmap::DashMap;
use oauth2::{
    basic::BasicClient, AuthUrl, ClientId, ClientSecret, CsrfToken, RedirectUrl, Scope,
    TokenResponse, TokenUrl,
};

// --- 1. DOMAIN MODELS ---
mod models {
    use super::*;

    #[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
    pub enum Role {
        ADMIN,
        USER,
    }

    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub enum PostStatus {
        DRAFT,
        PUBLISHED,
    }

    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        #[serde(skip_serializing)]
        pub password_hash: String,
        pub role: Role,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }
}

// --- 2. MOCK DATABASE ---
mod db {
    use super::models::{Post, Role, User};
    use super::*;

    pub type Db = DashMap<Uuid, User>;

    pub static MOCK_USERS: Lazy<Db> = Lazy::new(|| {
        let db = DashMap::new();
        let admin_id = Uuid::new_v4();
        let user_id = Uuid::new_v4();

        let admin = User {
            id: admin_id,
            email: "admin@example.com".to_string(),
            password_hash: crate::auth::hash_password("admin123").unwrap(),
            role: Role::ADMIN,
            is_active: true,
            created_at: Utc::now(),
        };
        let user = User {
            id: user_id,
            email: "user@example.com".to_string(),
            password_hash: crate::auth::hash_password("user123").unwrap(),
            role: Role::USER,
            is_active: true,
            created_at: Utc::now(),
        };
        db.insert(admin.id, admin);
        db.insert(user.id, user);
        db
    });

    pub static MOCK_POSTS: Lazy<DashMap<Uuid, Post>> = Lazy::new(DashMap::new);
}

// --- 3. AUTHENTICATION LOGIC ---
mod auth {
    use super::*;
    use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};

    #[derive(Debug, Serialize, Deserialize)]
    pub struct Claims {
        pub sub: String, // Subject (user id)
        pub role: models::Role,
        pub exp: usize, // Expiration time
    }

    pub fn hash_password(password: &str) -> Result<String, bcrypt::BcryptError> {
        bcrypt::hash(password, bcrypt::DEFAULT_COST)
    }

    pub fn verify_password(password: &str, hash: &str) -> Result<bool, bcrypt::BcryptError> {
        bcrypt::verify(password, hash)
    }

    pub fn create_jwt(user_id: Uuid, role: &models::Role, secret: &str) -> Result<String, jsonwebtoken::errors::Error> {
        let expiration = Utc::now()
            .checked_add_signed(chrono::Duration::hours(24))
            .expect("valid timestamp")
            .timestamp();

        let claims = Claims {
            sub: user_id.to_string(),
            role: role.clone(),
            exp: expiration as usize,
        };
        encode(&Header::default(), &claims, &EncodingKey::from_secret(secret.as_ref()))
    }

    pub fn decode_jwt(token: &str, secret: &str) -> Result<Claims, jsonwebtoken::errors::Error> {
        decode::<Claims>(token, &DecodingKey::from_secret(secret.as_ref()), &Validation::default())
            .map(|data| data.claims)
    }
}

// --- 4. REQUEST GUARDS ---
mod guards {
    use super::{auth, db, models::*, AppState};
    use super::*;

    pub struct AuthenticatedUser(pub models::User);
    pub struct AdminGuard(pub models::User);

    #[rocket::async_trait]
    impl<'r> FromRequest<'r> for AuthenticatedUser {
        type Error = Value;

        async fn from_request(req: &'r Request<'_>) -> Outcome<Self, Self::Error> {
            let app_state = req.guard::<&State<AppState>>().await.unwrap();
            
            let token = match req.headers().get_one("Authorization").and_then(|v| v.strip_prefix("Bearer ")) {
                Some(token) => token,
                None => return Outcome::Failure((Status::Unauthorized, json!({"error": "Missing token"}))),
            };

            let claims = match auth::decode_jwt(token, &app_state.jwt_secret) {
                Ok(c) => c,
                Err(_) => return Outcome::Failure((Status::Unauthorized, json!({"error": "Invalid token"}))),
            };

            let user_id = match Uuid::parse_str(&claims.sub) {
                Ok(id) => id,
                Err(_) => return Outcome::Failure((Status::Unauthorized, json!({"error": "Invalid user ID in token"}))),
            };

            match db::MOCK_USERS.get(&user_id) {
                Some(user) if user.is_active => Outcome::Success(AuthenticatedUser(user.clone())),
                _ => Outcome::Failure((Status::Unauthorized, json!({"error": "User not found or inactive"}))),
            }
        }
    }
    
    #[rocket::async_trait]
    impl<'r> FromRequest<'r> for AdminGuard {
        type Error = Value;

        async fn from_request(req: &'r Request<'_>) -> Outcome<Self, Self::Error> {
            match AuthenticatedUser::from_request(req).await {
                Outcome::Success(AuthenticatedUser(user)) => {
                    if user.role == models::Role::ADMIN {
                        Outcome::Success(AdminGuard(user))
                    } else {
                        Outcome::Failure((Status::Forbidden, json!({"error": "Admin access required"})))
                    }
                }
                Outcome::Failure(e) => Outcome::Failure(e),
                Outcome::Forward(f) => Outcome::Forward(f),
            }
        }
    }
}

// --- 5. ROUTE HANDLERS ---
mod routes {
    use super::{auth, db, guards::*, models::*, AppState};
    use super::*;

    #[derive(Deserialize)]
    pub struct LoginRequest<'r> {
        email: &'r str,
        password: &'r str,
    }

    #[post("/login", data = "<login_request>")]
    pub fn login(state: &State<AppState>, login_request: Json<LoginRequest<'_>>) -> Result<Value, (Status, Value)> {
        let user = db::MOCK_USERS
            .iter()
            .find(|entry| entry.value().email == login_request.email)
            .map(|entry| entry.value().clone());

        match user {
            Some(u) if auth::verify_password(login_request.password, &u.password_hash).unwrap_or(false) => {
                let token = auth::create_jwt(u.id, &u.role, &state.jwt_secret)
                    .map_err(|_| (Status::InternalServerError, json!({"error": "Could not create token"})))?;
                Ok(json!({ "token": token }))
            }
            _ => Err((Status::Unauthorized, json!({"error": "Invalid credentials"}))),
        }
    }

    #[get("/me")]
    pub fn get_me(auth_user: AuthenticatedUser) -> Json<models::User> {
        Json(auth_user.0)
    }

    #[derive(Deserialize)]
    pub struct CreatePostRequest {
        title: String,
        content: String,
    }

    #[post("/posts", data = "<post_data>")]
    pub fn create_post(auth_user: AuthenticatedUser, post_data: Json<CreatePostRequest>) -> (Status, Json<Post>) {
        let new_post = Post {
            id: Uuid::new_v4(),
            user_id: auth_user.0.id,
            title: post_data.title.clone(),
            content: post_data.content.clone(),
            status: PostStatus::DRAFT,
        };
        db::MOCK_POSTS.insert(new_post.id, new_post.clone());
        (Status::Created, Json(new_post))
    }

    #[get("/posts")]
    pub fn list_posts(_auth_user: AuthenticatedUser) -> Json<Vec<Post>> {
        let posts = db::MOCK_POSTS.iter().map(|entry| entry.value().clone()).collect();
        Json(posts)
    }

    #[delete("/posts/<id>")]
    pub fn delete_post(_admin: AdminGuard, id: Uuid) -> Status {
        if db::MOCK_POSTS.remove(&id).is_some() {
            Status::NoContent
        } else {
            Status::NotFound
        }
    }

    // --- OAuth2 Routes ---
    fn get_oauth_client(state: &State<AppState>) -> BasicClient {
        BasicClient::new(
            ClientId::new(state.oauth_client_id.clone()),
            Some(ClientSecret::new(state.oauth_client_secret.clone())),
            AuthUrl::new("https://accounts.google.com/o/oauth2/v2/auth".to_string()).unwrap(),
            Some(TokenUrl::new("https://www.googleapis.com/oauth2/v4/token".to_string()).unwrap()),
        )
        .set_redirect_uri(RedirectUrl::new("http://localhost:8000/auth/google/callback".to_string()).unwrap())
    }

    #[get("/auth/google")]
    pub fn google_login(state: &State<AppState>, cookies: &CookieJar<'_>) -> Redirect {
        let client = get_oauth_client(state);
        let (authorize_url, csrf_state) = client
            .authorize_url(CsrfToken::new_random)
            .add_scope(Scope::new("email".to_string()))
            .add_scope(Scope::new("profile".to_string()))
            .url();
        
        cookies.add(("oauth_csrf_state", csrf_state.secret().clone()));
        Redirect::to(authorize_url)
    }

    #[derive(Deserialize)]
    pub struct AuthCallbackQuery {
        code: String,
        state: String,
    }

    #[get("/auth/google/callback?<query>")]
    pub async fn google_callback(
        state: &State<AppState>,
        cookies: &CookieJar<'_>,
        query: AuthCallbackQuery,
    ) -> Result<Value, Flash<Redirect>> {
        let stored_state = cookies.get("oauth_csrf_state").map(|c| c.value().to_string());
        if stored_state.is_none() || stored_state.unwrap() != query.state {
            return Err(Flash::error(Redirect::to("/"), "CSRF state mismatch."));
        }
        cookies.remove("oauth_csrf_state");

        let client = get_oauth_client(state);
        let token_res = client
            .exchange_code(oauth2::AuthorizationCode::new(query.code))
            .request_async(oauth2::reqwest::async_http_client)
            .await;

        if let Ok(token) = token_res {
            // In a real app, you'd use the token to fetch user info from Google,
            // then find or create a user in your DB, and finally generate your own JWT.
            // For this mock, we'll just pretend and log in the default user.
            let user = db::MOCK_USERS.iter().find(|u| u.email == "user@example.com").unwrap();
            let jwt = auth::create_jwt(user.id, &user.role, &state.jwt_secret).unwrap();
            Ok(json!({ "message": "OAuth login successful (mocked)", "token": jwt }))
        } else {
            Err(Flash::error(Redirect::to("/"), "Failed to exchange token."))
        }
    }
}

// --- 6. APPLICATION STATE & MAIN ---
pub struct AppState {
    jwt_secret: String,
    oauth_client_id: String,
    oauth_client_secret: String,
}

#[launch]
fn rocket() -> _ {
    // Initialize DB
    let _ = &db::MOCK_USERS;
    let _ = &db::MOCK_POSTS;

    rocket::build()
        .manage(AppState {
            jwt_secret: "a_very_secret_key_for_jwt_1".to_string(),
            oauth_client_id: std::env::var("GOOGLE_CLIENT_ID").unwrap_or_else(|_| "test_id".to_string()),
            oauth_client_secret: std::env::var("GOOGLE_CLIENT_SECRET").unwrap_or_else(|_| "test_secret".to_string()),
        })
        .mount(
            "/",
            routes![
                routes::login,
                routes::get_me,
                routes::create_post,
                routes::list_posts,
                routes::delete_post,
                routes::google_login,
                routes::google_callback,
            ],
        )
}