/*
    Variation 2: The "OOP & Service-Oriented" Developer
    - Style: Uses structs to represent services (e.g., AuthService, UserService).
    - Auth: Injects services into Actix app_data and uses Actix Guards for RBAC.
    - Naming: More object-oriented (e.g., AuthService::authenticate, User::new).
    - Structure: Logic is encapsulated within methods on service structs.
*/

// main.rs

// --- Mock Dependencies in Cargo.toml ---
// actix-web = "4"
// actix-session = { version = "0.7", features = ["cookie-session"] }
// serde = { version = "1.0", features = ["derive"] }
// serde_json = "1.0"
// uuid = { version = "1", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }
// jsonwebtoken = "8"
// argon2 = "0.5"
// lazy_static = "1.4"
// rand = "0.8"
// std::sync::Arc

use actix_web::{web, App, HttpServer, Responder, HttpResponse, HttpRequest};
use actix_session::{Session, SessionMiddleware, storage::CookieSessionStore};
use actix_web::cookie::Key;
use rand::Rng;
use std::sync::Arc;

mod domain;
mod services;
mod data_access;
mod api;
mod security;

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let session_key = Key::from(&rand::thread_rng().gen::<[u8; 64]>());

    // Instantiate services and wrap in Arc for thread-safe sharing
    let user_service = Arc::new(services::UserService::new());
    let auth_service = Arc::new(services::AuthService::new(user_service.clone()));

    println!("Starting server at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .wrap(SessionMiddleware::new(CookieSessionStore::default(), session_key.clone()))
            .app_data(web::Data::from(user_service.clone()))
            .app_data(web::Data::from(auth_service.clone()))
            .configure(api::configure_routes)
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}

// domain.rs
mod domain {
    use serde::{Serialize, Deserialize};
    use uuid::Uuid;
    use chrono::{DateTime, Utc};

    #[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq, Hash)]
    pub enum Role { ADMIN, USER }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub enum PostStatus { DRAFT, PUBLISHED }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        #[serde(skip_serializing)]
        pub password_hash: String,
        pub role: Role,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Serialize, Deserialize)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }
}

// data_access.rs
mod data_access {
    use super::domain::{User, Role};
    use uuid::Uuid;
    use std::collections::HashMap;
    use std::sync::Mutex;
    use lazy_static::lazy_static;
    use chrono::Utc;
    use argon2::{self, Config};

    lazy_static! {
        static ref USER_STORE: Mutex<HashMap<String, User>> = {
            let mut m = HashMap::new();
            let salt = b"randomsalt";
            let config = Config::default();
            let admin_pass = argon2::hash_encoded(b"adminpass", salt, &config).unwrap();
            let user_pass = argon2::hash_encoded(b"userpass", salt, &config).unwrap();

            m.insert("admin@example.com".to_string(), User {
                id: Uuid::new_v4(), email: "admin@example.com".to_string(), password_hash: admin_pass,
                role: Role::ADMIN, is_active: true, created_at: Utc::now(),
            });
            m.insert("user@example.com".to_string(), User {
                id: Uuid::new_v4(), email: "user@example.com".to_string(), password_hash: user_pass,
                role: Role::USER, is_active: true, created_at: Utc::now(),
            });
            Mutex::new(m)
        };
    }

    pub struct UserRepo;
    impl UserRepo {
        pub fn find_by_email(&self, email: &str) -> Option<User> {
            USER_STORE.lock().unwrap().get(email).cloned()
        }
    }
}

// services.rs
mod services {
    use super::domain::{User, Role};
    use super::data_access::UserRepo;
    use std::sync::Arc;
    use jsonwebtoken::{encode, Header, EncodingKey};
    use chrono::{Utc, Duration};
    use uuid::Uuid;

    pub const JWT_SECRET: &[u8] = b"supersecretkey";

    #[derive(serde::Serialize, serde::Deserialize)]
    pub struct Claims {
        pub sub: Uuid,
        pub role: Role,
        pub exp: i64,
    }

    pub struct UserService {
        repo: UserRepo,
    }

    impl UserService {
        pub fn new() -> Self {
            UserService { repo: UserRepo }
        }
        pub fn find_by_email(&self, email: &str) -> Option<User> {
            self.repo.find_by_email(email)
        }
    }

    pub struct AuthService {
        user_service: Arc<UserService>,
    }

    impl AuthService {
        pub fn new(user_service: Arc<UserService>) -> Self {
            AuthService { user_service }
        }

        pub fn authenticate(&self, email: &str, password: &str) -> Result<String, &'static str> {
            let user = self.user_service.find_by_email(email).ok_or("Invalid credentials")?;
            
            if !user.is_active {
                return Err("User is not active");
            }

            let is_valid = argon2::verify_encoded(&user.password_hash, password.as_bytes()).unwrap_or(false);
            if !is_valid {
                return Err("Invalid credentials");
            }

            self.generate_jwt(&user)
        }

        fn generate_jwt(&self, user: &User) -> Result<String, &'static str> {
            let exp = Utc::now() + Duration::days(1);
            let claims = Claims {
                sub: user.id,
                role: user.role.clone(),
                exp: exp.timestamp(),
            };
            encode(&Header::default(), &claims, &EncodingKey::from_secret(JWT_SECRET))
                .map_err(|_| "Failed to generate token")
        }
    }
}

// security.rs
mod security {
    use super::domain::Role;
    use super::services::{Claims, JWT_SECRET};
    use actix_web::{guard::{Guard, GuardContext}, HttpMessage};
    use jsonwebtoken::{decode, DecodingKey, Validation, Algorithm};

    pub struct RoleGuard {
        allowed_roles: Vec<Role>,
    }

    impl RoleGuard {
        pub fn new(allowed_roles: Vec<Role>) -> Self {
            Self { allowed_roles }
        }
    }

    impl Guard for RoleGuard {
        fn check(&self, ctx: &GuardContext) -> bool {
            let req = ctx.head();
            let token = match req.headers().get("Authorization")
                .and_then(|h| h.to_str().ok())
                .filter(|s| s.starts_with("Bearer "))
                .map(|s| &s[7..]) 
            {
                Some(token) => token,
                None => return false,
            };

            let validation = Validation::new(Algorithm::HS256);
            if let Ok(token_data) = decode::<Claims>(token, &DecodingKey::from_secret(JWT_SECRET), &validation) {
                if self.allowed_roles.contains(&token_data.claims.role) {
                    // Optionally add claims to request extensions
                    req.extensions_mut().insert(token_data.claims);
                    return true;
                }
            }
            false
        }
    }
}

// api.rs
mod api {
    use super::domain::{Post, PostStatus, Role};
    use super::services::AuthService;
    use super::security::RoleGuard;
    use actix_web::{web, HttpResponse, Responder};
    use serde::Deserialize;
    use uuid::Uuid;
    use actix_session::Session;

    #[derive(Deserialize)]
    struct LoginPayload {
        email: String,
        password: String,
    }

    async fn login(
        payload: web::Json<LoginPayload>,
        auth_service: web::Data<AuthService>,
    ) -> impl Responder {
        match auth_service.authenticate(&payload.email, &payload.password) {
            Ok(token) => HttpResponse::Ok().json(serde_json::json!({ "token": token })),
            Err(e) => HttpResponse::Unauthorized().json(serde_json::json!({ "error": e })),
        }
    }

    async fn oauth_start(session: Session) -> impl Responder {
        let state = Uuid::new_v4().to_string();
        session.insert("oauth_state", &state).unwrap();
        HttpResponse::Ok().json(serde_json::json!({ "message": "Redirect to OAuth provider with this state", "state": state }))
    }

    async fn oauth_end(session: Session) -> impl Responder {
        session.remove("oauth_state");
        HttpResponse::Ok().json(serde_json::json!({ "message": "OAuth flow complete (mocked)" }))
    }

    async fn get_all_posts() -> impl Responder {
        let mock_posts = vec![Post {
            id: Uuid::new_v4(), user_id: Uuid::new_v4(), title: "A Post".to_string(),
            content: "Content here".to_string(), status: PostStatus::PUBLISHED
        }];
        HttpResponse::Ok().json(mock_posts)
    }

    async fn publish_a_post() -> impl Responder {
        HttpResponse::Ok().json(serde_json::json!({ "status": "published" }))
    }

    pub fn configure_routes(cfg: &mut web::ServiceConfig) {
        cfg.service(
            web::scope("/api")
                .route("/login", web::post().to(login))
                .route("/oauth/google", web::get().to(oauth_start))
                .route("/oauth/callback", web::get().to(oauth_end))
                .route("/posts", web::get()
                    .guard(RoleGuard::new(vec![Role::USER, Role::ADMIN]))
                    .to(get_all_posts))
                .route("/admin/posts/publish", web::post()
                    .guard(RoleGuard::new(vec![Role::ADMIN]))
                    .to(publish_a_post))
        );
    }
}