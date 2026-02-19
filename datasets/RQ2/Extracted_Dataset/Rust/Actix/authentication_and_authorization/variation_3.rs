/*
    Variation 3: The "Extractor-Focused" Developer
    - Style: Leverages custom `FromRequest` extractors for auth logic.
    - Auth: Creates `AuthenticatedUser` and `AdminUser` extractors.
    - RBAC: The presence of a specific extractor in the handler signature *is* the authorization check.
    - Structure: Pushes boilerplate into reusable `FromRequest` implementations, making handlers very clean.
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
// futures-util = "0.3"
// rand = "0.8"

use actix_web::{web, App, HttpServer, Responder, HttpResponse};
use actix_session::{Session, SessionMiddleware, storage::CookieSessionStore};
use actix_web::cookie::Key;
use rand::Rng;

mod models;
mod db;
mod extractors;
mod handlers;

pub mod config {
    pub const JWT_SECRET: &[u8] = b"a_very_secure_secret_key_3";
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let session_key = Key::from(&rand::thread_rng().gen::<[u8; 64]>());

    println!("Starting server at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .wrap(SessionMiddleware::new(CookieSessionStore::default(), session_key.clone()))
            .service(
                web::scope("/api")
                    .service(handlers::login)
                    .service(handlers::oauth_google)
                    .service(handlers::oauth_callback)
                    .service(handlers::get_posts)
                    .service(handlers::publish_post)
            )
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}

// models.rs
mod models {
    use serde::{Serialize, Deserialize};
    use uuid::Uuid;
    use chrono::{DateTime, Utc};

    #[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
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

    #[derive(Debug, Serialize, Deserialize)]
    pub struct JwtClaims {
        pub sub: Uuid,
        pub role: Role,
        pub exp: usize,
    }
}

// db.rs
mod db {
    use super::models::{User, Role};
    use uuid::Uuid;
    use std::collections::HashMap;
    use std::sync::Mutex;
    use lazy_static::lazy_static;
    use chrono::Utc;
    use argon2::{self, Config};

    lazy_static! {
        pub static ref MOCK_DB: Mutex<HashMap<String, User>> = {
            let mut db = HashMap::new();
            let salt = b"another-random-salt";
            let config = Config::default();
            let p_hash_admin = argon2::hash_encoded(b"adminpass", salt, &config).unwrap();
            let p_hash_user = argon2::hash_encoded(b"userpass", salt, &config).unwrap();

            db.insert("admin@example.com".into(), User {
                id: Uuid::parse_str("00000000-0000-0000-0000-000000000001").unwrap(),
                email: "admin@example.com".into(), password_hash: p_hash_admin,
                role: Role::ADMIN, is_active: true, created_at: Utc::now(),
            });
            db.insert("user@example.com".into(), User {
                id: Uuid::parse_str("00000000-0000-0000-0000-000000000002").unwrap(),
                email: "user@example.com".into(), password_hash: p_hash_user,
                role: Role::USER, is_active: true, created_at: Utc::now(),
            });
            Mutex::new(db)
        };
    }
}

// extractors.rs
mod extractors {
    use crate::config::JWT_SECRET;
    use crate::models::{JwtClaims, Role};
    use actix_web::{dev::Payload, Error, FromRequest, HttpRequest};
    use futures_util::future::{err, ok, Ready};
    use jsonwebtoken::{decode, DecodingKey, Validation, Algorithm};

    // This struct will be available in handlers that use this extractor
    pub struct AuthenticatedUser {
        pub claims: JwtClaims,
    }

    impl FromRequest for AuthenticatedUser {
        type Error = Error;
        type Future = Ready<Result<Self, Self::Error>>;

        fn from_request(req: &HttpRequest, _: &mut Payload) -> Self::Future {
            let auth_header = match req.headers().get("Authorization") {
                Some(h) => h,
                None => return err(actix_web::error::ErrorUnauthorized("Missing token")),
            };

            let auth_str = match auth_header.to_str() {
                Ok(s) => s,
                Err(_) => return err(actix_web::error::ErrorBadRequest("Invalid header")),
            };

            if !auth_str.starts_with("Bearer ") {
                return err(actix_web::error::ErrorUnauthorized("Invalid token format"));
            }

            let token = &auth_str[7..];
            let decoding_key = DecodingKey::from_secret(JWT_SECRET);
            let validation = Validation::new(Algorithm::HS256);

            match decode::<JwtClaims>(token, &decoding_key, &validation) {
                Ok(token_data) => ok(AuthenticatedUser { claims: token_data.claims }),
                Err(_) => err(actix_web::error::ErrorUnauthorized("Invalid token")),
            }
        }
    }

    // A more specific extractor for Admins
    pub struct AdminUser {
        pub claims: JwtClaims,
    }

    impl FromRequest for AdminUser {
        type Error = Error;
        type Future = Ready<Result<Self, Self::Error>>;

        fn from_request(req: &HttpRequest, payload: &mut Payload) -> Self::Future {
            // First, use the AuthenticatedUser extractor
            match AuthenticatedUser::from_request(req, payload) {
                ok(fut) => {
                    // This is a bit of a trick since from_request returns a Future
                    // In a real scenario with async logic, you'd need to handle the future properly.
                    // For this synchronous case, we can assume it resolves immediately.
                    // A more robust way would involve `Box<dyn Future>`.
                    // But for this synchronous JWT check, it's okay.
                    let authenticated_user = futures_util::future::poll_fn(|cx| fut.poll(cx)).map_err(|_| actix_web::error::ErrorInternalServerError("Future resolution error")).wait().unwrap();
                    
                    if authenticated_user.claims.role == Role::ADMIN {
                        ok(AdminUser { claims: authenticated_user.claims })
                    } else {
                        err(actix_web::error::ErrorForbidden("Requires admin privileges"))
                    }
                },
                err(e) => err(e.into()),
            }
        }
    }
}

// handlers.rs
mod handlers {
    use crate::{config, db, extractors, models::{self, Post, PostStatus}};
    use actix_web::{get, post, web, HttpResponse, Responder};
    use serde::Deserialize;
    use jsonwebtoken::{encode, Header, EncodingKey};
    use chrono::{Utc, Duration};
    use actix_session::Session;
    use uuid::Uuid;

    #[derive(Deserialize)]
    pub struct LoginInfo {
        email: String,
        password: String,
    }

    #[post("/api/login")]
    pub async fn login(info: web::Json<LoginInfo>) -> impl Responder {
        let db_lock = db::MOCK_DB.lock().unwrap();
        let user = match db_lock.get(&info.email) {
            Some(user) => user.clone(),
            None => return HttpResponse::Unauthorized().finish(),
        };

        if !argon2::verify_encoded(&user.password_hash, info.password.as_bytes()).unwrap_or(false) {
            return HttpResponse::Unauthorized().finish();
        }

        let claims = models::JwtClaims {
            sub: user.id,
            role: user.role,
            exp: (Utc::now() + Duration::days(1)).timestamp() as usize,
        };

        let token = encode(&Header::default(), &claims, &EncodingKey::from_secret(config::JWT_SECRET)).unwrap();
        HttpResponse::Ok().json(serde_json::json!({ "token": token }))
    }

    #[get("/api/oauth/google")]
    pub async fn oauth_google(session: Session) -> impl Responder {
        let state = Uuid::new_v4().to_string();
        session.insert("oauth_state", state).unwrap();
        HttpResponse::Ok().body("OAuth flow started (mock). State stored in session.")
    }

    #[get("/api/oauth/callback")]
    pub async fn oauth_callback(session: Session) -> impl Responder {
        if session.get::<String>("oauth_state").unwrap().is_some() {
            session.remove("oauth_state");
            HttpResponse::Ok().body("OAuth callback successful (mock).")
        } else {
            HttpResponse::BadRequest().body("No OAuth state in session.")
        }
    }

    #[get("/api/posts")]
    pub async fn get_posts(user: extractors::AuthenticatedUser) -> impl Responder {
        // Accessing authenticated user's info is now trivial
        println!("User {} requested posts.", user.claims.sub);
        let mock_posts = vec![Post {
            id: Uuid::new_v4(), user_id: user.claims.sub, title: "My Post".to_string(),
            content: "Content...".to_string(), status: PostStatus::DRAFT
        }];
        HttpResponse::Ok().json(mock_posts)
    }

    #[post("/api/admin/posts/publish")]
    pub async fn publish_post(admin: extractors::AdminUser) -> impl Responder {
        // This handler will only be reached if the user is an admin
        HttpResponse::Ok().json(serde_json::json!({
            "message": format!("Admin {} published a post.", admin.claims.sub)
        }))
    }
}