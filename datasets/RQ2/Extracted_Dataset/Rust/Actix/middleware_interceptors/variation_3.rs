/*
[dependencies]
actix-web = "4"
actix-cors = "0.6"
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
futures-util = "0.3"
tracing = "0.1"
tracing-subscriber = "0.3"
tracing-actix-web = "0.7"
thiserror = "1.0"
*/

use actix_web::{web, App, HttpServer};

// --- Project Modules ---

mod models {
    use serde::{Deserialize, Serialize};
    use uuid::Uuid;
    use chrono::{DateTime, Utc};

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub enum UserRole { ADMIN, USER }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        #[serde(skip_serializing)]
        pub password_hash: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub enum PostStatus { DRAFT, PUBLISHED }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }
}

mod errors {
    use actix_web::{HttpResponse, ResponseError};
    use serde_json::json;
    use thiserror::Error;

    #[derive(Error, Debug)]
    pub enum ApiError {
        #[error("Unauthorized: {0}")]
        Unauthorized(String),
        #[error("Not Found: {0}")]
        NotFound(String),
        #[error("Internal Server Error")]
        InternalServerError,
    }

    impl ResponseError for ApiError {
        fn error_response(&self) -> HttpResponse {
            match self {
                ApiError::Unauthorized(msg) => HttpResponse::Unauthorized().json(json!({"error": msg})),
                ApiError::NotFound(msg) => HttpResponse::NotFound().json(json!({"error": msg})),
                ApiError::InternalServerError => HttpResponse::InternalServerError().json(json!({"error": "An unexpected error occurred."})),
            }
        }
    }
}

mod middlewares {
    use super::errors::ApiError;
    use super::models::User;
    use actix_web::{
        dev::{Service, ServiceRequest, ServiceResponse, Transform},
        Error, HttpMessage,
    };
    use futures_util::future::{ok, LocalBoxFuture, Ready};
    use std::task::{Context, Poll};
    use uuid::Uuid;

    // Request Transformation: Authentication Middleware
    // This middleware checks for a token and attaches a User to the request extensions.
    pub struct Authentication;

    impl<S, B> Transform<S, ServiceRequest> for Authentication
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
                service: std::sync::Arc::new(service),
            })
        }
    }

    pub struct AuthMiddleware<S> {
        service: std::sync::Arc<S>,
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

        fn poll_ready(&self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
            self.service.poll_ready(cx)
        }

        fn call(&self, req: ServiceRequest) -> Self::Future {
            let service = self.service.clone();
            Box::pin(async move {
                // Mock token validation
                if let Some(auth_header) = req.headers().get("Authorization") {
                    if let Ok(auth_str) = auth_header.to_str() {
                        if auth_str.starts_with("Bearer ") {
                            let token = &auth_str[7..];
                            if token == "valid-admin-token" {
                                let user = User {
                                    id: Uuid::new_v4(),
                                    email: "admin@modular.com".to_string(),
                                    password_hash: "".to_string(),
                                    role: super::models::UserRole::ADMIN,
                                    is_active: true,
                                    created_at: chrono::Utc::now(),
                                };
                                // Attach user to request extensions
                                req.extensions_mut().insert(user);
                                return service.call(req).await;
                            }
                        }
                    }
                }
                // If token is invalid or missing, return an error
                let err: Error = ApiError::Unauthorized("Invalid or missing authentication token.".to_string()).into();
                Err(err)
            })
        }
    }
}

mod handlers {
    use super::errors::ApiError;
    use super::models::{Post, User};
    use actix_web::{web, HttpMessage, HttpRequest, HttpResponse, Responder};
    use uuid::Uuid;

    // This handler can now extract the User from request extensions
    pub async fn get_current_user(req: HttpRequest) -> Result<impl Responder, ApiError> {
        if let Some(user) = req.extensions().get::<User>() {
            Ok(HttpResponse::Ok().json(user))
        } else {
            // This case should ideally not be reached due to the middleware
            Err(ApiError::InternalServerError)
        }
    }

    pub async fn get_post_by_id(post_id: web::Path<Uuid>) -> Result<impl Responder, ApiError> {
        if *post_id == Uuid::nil() {
            return Err(ApiError::NotFound(format!("Post with ID {} not found.", post_id)));
        }
        let mock_post = Post {
            id: *post_id,
            user_id: Uuid::new_v4(),
            title: "Modular Design Patterns".to_string(),
            content: "Content about modularity...".to_string(),
            status: super::models::PostStatus::PUBLISHED,
        };
        Ok(HttpResponse::Ok().json(mock_post))
    }
}

// --- Main Application Setup ---
use models::*;
use errors::*;
use middlewares::*;
use handlers::*;

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    // Setup structured logging with Tracing
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .init();

    tracing::info!("Modular server starting at http://127.0.0.1:8082");

    HttpServer::new(|| {
        // CORS Handling
        let cors = actix_cors::Cors::default()
            .allow_any_origin()
            .allow_any_method()
            .allow_any_header()
            .max_age(3600);

        App::new()
            // 1. Request Logging (using `tracing-actix-web`)
            .wrap(tracing_actix_web::TracingLogger::default())
            .wrap(cors)
            // Note: Rate limiting is omitted in this variation to focus on modular auth/error handling.
            // A similar struct-based middleware as in Variation 1 could be placed here.
            .service(
                web::scope("/api")
                    // This scope is protected by the Authentication middleware
                    .service(
                        web::scope("/secure")
                            .wrap(Authentication)
                            .route("/me", web::get().to(get_current_user))
                    )
                    // This route is not protected
                    .route("/posts/{post_id}", web::get().to(get_post_by_id))
            )
    })
    .bind("127.0.0.1:8082")?
    .run()
    .await
}