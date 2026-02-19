/*
[dependencies]
actix-web = "4"
actix-cors = "0.6"
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
futures-util = "0.3"
std::pin::Pin
std::future::{Ready, ready}
std::collections::HashMap
std::sync::{Arc, Mutex}
std::time::{Instant, Duration}
std::net::SocketAddr
*/

use actix_web::{
    dev::{self, Service, ServiceRequest, ServiceResponse, Transform},
    web, App, Error, HttpResponse, HttpServer, Responder,
};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::future::{ready, Ready};
use std::pin::Pin;
use std::task::{Context, Poll};
use futures_util::future::LocalBoxFuture;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Instant, Duration};
use std::net::SocketAddr;

// --- Domain Models ---

#[derive(Debug, Serialize, Deserialize, Clone)]
enum UserRole {
    ADMIN,
    USER,
}

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
enum PostStatus {
    DRAFT,
    PUBLISHED,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Middleware Implementation: Classic Struct-based Approach ---

// 1. Request Logging Middleware
pub struct RequestLogger;

impl<S, B> Transform<S, ServiceRequest> for RequestLogger
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error>,
    S::Future: 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = Error;
    type InitError = ();
    type Transform = RequestLoggerMiddleware<S>;
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        ready(Ok(RequestLoggerMiddleware { service }))
    }
}

pub struct RequestLoggerMiddleware<S> {
    service: S,
}

impl<S, B> Service<ServiceRequest> for RequestLoggerMiddleware<S>
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error>,
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
        let peer_addr = req.peer_addr().map(|addr| addr.to_string()).unwrap_or_else(|| "unknown".to_string());
        println!(
            "Request: {} {} from {}",
            req.method(),
            req.uri(),
            peer_addr
        );
        let fut = self.service.call(req);
        Box::pin(async move {
            let res = fut.await?;
            println!(
                "Response: {} for {} {}",
                res.status(),
                res.request().method(),
                res.request().uri()
            );
            Ok(res)
        })
    }
}

// 2. Rate Limiting Middleware
#[derive(Clone)]
pub struct RateLimiter {
    clients: Arc<Mutex<HashMap<SocketAddr, (u32, Instant)>>>,
    limit: u32,
    period: Duration,
}

impl RateLimiter {
    pub fn new(limit: u32, period: Duration) -> Self {
        RateLimiter {
            clients: Arc::new(Mutex::new(HashMap::new())),
            limit,
            period,
        }
    }
}

impl<S, B> Transform<S, ServiceRequest> for RateLimiter
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + 'static,
    S::Future: 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = Error;
    type InitError = ();
    type Transform = RateLimiterMiddleware<S>;
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        ready(Ok(RateLimiterMiddleware {
            service: Arc::new(service),
            clients: self.clients.clone(),
            limit: self.limit,
            period: self.period,
        }))
    }
}

pub struct RateLimiterMiddleware<S> {
    service: Arc<S>,
    clients: Arc<Mutex<HashMap<SocketAddr, (u32, Instant)>>>,
    limit: u32,
    period: Duration,
}

impl<S, B> Service<ServiceRequest> for RateLimiterMiddleware<S>
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
        let clients = self.clients.clone();
        let limit = self.limit;
        let period = self.period;

        Box::pin(async move {
            if let Some(addr) = req.peer_addr() {
                let mut clients_guard = clients.lock().unwrap();
                let now = Instant::now();
                
                let (count, start_time) = clients_guard.entry(addr).or_insert((0, now));

                if now.duration_since(*start_time) > period {
                    *count = 1;
                    *start_time = now;
                } else {
                    *count += 1;
                }

                if *count > limit {
                    return Ok(req.into_response(HttpResponse::TooManyRequests().finish()));
                }
            }
            service.call(req).await
        })
    }
}

// 3. Response Transformation Middleware
pub struct ResponseTransformer;

impl<S, B> Transform<S, ServiceRequest> for ResponseTransformer
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error>,
    S::Future: 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = Error;
    type InitError = ();
    type Transform = ResponseTransformerMiddleware<S>;
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        ready(Ok(ResponseTransformerMiddleware { service }))
    }
}

pub struct ResponseTransformerMiddleware<S> {
    service: S,
}

impl<S, B> Service<ServiceRequest> for ResponseTransformerMiddleware<S>
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error>,
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
        let fut = self.service.call(req);
        Box::pin(async move {
            let mut res = fut.await?;
            res.headers_mut().insert(
                actix_web::http::header::HeaderName::from_static("x-api-version"),
                actix_web::http::header::HeaderValue::from_static("1.0"),
            );
            Ok(res)
        })
    }
}

// 4. Error Handling Middleware
pub struct ErrorHandler;

impl<S, B> Transform<S, ServiceRequest> for ErrorHandler
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error>,
    S::Future: 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = Error;
    type InitError = ();
    type Transform = ErrorHandlerMiddleware<S>;
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        ready(Ok(ErrorHandlerMiddleware { service }))
    }
}

pub struct ErrorHandlerMiddleware<S> {
    service: S,
}

impl<S, B> Service<ServiceRequest> for ErrorHandlerMiddleware<S>
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error>,
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
        let fut = self.service.call(req);
        Box::pin(async move {
            let res = fut.await;
            match res {
                Ok(res) => Ok(res),
                Err(err) => {
                    let error_response = HttpResponse::InternalServerError()
                        .json(serde_json::json!({
                            "status": "error",
                            "message": format!("An internal error occurred: {}", err)
                        }));
                    // We need to convert HttpResponse into ServiceResponse
                    Ok(ServiceResponse::new(req.into_parts().0, error_response.map_into_boxed_body()))
                }
            }
        })
    }
}


// --- Mock Handlers ---

async fn get_user(user_id: web::Path<Uuid>) -> impl Responder {
    let mock_user = User {
        id: *user_id,
        email: "test@example.com".to_string(),
        password_hash: "hashed_password".to_string(),
        role: UserRole::USER,
        is_active: true,
        created_at: Utc::now(),
    };
    HttpResponse::Ok().json(mock_user)
}

async fn create_post(post_data: web::Json<Post>) -> impl Responder {
    let mut new_post = post_data.into_inner();
    new_post.id = Uuid::new_v4();
    HttpResponse::Created().json(new_post)
}

async fn trigger_error() -> Result<HttpResponse, Error> {
    // This handler will simulate an error
    Err(actix_web::error::ErrorInternalServerError("This is a simulated error!"))
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    println!("Starting server at http://127.0.0.1:8080");

    HttpServer::new(|| {
        // 5. CORS Handling (using built-in middleware)
        let cors = actix_cors::Cors::default()
            .allowed_origin("http://127.0.0.1:8080")
            .allowed_methods(vec!["GET", "POST"])
            .allowed_headers(vec![actix_web::http::header::AUTHORIZATION, actix_web::http::header::ACCEPT])
            .allowed_header(actix_web::http::header::CONTENT_TYPE)
            .max_age(3600);

        App::new()
            // Middleware registration order matters. Outer -> Inner.
            .wrap(ErrorHandler)
            .wrap(cors)
            .wrap(ResponseTransformer)
            .wrap(RateLimiter::new(10, Duration::from_secs(60))) // 10 requests per minute
            .wrap(RequestLogger)
            .service(
                web::scope("/api")
                    .route("/users/{user_id}", web::get().to(get_user))
                    .route("/posts", web::post().to(create_post))
                    .route("/error", web::get().to(trigger_error))
            )
    })
    .bind("127.0.0.1:8080")?
    .run()
    .await
}