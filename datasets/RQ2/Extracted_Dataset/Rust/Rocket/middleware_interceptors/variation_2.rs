#[macro_use]
extern crate rocket;

use async_trait::async_trait;
use dashmap::DashMap;
use rocket::fairing::{Fairing, Info, Kind};
use rocket::http::{Header, Method, Status};
use rocket::serde::json::{json, Json, Value};
use rocket::{Request, Response, State};
use serde::Serialize;
use std::net::IpAddr;
use std::sync::Arc;
use std::time::{Duration, Instant};
use uuid::Uuid;

// --- Domain Schema ---

#[derive(Serialize, Debug)]
#[serde(rename_all = "PascalCase")]
enum UserRole {
    Admin,
    User,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "PascalCase")]
struct User {
    Id: Uuid,
    Email: String,
    #[serde(skip_serializing)]
    PasswordHash: String,
    Role: UserRole,
    IsActive: bool,
    CreatedAt: u64,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "PascalCase")]
enum PostStatus {
    Draft,
    Published,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "PascalCase")]
struct Post {
    Id: Uuid,
    UserId: Uuid,
    Title: String,
    Content: String,
    Status: PostStatus,
}

// --- Middleware Implementation (Modular Struct-based Style) ---

// Module 1: Request Logging
pub struct RequestLogger;

#[async_trait]
impl Fairing for RequestLogger {
    fn info(&self) -> Info {
        Info {
            name: "Request Logger",
            kind: Kind::Request | Kind::Response,
        }
    }

    async fn on_request(&self, request: &mut Request<'_>, _: &mut rocket::Data<'_>) {
        request.local_cache(|| Instant::now());
        info_!("Request [{}]: {} {}", request.id(), request.method(), request.uri());
    }

    async fn on_response<'r>(&self, request: &'r Request<'_>, response: &mut Response<'r>) {
        let start_time = request.local_cache(|| Instant::now());
        let elapsed = start_time.elapsed();
        info_!(
            "Response [{}]: {} {} -> {} ({:.2?})",
            request.id(),
            request.method(),
            request.uri(),
            response.status(),
            elapsed
        );
    }
}

// Module 2: CORS Handling
pub struct CorsHandler;

#[async_trait]
impl Fairing for CorsHandler {
    fn info(&self) -> Info {
        Info {
            name: "CORS Header Attacher",
            kind: Kind::Response,
        }
    }

    async fn on_response<'r>(&self, request: &'r Request<'_>, response: &mut Response<'r>) {
        response.set_header(Header::new("Access-Control-Allow-Origin", "*"));
        response.set_header(Header::new("Access-Control-Allow-Methods", "GET, POST, OPTIONS"));
        response.set_header(Header::new("Access-Control-Allow-Headers", "Content-Type"));
        if request.method() == Method::Options {
            response.set_status(Status::Ok);
        }
    }
}

// Module 3: Rate Limiting
struct RateLimitInfo {
    count: u32,
    start: Instant,
}
struct RateLimiterState(Arc<DashMap<IpAddr, RateLimitInfo>>);

pub struct RateLimiter;

#[async_trait]
impl Fairing for RateLimiter {
    fn info(&self) -> Info {
        Info {
            name: "IP-based Rate Limiter",
            kind: Kind::Request,
        }
    }

    async fn on_request(&self, request: &mut Request<'_>, _: &mut rocket::Data<'_>) {
        let client_ip = match request.client_ip() {
            Some(ip) => ip,
            None => return,
        };

        let state = request.rocket().state::<RateLimiterState>().unwrap();
        let mut entry = state.0.entry(client_ip).or_insert(RateLimitInfo {
            count: 0,
            start: Instant::now(),
        });

        if entry.start.elapsed() > Duration::from_secs(60) {
            entry.count = 1;
            entry.start = Instant::now();
        } else {
            entry.count += 1;
        }

        if entry.count > 20 { // 20 requests per minute
            request.set_outcome(rocket::outcome::Outcome::Error(Status::TooManyRequests));
        }
    }
}

// Module 4: Response Transformation
pub struct ApiResponseTransformer;

#[async_trait]
impl Fairing for ApiResponseTransformer {
    fn info(&self) -> Info {
        Info {
            name: "API Response Transformer",
            kind: Kind::Response,
        }
    }

    async fn on_response<'r>(&self, request: &'r Request<'_>, response: &mut Response<'r>) {
        response.set_header(Header::new("X-API-Version", "1.0"));
        if let Some(start_time) = request.local_cache_get::<Instant>() {
            let elapsed_ms = start_time.elapsed().as_millis();
            response.set_header(Header::new("X-Processing-Time-ms", elapsed_ms.to_string()));
        }
    }
}

// --- Error Handling ---
#[catch(default)]
fn default_catcher(status: Status, _req: &Request) -> Value {
    json!({
        "error": {
            "code": status.code,
            "message": status.reason().unwrap_or("An unknown error occurred.")
        }
    })
}

// --- Mock API Routes ---
#[get("/users/<id>")]
fn get_user_by_id(id: Uuid) -> Json<User> {
    Json(User {
        Id: id,
        Email: "developer2@example.com".to_string(),
        PasswordHash: "secret_hash".to_string(),
        Role: UserRole::User,
        IsActive: true,
        CreatedAt: 1672531200,
    })
}

#[get("/posts/<id>")]
fn get_post_by_id(id: Uuid) -> Json<Post> {
    Json(Post {
        Id: id,
        UserId: Uuid::new_v4(),
        Title: "Modular Middleware".to_string(),
        Content: "Separating concerns in Rocket Fairings.".to_string(),
        Status: PostStatus::Published,
    })
}

#[launch]
fn rocket() -> _ {
    rocket::build()
        .manage(RateLimiterState(Arc::new(DashMap::new())))
        .attach(RequestLogger)
        .attach(CorsHandler)
        .attach(RateLimiter)
        .attach(ApiResponseTransformer)
        .mount("/", routes![get_user_by_id, get_post_by_id])
        .register("/", catchers![default_catcher])
}