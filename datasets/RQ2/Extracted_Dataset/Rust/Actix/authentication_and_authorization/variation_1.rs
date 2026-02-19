/*
    Variation 1: The "Functional & Middleware-Heavy" Developer
    - Style: Organizes code into modules (handlers, auth, models).
    - Auth: Implements RBAC using a custom middleware struct that implements the `Transform` trait.
    - Naming: Clear, descriptive names (e.g., login_handler, admin_only_route).
    - Structure: Prefers free functions for handlers and a dedicated middleware for auth logic.
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

use actix_web::{web, App, HttpServer, Responder, HttpResponse, HttpRequest};
use actix_session::{Session, SessionMiddleware, storage::CookieSessionStore};
use actix_web::cookie::Key;
use rand::Rng;

mod models;
mod db;
mod handlers;
mod auth;

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    // In a real app, load this from a secure config
    let session_key = Key::from(&rand::thread_rng().gen::<[u8; 64]>());

    println!("Starting server at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .wrap(SessionMiddleware::new(CookieSessionStore::default(), session_key.clone()))
            .service(
                web::scope("/api")
                    .route("/login", web::post().to(handlers::auth_handlers::login))
                    .route("/oauth/google", web::get().to(handlers::auth_handlers::oauth_google_login))
                    .route("/oauth/callback", web::get().to(handlers::auth_handlers::oauth_callback))
                    .service(
                        web::scope("/posts")
                            .wrap(auth::AuthMiddleware::new(vec![models::Role::USER, models::Role::ADMIN]))
                            .route("", web::get().to(handlers::post_handlers::get_posts))
                    )
                    .service(
                        web::scope("/admin")
                            .wrap(auth::AuthMiddleware::new(vec![models::Role::ADMIN]))
                            .route("/posts/publish", web::post().to(handlers::post_handlers::publish_post))
                    )
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
    pub enum Role {
        ADMIN,
        USER,
    }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub enum PostStatus {
        DRAFT,
        PUBLISHED,
    }

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
        pub static ref USER_DB: Mutex<HashMap<String, User>> = {
            let mut m = HashMap::new();
            let salt = b"randomsalt";
            let config = Config::default();
            let admin_password_hash = argon2::hash_encoded(b"adminpass", salt, &config).unwrap();
            let user_password_hash = argon2::hash_encoded(b"userpass", salt, &config).unwrap();

            let admin_user = User {
                id: Uuid::new_v4(),
                email: "admin@example.com".to_string(),
                password_hash: admin_password_hash,
                role: Role::ADMIN,
                is_active: true,
                created_at: Utc::now(),
            };
            let normal_user = User {
                id: Uuid::new_v4(),
                email: "user@example.com".to_string(),
                password_hash: user_password_hash,
                role: Role::USER,
                is_active: true,
                created_at: Utc::now(),
            };
            m.insert(admin_user.email.clone(), admin_user);
            m.insert(normal_user.email.clone(), normal_user);
            Mutex::new(m)
        };
    }

    pub fn find_user_by_email(email: &str) -> Option<User> {
        let db = USER_DB.lock().unwrap();
        db.get(email).cloned()
    }
}

// auth.rs
mod auth {
    use super::models::{Role, User};
    use actix_web::{
        dev::{Service, ServiceRequest, ServiceResponse, Transform},
        Error, HttpMessage,
    };
    use futures_util::future::{ok, Ready, LocalBoxFuture};
    use jsonwebtoken::{decode, DecodingKey, Validation, Algorithm};
    use serde::{Serialize, Deserialize};
    use std::rc::Rc;
    use uuid::Uuid;

    pub const JWT_SECRET: &[u8] = b"supersecretkey";

    #[derive(Debug, Serialize, Deserialize)]
    pub struct Claims {
        pub sub: Uuid,
        pub role: Role,
        pub exp: usize,
    }

    pub struct AuthMiddleware<S> {
        service: Rc<S>,
        required_roles: Vec<Role>,
    }

    impl<S, B> Service<ServiceRequest> for AuthMiddleware<S>
    where
        S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + 'static,
        S::Future: 'static,
        B: 'static,
    {
        type Response = ServiceResponse<B>;
        type Error = Error;
        type Future = LocalBoxFuture<'static, Result<Self::Response, Self::Error>>;

        actix_web::dev::forward_ready!(service);

        fn call(&self, req: ServiceRequest) -> Self::Future {
            let roles = self.required_roles.clone();
            let srv = self.service.clone();

            Box::pin(async move {
                let auth_header = req.headers().get("Authorization");
                if auth_header.is_none() {
                    return Err(actix_web::error::ErrorUnauthorized("No token provided"));
                }

                let auth_str = auth_header.unwrap().to_str().unwrap_or("");
                if !auth_str.starts_with("Bearer ") {
                    return Err(actix_web::error::ErrorUnauthorized("Invalid token format"));
                }

                let token = &auth_str[7..];
                let token_data = decode::<Claims>(
                    token,
                    &DecodingKey::from_secret(JWT_SECRET),
                    &Validation::new(Algorithm::HS256),
                );

                match token_data {
                    Ok(data) => {
                        if roles.contains(&data.claims.role) {
                            // You can add user info to request extensions if needed
                            // let user_info = UserInfo { id: data.claims.sub, role: data.claims.role };
                            // req.extensions_mut().insert(user_info);
                            let fut = srv.call(req);
                            fut.await
                        } else {
                            Err(actix_web::error::ErrorForbidden("Insufficient permissions"))
                        }
                    }
                    Err(_) => Err(actix_web::error::ErrorUnauthorized("Invalid token")),
                }
            })
        }
    }

    pub struct AuthMiddlewareFactory {
        required_roles: Vec<Role>,
    }

    impl AuthMiddlewareFactory {
        pub fn new(required_roles: Vec<Role>) -> Self {
            AuthMiddlewareFactory { required_roles }
        }
    }

    impl<S, B> Transform<S, ServiceRequest> for AuthMiddlewareFactory
    where
        S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + 'static,
        S::Future: 'static,
        B: 'static,
    {
        type Response = ServiceResponse<B>;
        type Error = Error;
        type InitError = ();
        type Transform = AuthMiddleware<S>;
        type Future = Ready<Result<Self::Transform, Self::InitError>>;

        fn new_transform(&self, service: S) -> Self::Future {
            ok(AuthMiddleware {
                service: Rc::new(service),
                required_roles: self.required_roles.clone(),
            })
        }
    }
    
    // Alias for cleaner use in main.rs
    pub use AuthMiddlewareFactory as AuthMiddleware;
}

// handlers.rs
mod handlers {
    pub mod auth_handlers {
        use crate::{auth, db, models};
        use actix_web::{web, HttpResponse, Responder, Result};
        use serde::Deserialize;
        use jsonwebtoken::{encode, Header, EncodingKey};
        use chrono::{Utc, Duration};
        use actix_session::Session;
        use uuid::Uuid;

        #[derive(Deserialize)]
        pub struct LoginRequest {
            email: String,
            password: String,
        }

        pub async fn login(req: web::Json<LoginRequest>) -> Result<HttpResponse> {
            let user = db::find_user_by_email(&req.email);
            if user.is_none() {
                return Ok(HttpResponse::Unauthorized().json("Invalid credentials"));
            }

            let user = user.unwrap();
            let is_valid = argon2::verify_encoded(&user.password_hash, req.password.as_bytes()).unwrap_or(false);

            if !is_valid || !user.is_active {
                return Ok(HttpResponse::Unauthorized().json("Invalid credentials"));
            }

            let expiration = Utc::now()
                .checked_add_signed(Duration::hours(24))
                .expect("valid timestamp")
                .timestamp();

            let claims = auth::Claims {
                sub: user.id,
                role: user.role,
                exp: expiration as usize,
            };

            let token = encode(&Header::default(), &claims, &EncodingKey::from_secret(auth::JWT_SECRET))
                .map_err(|_| actix_web::error::ErrorInternalServerError("Token generation failed"))?;

            Ok(HttpResponse::Ok().json(serde_json::json!({ "token": token })))
        }

        pub async fn oauth_google_login(session: Session) -> impl Responder {
            // In a real app, you'd generate a state and redirect to Google's OAuth2 endpoint
            let state = Uuid::new_v4().to_string();
            session.insert("oauth_state", &state).unwrap();
            let redirect_url = format!("https://accounts.google.com/o/oauth2/v2/auth?client_id=YOUR_CLIENT_ID&redirect_uri=http://127.0.0.1:8080/api/oauth/callback&response_type=code&scope=openid%20email&state={}", state);
            HttpResponse::Found()
                .append_header(("Location", redirect_url))
                .finish()
        }

        pub async fn oauth_callback(session: Session, _req: web::Query<serde_json::Value>) -> impl Responder {
            // Here you would verify the 'state' from the query against the session
            // Then exchange the 'code' for an access token from Google
            // Then fetch user info and create/login the user in your system
            let stored_state = session.get::<String>("oauth_state").unwrap_or(None);
            if stored_state.is_none() {
                return HttpResponse::BadRequest().body("OAuth state missing from session.");
            }
            // Mocking a successful login
            HttpResponse::Ok().body("OAuth login successful (mocked). You would now get a JWT.")
        }
    }

    pub mod post_handlers {
        use crate::models::{Post, PostStatus};
        use actix_web::{web, HttpResponse, Responder};
        use serde::Deserialize;
        use uuid::Uuid;

        #[derive(Deserialize)]
        pub struct PublishPostRequest {
            post_id: Uuid,
        }

        pub async fn get_posts() -> impl Responder {
            // Mock response
            let posts = vec![
                Post {
                    id: Uuid::new_v4(),
                    user_id: Uuid::new_v4(),
                    title: "First Post".to_string(),
                    content: "This is a post.".to_string(),
                    status: PostStatus::PUBLISHED,
                }
            ];
            HttpResponse::Ok().json(posts)
        }

        pub async fn publish_post(_req: web::Json<PublishPostRequest>) -> impl Responder {
            // In a real app, find the post by ID and update its status
            HttpResponse::Ok().json(serde_json::json!({ "message": "Post published successfully" }))
        }
    }
}