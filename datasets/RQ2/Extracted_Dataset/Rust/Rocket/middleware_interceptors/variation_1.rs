#[macro_use]
extern crate rocket;

use rocket::fairing::{Fairing, Info, Kind};
use rocket::http::{Header, Method, Status};
use rocket::serde::json::{json, Json, Value};
use rocket::tokio::sync::Mutex;
use rocket::{Request, Response, State};
use serde::Serialize;
use std::collections::HashMap;
use std::net::IpAddr;
use std::time::{Duration, SystemTime};
use uuid::Uuid;

// --- Domain Schema ---

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
enum UserRole {
    ADMIN,
    USER,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
struct User {
    id: Uuid,
    email: String,
    #[serde(skip_serializing)]
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: u64,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
enum PostStatus {
    DRAFT,
    PUBLISHED,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Middleware Implementation (All-in-One Functional Style) ---

struct RequestTimer(SystemTime);

#[derive(Default)]
struct RateLimitTracker {
    requests: Mutex<HashMap<IpAddr, (u32, SystemTime)>>,
}

struct MasterFairing {
    rate_limiter: RateLimitTracker,
}

#[rocket::async_trait]
impl Fairing for MasterFairing {
    fn info(&self) -> Info {
        Info {
            name: "Master Fairing for Logging, CORS, Rate Limiting, and Transformations",
            kind: Kind::Request | Kind::Response,
        }
    }

    // Request handling: Logging and Rate Limiting
    async fn on_request(&self, request: &mut Request<'_>, _: &mut rocket::Data<'_>) {
        // 1. Request Logging (start)
        request.local_cache(|| RequestTimer(SystemTime::now()));
        println!(
            "Incoming Request: {} {} from {}",
            request.method(),
            request.uri(),
            request.client_ip().unwrap()
        );

        // 2. Rate Limiting
        let client_ip = request.client_ip().unwrap();
        let mut tracker = self.rate_limiter.requests.lock().await;
        let (count, start_time) = tracker
            .entry(client_ip)
            .or_insert((0, SystemTime::now()));

        if start_time.elapsed().unwrap_or_default() > Duration::from_secs(60) {
            // Reset after 1 minute
            *count = 1;
            *start_time = SystemTime::now();
        } else {
            *count += 1;
        }

        // Limit: 10 requests per minute
        if *count > 10 {
            request.set_outcome(rocket::outcome::Outcome::Failure(
                Status::TooManyRequests,
            ));
        }
    }

    // Response handling: CORS, Logging, and Transformations
    async fn on_response<'r>(&self, request: &'r Request<'_>, response: &mut Response<'r>) {
        // 3. CORS Handling
        response.set_header(Header::new("Access-Control-Allow-Origin", "*"));
        response.set_header(Header::new(
            "Access-Control-Allow-Methods",
            "POST, GET, PATCH, OPTIONS",
        ));
        response.set_header(Header::new("Access-Control-Allow-Headers", "*"));
        response.set_header(Header::new("Access-Control-Allow-Credentials", "true"));

        // Handle preflight requests
        if request.method() == Method::Options {
            response.set_status(Status::NoContent);
        }

        // 4. Request/Response Transformation (add response time header)
        let start_time = request.local_cache(|| RequestTimer(SystemTime::now()));
        if let Ok(duration) = start_time.0.elapsed() {
            let ms = duration.as_millis();
            response.set_header(Header::new("X-Response-Time", format!("{}ms", ms)));
            // 1. Request Logging (end)
            println!(
                "Response: {} {} {} in {}ms",
                request.method(),
                request.uri(),
                response.status(),
                ms
            );
        }
    }
}

// --- Error Handling ---

#[catch(404)]
fn not_found() -> Value {
    json!({
        "status": "error",
        "reason": "Resource not found."
    })
}

#[catch(500)]
fn internal_error() -> Value {
    json!({
        "status": "error",
        "reason": "Internal server error."
    })
}

// --- Mock API Routes ---

#[get("/users/<id>")]
fn get_user(id: Uuid) -> Json<User> {
    Json(User {
        id,
        email: "developer1@example.com".to_string(),
        password_hash: "secret".to_string(),
        role: UserRole::ADMIN,
        is_active: true,
        created_at: 1672531200,
    })
}

#[get("/posts/<id>")]
fn get_post(id: Uuid) -> Json<Post> {
    Json(Post {
        id,
        user_id: Uuid::new_v4(),
        title: "My First Post".to_string(),
        content: "This is a post about Rocket middleware.".to_string(),
        status: PostStatus::PUBLISHED,
    })
}

#[launch]
fn rocket() -> _ {
    rocket::build()
        .attach(MasterFairing {
            rate_limiter: RateLimitTracker::default(),
        })
        .mount("/", routes![get_user, get_post])
        .register("/", catchers![not_found, internal_error])
}