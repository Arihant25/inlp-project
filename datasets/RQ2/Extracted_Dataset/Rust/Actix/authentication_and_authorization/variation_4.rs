/*
    Variation 4: The "Minimalist & Session-Based" Developer
    - Style: A more direct, procedural style within handlers. Less modular.
    - Auth: Leans heavily on `actix-session` for web UI auth, storing user ID and role.
    - JWT: Still generates JWTs for API clients, but web routes are session-first.
    - Structure: Most logic is in a single file, suitable for a small microservice.
    - Naming: More terse variable names (e.g., usr, req, claims).
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

use actix_web::{web, App, HttpServer, Responder, HttpResponse, Error};
use actix_session::{Session, SessionMiddleware, storage::CookieSessionStore};
use actix_web::cookie::Key;
use rand::Rng;
use serde::{Serialize, Deserialize};
use uuid::Uuid;
use chrono::{DateTime, Utc, Duration};
use std::collections::HashMap;
use std::sync::Mutex;
use lazy_static::lazy_static;
use jsonwebtoken::{encode, decode, Header, EncodingKey, DecodingKey, Validation, Algorithm};

// --- 1. DOMAIN MODELS ---
#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq, Copy)]
pub enum Role { ADMIN, USER }

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct User {
    id: Uuid,
    email: String,
    #[serde(skip_serializing)]
    password_hash: String,
    role: Role,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct JwtClaims {
    sub: Uuid,
    role: Role,
    exp: i64,
}

// --- 2. MOCK DATABASE ---
lazy_static! {
    static ref DB_USERS: Mutex<HashMap<String, User>> = {
        let mut db = HashMap::new();
        let salt = b"somesalt4";
        let config = argon2::Config::default();
        let admin_hash = argon2::hash_encoded(b"adminpass", salt, &config).unwrap();
        let user_hash = argon2::hash_encoded(b"userpass", salt, &config).unwrap();

        db.insert("admin@example.com".into(), User {
            id: Uuid::new_v4(), email: "admin@example.com".into(), password_hash: admin_hash,
            role: Role::ADMIN, is_active: true, created_at: Utc::now(),
        });
        db.insert("user@example.com".into(), User {
            id: Uuid::new_v4(), email: "user@example.com".into(), password_hash: user_hash,
            role: Role::USER, is_active: true, created_at: Utc::now(),
        });
        Mutex::new(db)
    };
}

// --- 3. CONFIG & HELPERS ---
const JWT_SECRET: &[u8] = b"minimalist_secret";

fn create_jwt(usr: &User) -> Result<String, jsonwebtoken::errors::Error> {
    let exp = (Utc::now() + Duration::days(1)).timestamp();
    let claims = JwtClaims { sub: usr.id, role: usr.role, exp };
    encode(&Header::default(), &claims, &EncodingKey::from_secret(JWT_SECRET))
}

// --- 4. HANDLERS ---
#[derive(Deserialize)]
struct LoginData {
    email: String,
    password: String,
}

async fn login(data: web::Json<LoginData>, session: Session) -> impl Responder {
    let db = DB_USERS.lock().unwrap();
    let usr_opt = db.get(&data.email);

    if let Some(usr) = usr_opt {
        if usr.is_active && argon2::verify_encoded(&usr.password_hash, data.password.as_bytes()).unwrap_or(false) {
            // Set session for web clients
            session.insert("user_id", usr.id).unwrap();
            session.insert("user_role", usr.role).unwrap();
            
            // Generate JWT for API clients
            let token = create_jwt(usr).unwrap_or_default();

            return HttpResponse::Ok().json(serde_json::json!({
                "message": "Login successful",
                "token": token
            }));
        }
    }
    HttpResponse::Unauthorized().json("Invalid credentials")
}

async fn logout(session: Session) -> impl Responder {
    session.purge();
    HttpResponse::Ok().body("Logged out")
}

async fn oauth_redirect(session: Session) -> impl Responder {
    session.insert("oauth_state", "random_state_string").unwrap();
    HttpResponse::Found()
        .append_header(("Location", "/api/oauth/callback?code=mock_code&state=random_state_string"))
        .finish()
}

async fn oauth_callback(session: Session) -> impl Responder {
    if session.get::<String>("oauth_state").unwrap().is_some() {
        // Here you'd exchange the code, get user info, and log them in
        // For this mock, we'll just grant a user session
        session.insert("user_id", Uuid::new_v4()).unwrap();
        session.insert("user_role", Role::USER).unwrap();
        HttpResponse::Ok().body("OAuth login successful (mocked)")
    } else {
        HttpResponse::BadRequest().body("Invalid OAuth state")
    }
}

async fn get_user_posts(session: Session) -> Result<HttpResponse, Error> {
    // This handler is session-based. It checks the session directly.
    let user_id: Uuid = session.get("user_id")?
        .ok_or_else(|| actix_web::error::ErrorUnauthorized("Not logged in"))?;
    
    // The role check is also done inside the handler
    let role: Role = session.get("user_role")?
        .ok_or_else(|| actix_web::error::ErrorUnauthorized("Not logged in"))?;

    if role != Role::USER && role != Role::ADMIN {
        return Err(actix_web::error::ErrorForbidden("Insufficient permissions"));
    }

    let mock_posts = vec![Post {
        id: Uuid::new_v4(), user_id, title: "My Session Post".to_string(),
        content: "Content".to_string(), status: PostStatus::DRAFT
    }];

    Ok(HttpResponse::Ok().json(mock_posts))
}

async fn publish_any_post(session: Session) -> Result<HttpResponse, Error> {
    // Admin-only check directly in the handler
    match session.get::<Role>("user_role")? {
        Some(Role::ADMIN) => {
            Ok(HttpResponse::Ok().json(serde_json::json!({"status": "post published by admin"})))
        },
        _ => Err(actix_web::error::ErrorForbidden("Admins only")),
    }
}

// --- 5. MAIN SERVER SETUP ---
#[actix_web::main]
async fn main() -> std::io::Result<()> {
    // Use a static key for simplicity in this example, but generate it for production
    let session_key = Key::from(&[0; 64]);

    println!("Starting server at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .wrap(SessionMiddleware::new(CookieSessionStore::default(), session_key.clone()))
            .service(
                web::scope("/api")
                    .route("/login", web::post().to(login))
                    .route("/logout", web::post().to(logout))
                    .route("/oauth/google", web::get().to(oauth_redirect))
                    .route("/oauth/callback", web::get().to(oauth_callback))
                    .route("/posts", web::get().to(get_user_posts))
                    .route("/admin/publish", web::post().to(publish_any_post))
            )
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}