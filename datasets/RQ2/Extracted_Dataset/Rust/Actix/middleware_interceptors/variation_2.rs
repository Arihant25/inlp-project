/*
[dependencies]
actix-web = { version = "4", features = ["macros"] }
actix-cors = "0.6"
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
env_logger = "0.9"
futures-util = "0.3"
std::collections::HashMap
std::sync::{Arc, Mutex}
std::time::{Instant, Duration}
std::net::SocketAddr
*/

use actix_web::{
    dev::Service,
    http,
    middleware::{self, ErrorHandlers},
    web, App, HttpResponse, HttpServer, Responder, Result,
};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

// --- Domain Schema ---

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

// --- Middleware Implementation: Functional Approach ---

// Rate Limiter State
type RateLimitState = Arc<Mutex<HashMap<SocketAddr, (u64, Instant)>>>;

// Error Handling Function
fn handle_error<B>(res: dev::ServiceResponse<B>) -> Result<ErrorHandlerResponse<B>> {
    let (req, res) = res.into_parts();
    let status = res.status();
    let error_response = HttpResponse::build(status)
        .json(serde_json::json!({
            "code": status.as_u16(),
            "error": status.canonical_reason().unwrap_or("Unknown Error"),
            "message": format!("An error occurred processing the request for {}", req.path())
        }));
    
    let new_res = ServiceResponse::new(req, error_response.map_into_left_body());
    Ok(ErrorHandlerResponse::Response(new_res))
}


// --- Mock API Handlers ---

#[get("/users/{user_id}")]
async fn fetch_user(user_id: web::Path<Uuid>) -> impl Responder {
    let mock_user = User {
        id: *user_id,
        email: "functional.dev@example.com".to_string(),
        password_hash: "some_hash".to_string(),
        role: UserRole::ADMIN,
        is_active: true,
        created_at: Utc::now(),
    };
    HttpResponse::Ok().json(mock_user)
}

#[post("/posts")]
async fn submit_post(post_info: web::Json<Post>) -> impl Responder {
    let mut new_post = post_info.into_inner();
    new_post.id = Uuid::new_v4();
    HttpResponse::Created().json(new_post)
}

#[get("/internal_error")]
async fn force_error() -> Result<HttpResponse> {
    // This handler will trigger the error handling middleware
    Err(actix_web::error::ErrorInternalServerError("Simulated internal failure."))
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    std::env::set_var("RUST_LOG", "actix_web=info");
    env_logger::init();

    // State for the rate limiter
    let rate_limiter_data: RateLimitState = Arc::new(Mutex::new(HashMap::new()));

    println!("Functional server starting at http://127.0.0.1:8081");

    HttpServer::new(move || {
        // 2. CORS Handling (built-in)
        let cors_config = actix_cors::Cors::default()
            .allowed_origin("http://localhost:8081")
            .allowed_methods(vec!["GET", "POST"])
            .allow_any_header()
            .max_age(3600);

        // 5. Error Handling (built-in with custom function)
        let error_handlers = ErrorHandlers::new()
            .handler(http::StatusCode::INTERNAL_SERVER_ERROR, handle_error)
            .handler(http::StatusCode::NOT_FOUND, handle_error);

        App::new()
            // Share state with handlers and middleware
            .app_data(web::Data::new(rate_limiter_data.clone()))
            
            // 1. Request Logging (using built-in `Logger` middleware)
            .wrap(middleware::Logger::new(
                r#"%a "%r" %s %b "%{Referer}i" "%{User-Agent}i" %T"#,
            ))
            .wrap(cors_config)
            .wrap(error_handlers)
            
            // 3. Rate Limiting (using `wrap_fn` for a functional style)
            .wrap_fn(|req, srv| {
                let state = req.app_data::<web::Data<RateLimitState>>().unwrap().clone();
                let mut clients = state.lock().unwrap();
                let now = Instant::now();
                
                let res = if let Some(addr) = req.peer_addr() {
                    let (count, start) = clients.entry(addr).or_insert((0, now));
                    if now.duration_since(*start) > Duration::from_secs(60) {
                        *count = 1;
                        *start = now;
                    } else {
                        *count += 1;
                    }

                    if *count > 20 { // Limit: 20 requests per minute
                        Box::pin(async {
                            Ok(req.into_response(HttpResponse::TooManyRequests().finish()))
                        })
                    } else {
                        srv.call(req)
                    }
                } else {
                    srv.call(req)
                };
                
                res
            })
            
            // 4. Request/Response Transformation (using `wrap_fn`)
            .wrap_fn(|req, srv| {
                // Example Request Transformation: Add a custom header to the request
                // Note: This is less common. More often you'd add to extensions.
                // For this example, we'll focus on response transformation.
                let fut = srv.call(req);
                async {
                    let mut res = fut.await?;
                    // Add a custom header to the response
                    res.headers_mut().insert(
                        http::header::HeaderName::from_static("x-processed-by"),
                        http::header::HeaderValue::from_static("functional-middleware"),
                    );
                    Ok(res)
                }
            })
            .service(
                web::scope("/v1")
                    .service(fetch_user)
                    .service(submit_post)
                    .service(force_error)
            )
    })
    .bind("127.0.0.1:8081")?
    .run()
    .await
}