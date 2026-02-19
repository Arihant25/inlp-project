#[macro_use]
extern crate rocket;

use rocket::fairing::AdHoc;
use rocket::http::{Header, Method, Status};
use rocket::serde::json::{json, Json, Value};
use rocket::{Request, Response, State};
use serde::Serialize;
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Mutex;
use std::time::{Duration, SystemTime};
use uuid::Uuid;

// --- Domain Schema ---

#[derive(Serialize, Debug)]
#[serde(rename_all = "snake_case")]
enum UserRole {
    Admin,
    User,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "snake_case")]
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
#[serde(rename_all = "snake_case")]
enum PostStatus {
    Draft,
    Published,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "snake_case")]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Middleware Implementation (Functional AdHoc Style) ---

type RateLimitMap = Mutex<HashMap<IpAddr, (u8, SystemTime)>>;

// --- Error Handling ---

#[catch(404)]
fn handle_not_found() -> Value {
    json!({ "error": "Not Found" })
}

#[catch(500)]
fn handle_server_error() -> Value {
    json!({ "error": "Internal Server Error" })
}

// --- Mock API Routes ---

#[get("/user/<id>")]
fn fetch_user(id: Uuid) -> Json<User> {
    Json(User {
        id,
        email: "developer3@example.com".to_string(),
        password_hash: "a_very_secure_hash".to_string(),
        role: UserRole::User,
        is_active: false,
        created_at: 1672531200,
    })
}

#[get("/post/<id>")]
fn fetch_post(id: Uuid) -> Json<Post> {
    Json(Post {
        id,
        user_id: Uuid::new_v4(),
        title: "Functional Fairings".to_string(),
        content: "Using AdHoc fairings in Rocket.".to_string(),
        status: PostStatus::Draft,
    })
}

#[launch]
fn rocket() -> _ {
    rocket::build()
        // 1. Request Logging Fairing
        .attach(AdHoc::on_request("Request Logger", |req, _| {
            Box::pin(async move {
                println!("-> {} {}", req.method(), req.uri().path());
            })
        }))
        // 2. Rate Limiting Fairing
        .attach(AdHoc::on_request("Rate Limiter", |req, _| {
            Box::pin(async move {
                let client_ip = req.client_ip().unwrap();
                let rate_limit_state = req.rocket().state::<RateLimitMap>().unwrap();
                let mut map = rate_limit_state.lock().unwrap();

                let (count, last_req_time) = map.entry(client_ip).or_insert((0, SystemTime::now()));

                if last_req_time.elapsed().unwrap() > Duration::from_secs(10) {
                    *count = 1;
                    *last_req_time = SystemTime::now();
                } else {
                    *count += 1;
                }

                if *count > 5 { // 5 requests per 10 seconds
                    req.set_outcome(rocket::outcome::Outcome::Failure(Status::TooManyRequests));
                }
            })
        }))
        // 3. CORS and Response Transformation Fairing
        .attach(AdHoc::on_response("CORS & Transformer", |req, res| {
            Box::pin(async move {
                // CORS Handling
                res.set_header(Header::new("Access-Control-Allow-Origin", "https://example.com"));
                res.set_header(Header::new("Access-Control-Allow-Methods", "GET"));
                res.set_header(Header::new("Access-Control-Allow-Headers", "Content-Type"));

                if req.method() == Method::Options {
                    res.set_status(Status::NoContent);
                    return;
                }

                // Response Transformation
                res.set_header(Header::new("X-Server-Name", "Rocket-Functional-API"));

                // Final part of logging
                println!("<- {} {} {}", req.method(), req.uri().path(), res.status());
            })
        }))
        // 4. Error Handling Fairing (transforms error responses)
        .attach(AdHoc::on_response("Error Formatter", |_, res| {
            Box::pin(async move {
                if res.status().class().is_client_error() || res.status().class().is_server_error() {
                    if res.body().is_empty() {
                         let body = json!({
                            "error": {
                                "code": res.status().code,
                                "message": res.status().reason().unwrap_or("An error occurred")
                            }
                        }).to_string();
                        res.set_sized_body(body.len(), std::io::Cursor::new(body));
                        res.set_header(rocket::http::ContentType::JSON);
                    }
                }
            })
        }))
        .manage(Mutex::new(HashMap::<IpAddr, (u8, SystemTime)>::new()))
        .mount("/", routes![fetch_user, fetch_post])
        .register("/", catchers![handle_not_found, handle_server_error])
}