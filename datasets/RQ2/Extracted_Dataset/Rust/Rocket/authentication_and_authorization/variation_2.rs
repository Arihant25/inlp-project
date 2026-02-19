/*
--- CARGO.TOML ---
[dependencies]
rocket = { version = "0.5.0", features = ["json", "secrets"] }
serde = { version = "1.0", features = ["derive"] }
uuid = { version = "1.6", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
jsonwebtoken = "8.3"
argon2 = "0.5"
oauth2 = "4.4"
reqwest = "0.11"
once_cell = "1.18"
dashmap = "5.5"
rand = "0.8"
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
use std::sync::{Arc, Mutex};
use std::collections::HashMap;
use once_cell::sync::Lazy;
use argon2::{
    password_hash::{
        rand_core::OsRng,
        PasswordHash, PasswordHasher, PasswordVerifier, SaltString
    },
    Argon2
};
use oauth2::{
    basic::BasicClient, AuthUrl, ClientId, ClientSecret, CsrfToken, RedirectUrl, Scope,
    TokenResponse, TokenUrl,
};

// --- DOMAIN ---
mod domain {
    use super::*;
    #[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
    pub enum UserRole { ADMIN, USER }
    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub enum PublicationStatus { DRAFT, PUBLISHED }

    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        #[serde(skip_serializing)]
        pub password_hash: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PublicationStatus,
    }
}

// --- SERVICES ---
mod services {
    use super::domain::{Post, User, UserRole};
    use super::*;

    // Mock Database
    type DbStore<T> = Arc<Mutex<HashMap<Uuid, T>>>;
    
    // --- User Service ---
    #[derive(Clone)]
    pub struct UserService {
        users: DbStore<User>,
    }

    impl UserService {
        pub fn new() -> Self {
            let users = Arc::new(Mutex::new(HashMap::new()));
            let admin_id = Uuid::new_v4();
            let user_id = Uuid::new_v4();
            
            let salt = SaltString::generate(&mut OsRng);
            let admin_hash = Argon2::default().hash_password(b"adminpass", &salt).unwrap().to_string();
            let user_hash = Argon2::default().hash_password(b"userpass", &salt).unwrap().to_string();

            let mut user_map = users.lock().unwrap();
            user_map.insert(admin_id, User {
                id: admin_id,
                email: "admin@service.com".to_string(),
                password_hash: admin_hash,
                role: UserRole::ADMIN,
                is_active: true,
                created_at: Utc::now(),
            });
            user_map.insert(user_id, User {
                id: user_id,
                email: "user@service.com".to_string(),
                password_hash: user_hash,
                role: UserRole::USER,
                is_active: true,
                created_at: Utc::now(),
            });

            UserService { users }
        }

        pub fn find_by_email(&self, email: &str) -> Option<User> {
            self.users.lock().unwrap().values().find(|u| u.email == email).cloned()
        }

        pub fn find_by_id(&self, id: Uuid) -> Option<User> {
            self.users.lock().unwrap().get(&id).cloned()
        }
    }

    // --- Auth Service ---
    #[derive(Debug, Serialize, Deserialize)]
    pub struct AuthClaims {
        pub sub: String,
        pub role: UserRole,
        pub exp: i64,
    }

    pub struct AuthService {
        jwt_secret: String,
        user_service: UserService,
    }

    impl AuthService {
        pub fn new(jwt_secret: String, user_service: UserService) -> Self {
            AuthService { jwt_secret, user_service }
        }

        pub fn verify_password(&self, password: &str, hash: &str) -> bool {
            PasswordHash::new(hash)
                .and_then(|parsed_hash| Argon2::default().verify_password(password.as_bytes(), &parsed_hash))
                .is_ok()
        }

        pub fn generate_token(&self, user: &User) -> Result<String, jsonwebtoken::errors::Error> {
            let expiration = Utc::now() + chrono::Duration::days(1);
            let claims = AuthClaims {
                sub: user.id.to_string(),
                role: user.role.clone(),
                exp: expiration.timestamp(),
            };
            jsonwebtoken::encode(
                &jsonwebtoken::Header::default(),
                &claims,
                &jsonwebtoken::EncodingKey::from_secret(self.jwt_secret.as_ref()),
            )
        }

        pub fn validate_token(&self, token: &str) -> Result<AuthClaims, jsonwebtoken::errors::Error> {
            jsonwebtoken::decode::<AuthClaims>(
                token,
                &jsonwebtoken::DecodingKey::from_secret(self.jwt_secret.as_ref()),
                &jsonwebtoken::Validation::default(),
            ).map(|data| data.claims)
        }
    }

    // --- Post Service ---
    pub struct PostService {
        posts: DbStore<Post>,
    }

    impl PostService {
        pub fn new() -> Self {
            PostService { posts: Arc::new(Mutex::new(HashMap::new())) }
        }

        pub fn create(&self, user_id: Uuid, title: String, content: String) -> Post {
            let new_post = Post {
                id: Uuid::new_v4(),
                user_id,
                title,
                content,
                status: domain::PublicationStatus::DRAFT,
            };
            self.posts.lock().unwrap().insert(new_post.id, new_post.clone());
            new_post
        }

        pub fn list_all(&self) -> Vec<Post> {
            self.posts.lock().unwrap().values().cloned().collect()
        }

        pub fn delete(&self, post_id: Uuid) -> bool {
            self.posts.lock().unwrap().remove(&post_id).is_some()
        }
    }
}

// --- WEB LAYER (GUARDS & HANDLERS) ---
mod web {
    use super::domain::{User, UserRole};
    use super::services::{AuthService, PostService, UserService};
    use super::*;

    // --- Guards ---
    pub struct Authenticated(pub User);
    pub struct Admin(pub User);

    #[rocket::async_trait]
    impl<'r> FromRequest<'r> for Authenticated {
        type Error = Value;
        async fn from_request(req: &'r Request<'_>) -> Outcome<Self, Self::Error> {
            let auth_svc = req.guard::<&State<Arc<AuthService>>>().await.unwrap();
            let user_svc = req.guard::<&State<Arc<UserService>>>().await.unwrap();

            let token = match req.headers().get_one("Authorization").and_then(|v| v.strip_prefix("Bearer ")) {
                Some(t) => t,
                None => return Outcome::Failure((Status::Unauthorized, json!({"error": "Missing auth token"}))),
            };

            let claims = match auth_svc.validate_token(token) {
                Ok(c) => c,
                Err(_) => return Outcome::Failure((Status::Unauthorized, json!({"error": "Invalid auth token"}))),
            };

            let user_id = Uuid::parse_str(&claims.sub).unwrap();
            match user_svc.find_by_id(user_id) {
                Some(user) if user.is_active => Outcome::Success(Authenticated(user)),
                _ => Outcome::Failure((Status::Unauthorized, json!({"error": "User not found"}))),
            }
        }
    }

    #[rocket::async_trait]
    impl<'r> FromRequest<'r> for Admin {
        type Error = Value;
        async fn from_request(req: &'r Request<'_>) -> Outcome<Self, Self::Error> {
            match Authenticated::from_request(req).await {
                Outcome::Success(Authenticated(user)) if user.role == UserRole::ADMIN => Outcome::Success(Admin(user)),
                Outcome::Success(_) => Outcome::Failure((Status::Forbidden, json!({"error": "Requires admin privileges"}))),
                Outcome::Failure(e) => Outcome::Failure(e),
                Outcome::Forward(f) => Outcome::Forward(f),
            }
        }
    }

    // --- Handlers ---
    #[derive(Deserialize)]
    pub struct LoginPayload<'r> {
        email: &'r str,
        password: &'r str,
    }

    #[post("/login", data = "<payload>")]
    pub fn login(
        auth_svc: &State<Arc<AuthService>>,
        user_svc: &State<Arc<UserService>>,
        payload: Json<LoginPayload<'_>>,
    ) -> Result<Value, (Status, Value)> {
        let user = user_svc.find_by_email(payload.email)
            .ok_or_else(|| (Status::Unauthorized, json!({"error": "Invalid credentials"})))?;

        if auth_svc.verify_password(payload.password, &user.password_hash) {
            let token = auth_svc.generate_token(&user)
                .map_err(|_| (Status::InternalServerError, json!({"error": "Token generation failed"})))?;
            Ok(json!({ "token": token }))
        } else {
            Err((Status::Unauthorized, json!({"error": "Invalid credentials"})))
        }
    }

    #[get("/me")]
    pub fn current_user(auth: Authenticated) -> Json<User> {
        Json(auth.0)
    }

    #[derive(Deserialize)]
    pub struct NewPostPayload {
        title: String,
        content: String,
    }

    #[post("/posts", data = "<payload>")]
    pub fn create_post(
        post_svc: &State<Arc<PostService>>,
        auth: Authenticated,
        payload: Json<NewPostPayload>,
    ) -> (Status, Json<domain::Post>) {
        let post = post_svc.create(auth.0.id, payload.title.clone(), payload.content.clone());
        (Status::Created, Json(post))
    }

    #[get("/posts")]
    pub fn get_all_posts(_auth: Authenticated, post_svc: &State<Arc<PostService>>) -> Json<Vec<domain::Post>> {
        Json(post_svc.list_all())
    }

    #[delete("/posts/<id>")]
    pub fn remove_post(_admin: Admin, post_svc: &State<Arc<PostService>>, id: Uuid) -> Status {
        if post_svc.delete(id) {
            Status::NoContent
        } else {
            Status::NotFound
        }
    }

    // --- OAuth2 Handlers ---
    pub struct OAuthConfig {
        pub client_id: String,
        pub client_secret: String,
    }

    fn get_oauth_client(config: &State<OAuthConfig>) -> BasicClient {
        BasicClient::new(
            ClientId::new(config.client_id.clone()),
            Some(ClientSecret::new(config.client_secret.clone())),
            AuthUrl::new("https://accounts.google.com/o/oauth2/v2/auth".to_string()).unwrap(),
            Some(TokenUrl::new("https://www.googleapis.com/oauth2/v4/token".to_string()).unwrap()),
        )
        .set_redirect_uri(RedirectUrl::new("http://localhost:8000/auth/google/callback".to_string()).unwrap())
    }

    #[get("/auth/google")]
    pub fn oauth_redirect(config: &State<OAuthConfig>, cookies: &CookieJar<'_>) -> Redirect {
        let client = get_oauth_client(config);
        let (auth_url, csrf_token) = client.authorize_url(CsrfToken::new_random).url();
        cookies.add(("oauth_csrf_token", csrf_token.secret().clone()));
        Redirect::to(auth_url)
    }

    #[derive(Deserialize)]
    pub struct CallbackQuery { code: String, state: String }

    #[get("/auth/google/callback?<query>")]
    pub async fn oauth_callback(
        config: &State<OAuthConfig>,
        cookies: &CookieJar<'_>,
        query: CallbackQuery,
        auth_svc: &State<Arc<AuthService>>,
        user_svc: &State<Arc<UserService>>,
    ) -> Result<Value, Flash<Redirect>> {
        let stored_token = cookies.get("oauth_csrf_token").map(|c| c.value().to_string());
        if stored_token.is_none() || stored_token.unwrap() != query.state {
            return Err(Flash::error(Redirect::to("/login"), "CSRF mismatch"));
        }
        cookies.remove("oauth_csrf_token");

        let client = get_oauth_client(config);
        let token_result = client.exchange_code(oauth2::AuthorizationCode::new(query.code))
            .request_async(oauth2::reqwest::async_http_client).await;

        if token_result.is_ok() {
            // Mock: In a real app, get user info from provider. Here, we log in the default user.
            let user = user_svc.find_by_email("user@service.com").unwrap();
            let jwt = auth_svc.generate_token(&user).unwrap();
            Ok(json!({ "message": "OAuth login successful (mocked)", "token": jwt }))
        } else {
            Err(Flash::error(Redirect::to("/login"), "OAuth token exchange failed"))
        }
    }
}

#[launch]
fn rocket() -> _ {
    let user_service = Arc::new(services::UserService::new());
    let auth_service = Arc::new(services::AuthService::new(
        "a_very_secret_key_for_jwt_2".to_string(),
        user_service.clone(),
    ));
    let post_service = Arc::new(services::PostService::new());
    let oauth_config = web::OAuthConfig {
        client_id: std::env::var("GOOGLE_CLIENT_ID").unwrap_or_else(|_| "test_id".to_string()),
        client_secret: std::env::var("GOOGLE_CLIENT_SECRET").unwrap_or_else(|_| "test_secret".to_string()),
    };

    rocket::build()
        .manage(user_service)
        .manage(auth_service)
        .manage(post_service)
        .manage(oauth_config)
        .mount("/", routes![
            web::login,
            web::current_user,
            web::create_post,
            web::get_all_posts,
            web::remove_post,
            web::oauth_redirect,
            web::oauth_callback,
        ])
}