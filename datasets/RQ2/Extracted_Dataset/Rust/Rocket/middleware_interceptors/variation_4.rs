#[macro_use]
extern crate rocket;

use async_trait::async_trait;
use rocket::fairing::{Fairing, Info, Kind};
use rocket::figment::{
    providers::{Format, Toml},
    Figment,
};
use rocket::http::{Header, Method, Status};
use rocket::serde::json::{json, Json, Value};
use rocket::{Config, Request, Response, Rocket, Build};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use uuid::Uuid;

// --- Domain Schema ---

#[derive(Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
enum UserRole {
    Admin,
    User,
}

#[derive(Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
struct User {
    id: Uuid,
    email: String,
    #[serde(skip_serializing)]
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
enum PostStatus {
    Draft,
    Published,
}

#[derive(Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Middleware Implementation (Advanced, Configurable Style) ---

#[derive(Deserialize, Debug)]
struct FairingConfig {
    allowed_origin: String,
    rate_limit_count: u64,
    rate_limit_period_sec: u64,
}

impl Default for FairingConfig {
    fn default() -> Self {
        FairingConfig {
            allowed_origin: "*".to_string(),
            rate_limit_count: 100,
            rate_limit_period_sec: 60,
        }
    }
}

struct RequestStartTime(Instant);

struct RateLimitEntry {
    count: u64,
    start: Instant,
}

struct AppIntegrator {
    config: FairingConfig,
    rate_limit_map: Mutex<HashMap<IpAddr, RateLimitEntry>>,
}

#[async_trait]
impl Fairing for AppIntegrator {
    fn info(&self) -> Info {
        Info {
            name: "Application Integration Fairing",
            kind: Kind::Request | Kind::Response | Kind::Ignite,
        }
    }

    // Load configuration when Rocket starts
    async fn on_ignite(&self, rocket: Rocket<Build>) -> rocket::fairing::Result {
        let figment = rocket.figment();
        let fairing_config: FairingConfig = figment.extract_inner("fairings.integrator").unwrap_or_default();
        
        // This is a bit of a hack to modify self state after ignition.
        // A better approach in a real app would be to put the config in managed state.
        // For this example, we'll just print it.
        println!("Integrator Fairing configured: {:?}", fairing_config);
        
        Ok(rocket)
    }

    // Handle request-time logic
    async fn on_request(&self, req: &mut Request<'_>, _: &mut rocket::Data<'_>) {
        // Store start time in request-local state for accurate timing
        req.local_cache(|| RequestStartTime(Instant::now()));

        // Rate Limiting
        if let Some(ip) = req.client_ip() {
            let mut map = self.rate_limit_map.lock().expect("Rate limit map lock poisoned");
            let entry = map.entry(ip).or_insert(RateLimitEntry {
                count: 0,
                start: Instant::now(),
            });

            if entry.start.elapsed() > Duration::from_secs(self.config.rate_limit_period_sec) {
                entry.count = 1;
                entry.start = Instant::now();
            } else {
                entry.count += 1;
            }

            if entry.count > self.config.rate_limit_count {
                req.set_outcome(rocket::outcome::Outcome::Error(Status::TooManyRequests));
            }
        }
    }

    // Handle response-time logic
    async fn on_response<'r>(&self, req: &'r Request<'_>, res: &mut Response<'r>) {
        // CORS Handling
        res.set_header(Header::new("Access-Control-Allow-Origin", self.config.allowed_origin.clone()));
        res.set_header(Header::new("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE"));
        res.set_header(Header::new("Access-Control-Allow-Headers", "Content-Type, Authorization"));

        if req.method() == Method::Options {
            res.set_status(Status::NoContent);
            return;
        }

        // Request/Response Transformation & Logging
        if let Some(start_time) = req.local_cache_get::<RequestStartTime>() {
            let elapsed_ms = start_time.0.elapsed().as_millis();
            res.set_header(Header::new("X-Response-Time-ms", elapsed_ms.to_string()));
            println!(
                "Request: {} {} -> {} ({}ms)",
                req.method(),
                req.uri(),
                res.status(),
                elapsed_ms
            );
        }

        // Error Handling Transformation
        if res.status().class().is_error() && res.body().is_empty() {
            let error_body = json!({
                "error": {
                    "code": res.status().code,
                    "message": res.status().reason().unwrap_or("An unexpected error occurred.").to_string()
                }
            }).to_string();

            res.set_header(rocket::http::ContentType::JSON);
            res.set_sized_body(error_body.len(), std::io::Cursor::new(error_body));
        }
    }
}

// --- Mock API Routes ---

#[get("/users/<id>")]
fn get_user_handler(id: Uuid) -> Json<User> {
    Json(User {
        id,
        email: "developer4@example.com".to_string(),
        password_hash: "super_secret_hash_string".to_string(),
        role: UserRole::Admin,
        is_active: true,
        created_at: chrono::Utc::now(),
    })
}

#[get("/posts/<id>")]
fn get_post_handler(id: Uuid) -> Result<Json<Post>, Status> {
    if id.is_nil() {
        return Err(Status::BadRequest);
    }
    Ok(Json(Post {
        id,
        user_id: Uuid::new_v4(),
        title: "Advanced Fairing Implementation".to_string(),
        content: "Using request-local state and configuration.".to_string(),
        status: PostStatus::Published,
    }))
}

#[catch(404)]
fn not_found_catcher() -> Value {
    // This catcher is now a fallback; the fairing handles formatting.
    json!({"error": {"code": 404, "message": "The requested resource was not found."}})
}

#[launch]
fn rocket() -> _ {
    // Mock configuration that would typically be in Rocket.toml
    // [fairings.integrator]
    // allowed_origin = "https://my-frontend.com"
    // rate_limit_count = 100
    // rate_limit_period_sec = 60
    let figment = Figment::from(Config::default())
        .merge(Toml::string(r#"
            [fairings.integrator]
            allowed_origin = "https://my-frontend.com"
            rate_limit_count = 50
            rate_limit_period_sec = 30
        "#).nested());

    rocket::custom(figment)
        .attach(AppIntegrator {
            config: FairingConfig::default(), // Will be re-read on_ignite
            rate_limit_map: Mutex::new(HashMap::new()),
        })
        .mount("/", routes![get_user_handler, get_post_handler])
        .register("/", catchers![not_found_catcher])
}