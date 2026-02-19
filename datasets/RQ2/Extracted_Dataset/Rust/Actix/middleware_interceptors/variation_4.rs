/*
[dependencies]
actix-web = "4"
actix-cors = "0.6"
actix-ratelimit = "0.4"
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
thiserror = "1.0"
redis = { version = "0.21", features = ["tokio-comp"] }
*/

use actix_web::{
    dev::Service, web, App, HttpResponse, HttpServer, Responder, ResponseError,
};
use actix_cors::Cors;
use actix_ratelimit::{RateLimiter, RedisStore, RateLimiterError};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use thiserror::Error;
use std::time::Duration;

// --- Domain Models ---

#[derive(Debug, Serialize, Deserialize, Clone)]
enum UserRole { ADMIN, USER }

#[derive(Debug, Serialize, Deserialize, Clone)]
struct User {
    id: Uuid,
    email: String,
    #[serde(skip_serializing)]
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug, Serialize, Deserialize, Clone)]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Custom Error Handling with `thiserror` ---

#[derive(Error, Debug)]
enum AppError {
    #[error("Invalid input provided: {0}")]
    BadClientData(String),
    #[error("Resource not found")]
    NotFound,
    #[error("An internal error occurred. Please try again later.")]
    InternalError,
    #[error("Rate limit exceeded")]
    RateLimitExceeded,
}

impl ResponseError for AppError {
    fn status_code(&self) -> actix_web::http::StatusCode {
        match self {
            AppError::BadClientData(_) => actix_web::http::StatusCode::BAD_REQUEST,
            AppError::NotFound => actix_web::http::StatusCode::NOT_FOUND,
            AppError::InternalError => actix_web::http::StatusCode::INTERNAL_SERVER_ERROR,
            AppError::RateLimitExceeded => actix_web::http::StatusCode::TOO_MANY_REQUESTS,
        }
    }

    fn error_response(&self) -> HttpResponse {
        HttpResponse::build(self.status_code()).json(serde_json::json!({
            "status": "error",
            "message": self.to_string(),
        }))
    }
}

// Convert RateLimiterError to our AppError
impl From<RateLimiterError> for AppError {
    fn from(_: RateLimiterError) -> Self {
        AppError::RateLimitExceeded
    }
}

// --- API Handlers returning `Result<_, AppError>` ---

async fn get_user_by_id(user_id: web::Path<Uuid>) -> Result<impl Responder, AppError> {
    if user_id.is_nil() {
        return Err(AppError::BadClientData("User ID cannot be nil".to_string()));
    }
    
    let mock_user = User {
        id: *user_id,
        email: "minimal.dev@example.com".to_string(),
        password_hash: "secret".to_string(),
        role: UserRole::USER,
        is_active: false,
        created_at: Utc::now(),
    };
    Ok(HttpResponse::Ok().json(mock_user))
}

async fn create_new_post(post_data: web::Json<Post>) -> Result<impl Responder, AppError> {
    let title = &post_data.title;
    if title.trim().is_empty() || title.len() > 100 {
        return Err(AppError::BadClientData("Title is invalid".to_string()));
    }
    
    let mut new_post = post_data.into_inner();
    new_post.id = Uuid::new_v4();
    Ok(HttpResponse::Created().json(new_post))
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    // This example requires a running Redis instance on localhost.
    // You can start one with: docker run -p 6379:6379 redis
    let redis_conn_str = "redis://127.0.0.1/";
    let redis_store = match RedisStore::connect(redis_conn_str).await {
        Ok(store) => store,
        Err(e) => {
            eprintln!("Could not connect to Redis: {}. Please ensure Redis is running.", e);
            eprintln!("To run this example, you can use: docker run -p 6379:6379 redis");
            std::process::exit(1);
        }
    };

    println!("Minimalist server starting at http://127.0.0.1:8083");

    HttpServer::new(move || {
        // 2. CORS Handling
        let cors_mw = Cors::default()
            .allowed_origin("https://example.com")
            .allowed_methods(vec!["GET", "POST"])
            .supports_credentials();

        // 3. Rate Limiting (using `actix-ratelimit` crate)
        let ratelimit_mw = RateLimiter::new(
            redis_store.clone()
        )
        .with_interval(Duration::from_secs(60))
        .with_max_requests(100)
        .with_identifier(|req| {
            // Rate limit by IP address
            Ok(req.connection_info().realip_remote_addr().unwrap_or("127.0.0.1").to_string())
        })
        .with_error_handler(|err| {
            // Convert the crate's error into our custom AppError
            let app_err: AppError = err.into();
            actix_web::error::InternalError::from_response(app_err, app_err.error_response())
        });

        App::new()
            // 1. Request Logging (using built-in `Logger`)
            .wrap(actix_web::middleware::Logger::default())
            .wrap(cors_mw)
            .wrap(ratelimit_mw)
            
            // 4. Response Transformation (using `wrap_fn`)
            .wrap_fn(|req, srv| {
                let fut = srv.call(req);
                async {
                    let mut res = fut.await?;
                    res.headers_mut().insert(
                        actix_web::http::header::HeaderName::from_static("x-server-id"),
                        actix_web::http::header::HeaderValue::from_static("srv-minimal-01"),
                    );
                    Ok(res)
                }
            })
            // 5. Error Handling is implicitly handled by `AppError` implementing `ResponseError`.
            // Actix-web automatically calls `error_response()` on `Err` variants.
            .service(
                web::scope("/api")
                    .route("/users/{user_id}", web::get().to(get_user_by_id))
                    .route("/posts", web::post().to(create_new_post))
            )
    })
    .bind("127.0.0.1:8083")?
    .run()
    .await
}