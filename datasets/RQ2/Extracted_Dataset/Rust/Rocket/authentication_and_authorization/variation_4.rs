/*
--- CARGO.TOML ---
[dependencies]
rocket = { version = "0.5.0", features = ["json", "secrets"] }
serde = { version = "1.0", features = ["derive"] }
uuid = { version = "1.6", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
jsonwebtoken = "8.3"
scrypt = "0.11"
oauth2 = "4.4"
reqwest = "0.11"
async-trait = "0.1.74"
tokio = { version = "1", features = ["sync"] }
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
use std::sync::Arc;
use std::collections::HashMap;
use async_trait::async_trait;
use tokio::sync::RwLock;
use scrypt::{
    password_hash::{
        rand_core::OsRng,
        PasswordHash, PasswordHasher, PasswordVerifier, SaltString
    },
    Scrypt
};
use oauth2::{
    basic::BasicClient, AuthUrl, ClientId, ClientSecret, CsrfToken, RedirectUrl, Scope,
    TokenResponse, TokenUrl,
};

// --- DOMAIN ---
mod domain {
    use super::*;
    #[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
    pub enum Role { ADMIN, USER }
    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub enum PostStatus { DRAFT, PUBLISHED }

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

// --- REPOSITORY TRAITS & IMPLEMENTATIONS ---
mod repository {
    use super::domain::{Post, User};
    use super::*;

    #[async_trait]
    pub trait UserRepository: Send + Sync {
        async fn find_by_id(&self, id: Uuid) -> Option<User>;
        async fn find_by_email(&self, email: &str) -> Option<User>;
    }

    #[async_trait]
    pub trait PostRepository: Send + Sync {
        async fn create(&self, post: Post) -> Post;
        async fn delete(&self, id: Uuid) -> bool;
        async fn find_all(&self) -> Vec<Post>;
    }

    // In-memory implementation
    pub struct InMemoryUserRepository {
        users: RwLock<HashMap<Uuid, User>>,
    }

    impl InMemoryUserRepository {
        pub async fn new() -> Self {
            let mut users = HashMap::new();
            let admin_id = Uuid::new_v4();
            let user_id = Uuid::new_v4();
            
            let salt = SaltString::generate(&mut OsRng);
            let admin_hash = Scrypt.hash_password(b"strongpass_admin", &salt).unwrap().to_string();
            let user_hash = Scrypt.hash_password(b"strongpass_user", &salt).unwrap().to_string();

            users.insert(admin_id, User {
                id: admin_id, email: "admin@trait.com".to_string(), password_hash: admin_hash,
                role: domain::Role::ADMIN, is_active: true, created_at: Utc::now(),
            });
            users.insert(user_id, User {
                id: user_id, email: "user@trait.com".to_string(), password_hash: user_hash,
                role: domain::Role::USER, is_active: true, created_at: Utc::now(),
            });
            Self { users: RwLock::new(users) }
        }
    }

    #[async_trait]
    impl UserRepository for InMemoryUserRepository {
        async fn find_by_id(&self, id: Uuid) -> Option<User> {
            self.users.read().await.get(&id).cloned()
        }
        async fn find_by_email(&self, email: &str) -> Option<User> {
            self.users.read().await.values().find(|u| u.email == email).cloned()
        }
    }

    pub struct InMemoryPostRepository {
        posts: RwLock<HashMap<Uuid, Post>>,
    }
    impl InMemoryPostRepository { pub fn new() -> Self { Self { posts: RwLock::new(HashMap::new()) } } }

    #[async_trait]
    impl PostRepository for InMemoryPostRepository {
        async fn create(&self, post: Post) -> Post {
            self.posts.write().await.insert(post.id, post.clone());
            post
        }
        async fn delete(&self, id: Uuid) -> bool {
            self.posts.write().await.remove(&id).is_some()
        }
        async fn find_all(&self) -> Vec<Post> {
            self.posts.read().await.values().cloned().collect()
        }
    }
}

// --- AUTH SERVICE ---
mod auth_provider {
    use super::domain::{Role, User};
    use super::*;

    #[derive(Debug, Serialize, Deserialize)]
    pub struct Claims {
        sub: String,
        role: Role,
        exp: i64,
    }

    #[async_trait]
    pub trait AuthProvider: Send + Sync {
        async fn create_token(&self, user: &User) -> Result<String, String>;
        async fn validate_token(&self, token: &str) -> Result<Claims, String>;
        fn verify_password(&self, password: &str, hash: &str) -> bool;
    }

    pub struct JwtAuthProvider {
        secret: String,
    }

    impl JwtAuthProvider {
        pub fn new(secret: String) -> Self { Self { secret } }
    }

    #[async_trait]
    impl AuthProvider for JwtAuthProvider {
        async fn create_token(&self, user: &User) -> Result<String, String> {
            let claims = Claims {
                sub: user.id.to_string(),
                role: user.role.clone(),
                exp: (Utc::now() + chrono::Duration::hours(24)).timestamp(),
            };
            jsonwebtoken::encode(&jsonwebtoken::Header::default(), &claims, &jsonwebtoken::EncodingKey::from_secret(self.secret.as_ref()))
                .map_err(|e| e.to_string())
        }

        async fn validate_token(&self, token: &str) -> Result<Claims, String> {
            jsonwebtoken::decode::<Claims>(token, &jsonwebtoken::DecodingKey::from_secret(self.secret.as_ref()), &jsonwebtoken::Validation::default())
                .map(|d| d.claims)
                .map_err(|e| e.to_string())
        }

        fn verify_password(&self, password: &str, hash: &str) -> bool {
            PasswordHash::new(hash)
                .and_then(|parsed| Scrypt.verify_password(password.as_bytes(), &parsed))
                .is_ok()
        }
    }
}

// --- WEB LAYER ---
mod web {
    use super::auth_provider::AuthProvider;
    use super::domain::{Post, PostStatus, Role, User};
    use super::repository::{PostRepository, UserRepository};
    use super::*;

    // Guards
    pub struct AuthenticatedUser(pub User);
    pub struct AdminUser(pub User);

    #[rocket::async_trait]
    impl<'r> FromRequest<'r> for AuthenticatedUser {
        type Error = Value;
        async fn from_request(req: &'r Request<'_>) -> Outcome<Self, Self::Error> {
            let auth_provider = req.guard::<&State<Arc<dyn AuthProvider>>>().await.unwrap();
            let user_repo = req.guard::<&State<Arc<dyn UserRepository>>>().await.unwrap();

            let token = match req.headers().get_one("Authorization").and_then(|v| v.strip_prefix("Bearer ")) {
                Some(t) => t,
                None => return Outcome::Failure((Status::Unauthorized, json!({"error": "missing token"}))),
            };

            let claims = match auth_provider.validate_token(token).await {
                Ok(c) => c,
                Err(_) => return Outcome::Failure((Status::Unauthorized, json!({"error": "invalid token"}))),
            };

            let user_id = Uuid::parse_str(&claims.sub).unwrap();
            match user_repo.find_by_id(user_id).await {
                Some(user) if user.is_active => Outcome::Success(AuthenticatedUser(user)),
                _ => Outcome::Failure((Status::Unauthorized, json!({"error": "user not found"}))),
            }
        }
    }

    #[rocket::async_trait]
    impl<'r> FromRequest<'r> for AdminUser {
        type Error = Value;
        async fn from_request(req: &'r Request<'_>) -> Outcome<Self, Self::Error> {
            match AuthenticatedUser::from_request(req).await {
                Outcome::Success(AuthenticatedUser(user)) if user.role == Role::ADMIN => Outcome::Success(AdminUser(user)),
                Outcome::Success(_) => Outcome::Failure((Status::Forbidden, json!({"error": "admin required"}))),
                Outcome::Failure(e) => Outcome::Failure(e),
                Outcome::Forward(f) => Outcome::Forward(f),
            }
        }
    }

    // Routes
    #[derive(Deserialize)]
    pub struct LoginRequest<'r> { email: &'r str, password: &'r str }

    #[post("/login", data = "<req>")]
    pub async fn login(
        auth_provider: &State<Arc<dyn AuthProvider>>,
        user_repo: &State<Arc<dyn UserRepository>>,
        req: Json<LoginRequest<'_>>,
    ) -> Result<Value, (Status, Value)> {
        let user = user_repo.find_by_email(req.email).await
            .ok_or_else(|| (Status::Unauthorized, json!({"error": "bad credentials"})))?;

        if auth_provider.verify_password(req.password, &user.password_hash) {
            let token = auth_provider.create_token(&user).await.unwrap();
            Ok(json!({ "token": token }))
        } else {
            Err((Status::Unauthorized, json!({"error": "bad credentials"})))
        }
    }

    #[get("/me")]
    pub fn get_me(user: AuthenticatedUser) -> Json<User> { Json(user.0) }

    #[derive(Deserialize)]
    pub struct CreatePostRequest { title: String, content: String }

    #[post("/posts", data = "<req>")]
    pub async fn create_post(
        user: AuthenticatedUser,
        post_repo: &State<Arc<dyn PostRepository>>,
        req: Json<CreatePostRequest>,
    ) -> (Status, Json<Post>) {
        let post = Post {
            id: Uuid::new_v4(), user_id: user.0.id, title: req.title.clone(),
            content: req.content.clone(), status: PostStatus::DRAFT,
        };
        let created = post_repo.create(post).await;
        (Status::Created, Json(created))
    }

    #[get("/posts")]
    pub async fn list_posts(_user: AuthenticatedUser, post_repo: &State<Arc<dyn PostRepository>>) -> Json<Vec<Post>> {
        Json(post_repo.find_all().await)
    }

    #[delete("/posts/<id>")]
    pub async fn delete_post(_admin: AdminUser, post_repo: &State<Arc<dyn PostRepository>>, id: Uuid) -> Status {
        if post_repo.delete(id).await { Status::NoContent } else { Status::NotFound }
    }
    
    // OAuth2
    pub struct OAuthConfig { client_id: String, client_secret: String }
    fn get_oauth_client(cfg: &State<OAuthConfig>) -> BasicClient {
        BasicClient::new(
            ClientId::new(cfg.client_id.clone()),
            Some(ClientSecret::new(cfg.client_secret.clone())),
            AuthUrl::new("https://accounts.google.com/o/oauth2/v2/auth".to_string()).unwrap(),
            Some(TokenUrl::new("https://www.googleapis.com/oauth2/v4/token".to_string()).unwrap()),
        ).set_redirect_uri(RedirectUrl::new("http://localhost:8000/auth/google/callback".to_string()).unwrap())
    }

    #[get("/auth/google")]
    pub fn oauth_redirect(cfg: &State<OAuthConfig>, cookies: &CookieJar<'_>) -> Redirect {
        let (url, state) = get_oauth_client(cfg).authorize_url(CsrfToken::new_random).url();
        cookies.add(("oauth_csrf", state.secret().clone()));
        Redirect::to(url)
    }

    #[derive(Deserialize)]
    pub struct CallbackQuery { code: String, state: String }
    #[get("/auth/google/callback?<q>")]
    pub async fn oauth_callback(
        cfg: &State<OAuthConfig>, cookies: &CookieJar<'_>, q: CallbackQuery,
        auth: &State<Arc<dyn AuthProvider>>, users: &State<Arc<dyn UserRepository>>,
    ) -> Result<Value, Flash<Redirect>> {
        if cookies.get("oauth_csrf").map_or(true, |c| c.value() != q.state) {
            return Err(Flash::error(Redirect::to("/"), "CSRF error."));
        }
        cookies.remove("oauth_csrf");
        if get_oauth_client(cfg).exchange_code(oauth2::AuthorizationCode::new(q.code))
            .request_async(oauth2::reqwest::async_http_client).await.is_ok() {
            let user = users.find_by_email("user@trait.com").await.unwrap();
            let token = auth.create_token(&user).await.unwrap();
            Ok(json!({ "message": "OAuth login successful (mocked)", "token": token }))
        } else {
            Err(Flash::error(Redirect::to("/"), "OAuth failed."))
        }
    }
}

#[rocket::main]
async fn main() -> Result<(), rocket::Error> {
    let user_repo: Arc<dyn repository::UserRepository> = Arc::new(repository::InMemoryUserRepository::new().await);
    let post_repo: Arc<dyn repository::PostRepository> = Arc::new(repository::InMemoryPostRepository::new());
    let auth_provider: Arc<dyn auth_provider::AuthProvider> = Arc::new(auth_provider::JwtAuthProvider::new("a_very_secret_key_for_jwt_4".to_string()));
    let oauth_config = web::OAuthConfig {
        client_id: std::env::var("GOOGLE_CLIENT_ID").unwrap_or_else(|_| "test_id".to_string()),
        client_secret: std::env::var("GOOGLE_CLIENT_SECRET").unwrap_or_else(|_| "test_secret".to_string()),
    };

    rocket::build()
        .manage(user_repo)
        .manage(post_repo)
        .manage(auth_provider)
        .manage(oauth_config)
        .mount("/", routes![
            web::login,
            web::get_me,
            web::create_post,
            web::list_posts,
            web::delete_post,
            web::oauth_redirect,
            web::oauth_callback,
        ])
        .launch()
        .await
}