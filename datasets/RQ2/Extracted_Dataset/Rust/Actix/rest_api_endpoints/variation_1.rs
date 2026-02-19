// Variation 1: The Pragmatist - Functional Style
// A straightforward, functional approach with handlers as free functions.
// State is managed via a Mutex-wrapped HashMap.
//
// Cargo.toml dependencies:
// actix-web = "4"
// serde = { version = "1.0", features = ["derive"] }
// serde_json = "1.0"
// uuid = { version = "1", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }
// tokio = { version = "1", features = ["full"] }

use actix_web::{web, App, HttpResponse, HttpServer, Responder, ResponseError};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fmt;
use std::sync::{Arc, Mutex};
use uuid::Uuid;

// --- Domain Models ---

#[derive(Debug, Serialize, Clone, Deserialize)]
enum Role {
    ADMIN,
    USER,
}

#[derive(Debug, Serialize, Clone)]
struct User {
    id: Uuid,
    email: String,
    #[serde(skip_serializing)]
    password_hash: String,
    role: Role,
    is_active: bool,
    created_at: DateTime<Utc>,
}

// --- Application State & Error ---

struct AppState {
    users: Mutex<HashMap<Uuid, User>>,
}

#[derive(Debug)]
struct SimpleError {
    message: String,
}

impl fmt::Display for SimpleError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.message)
    }
}

impl ResponseError for SimpleError {
    fn error_response(&self) -> HttpResponse {
        HttpResponse::InternalServerError().json(self.message.clone())
    }
}

// --- DTOs (Data Transfer Objects) ---

#[derive(Deserialize)]
struct CreateUserPayload {
    email: String,
    password: String,
}

#[derive(Deserialize)]
struct UpdateUserPayload {
    email: Option<String>,
    role: Option<Role>,
    is_active: Option<bool>,
}

#[derive(Deserialize)]
struct ListUsersQuery {
    page: Option<usize>,
    limit: Option<usize>,
    role: Option<String>,
    is_active: Option<bool>,
}

// --- Handlers ---

async fn create_user(
    state: web::Data<AppState>,
    payload: web::Json<CreateUserPayload>,
) -> impl Responder {
    let mut users = state.users.lock().unwrap();

    if users.values().any(|u| u.email == payload.email) {
        return HttpResponse::Conflict().json("User with this email already exists");
    }

    let new_user = User {
        id: Uuid::new_v4(),
        email: payload.email.clone(),
        // In a real app, hash the password securely
        password_hash: format!("hashed_{}", payload.password),
        role: Role::USER,
        is_active: true,
        created_at: Utc::now(),
    };

    users.insert(new_user.id, new_user.clone());
    HttpResponse::Created().json(new_user)
}

async fn get_user_by_id(state: web::Data<AppState>, path: web::Path<Uuid>) -> impl Responder {
    let user_id = path.into_inner();
    let users = state.users.lock().unwrap();

    match users.get(&user_id) {
        Some(user) => HttpResponse::Ok().json(user),
        None => HttpResponse::NotFound().json("User not found"),
    }
}

async fn update_user(
    state: web::Data<AppState>,
    path: web::Path<Uuid>,
    payload: web::Json<UpdateUserPayload>,
) -> impl Responder {
    let user_id = path.into_inner();
    let mut users = state.users.lock().unwrap();

    if let Some(user) = users.get_mut(&user_id) {
        if let Some(email) = &payload.email {
            user.email = email.clone();
        }
        if let Some(role) = &payload.role {
            user.role = role.clone();
        }
        if let Some(is_active) = payload.is_active {
            user.is_active = is_active;
        }
        HttpResponse::Ok().json(user.clone())
    } else {
        HttpResponse::NotFound().json("User not found")
    }
}

async fn delete_user(state: web::Data<AppState>, path: web::Path<Uuid>) -> impl Responder {
    let user_id = path.into_inner();
    let mut users = state.users.lock().unwrap();

    if users.remove(&user_id).is_some() {
        HttpResponse::NoContent().finish()
    } else {
        HttpResponse::NotFound().json("User not found")
    }
}

async fn list_users(
    state: web::Data<AppState>,
    query: web::Query<ListUsersQuery>,
) -> impl Responder {
    let users = state.users.lock().unwrap();

    let mut filtered_users: Vec<User> = users.values().cloned().collect();

    // Filtering
    if let Some(role_str) = &query.role {
        let role_filter = match role_str.to_uppercase().as_str() {
            "ADMIN" => Some(Role::ADMIN),
            "USER" => Some(Role::USER),
            _ => None,
        };
        if let Some(role) = role_filter {
            filtered_users.retain(|u| std::mem::discriminant(&u.role) == std::mem::discriminant(&role));
        }
    }
    if let Some(is_active) = query.is_active {
        filtered_users.retain(|u| u.is_active == is_active);
    }

    // Pagination
    let page = query.page.unwrap_or(1);
    let limit = query.limit.unwrap_or(10);
    let offset = (page - 1) * limit;

    let paginated_users: Vec<User> = filtered_users.into_iter().skip(offset).take(limit).collect();

    HttpResponse::Ok().json(paginated_users)
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    // Initialize mock data
    let mut user_map = HashMap::new();
    let admin_user = User {
        id: Uuid::new_v4(),
        email: "admin@example.com".to_string(),
        password_hash: "hashed_admin_pass".to_string(),
        role: Role::ADMIN,
        is_active: true,
        created_at: Utc::now(),
    };
    user_map.insert(admin_user.id, admin_user);

    let app_state = web::Data::new(AppState {
        users: Mutex::new(user_map),
    });

    println!("Server running at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(app_state.clone())
            .service(
                web::scope("/users")
                    .route("", web::post().to(create_user))
                    .route("", web::get().to(list_users))
                    .route("/{id}", web::get().to(get_user_by_id))
                    .route("/{id}", web::put().to(update_user))
                    .route("/{id}", web::delete().to(delete_user)),
            )
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}