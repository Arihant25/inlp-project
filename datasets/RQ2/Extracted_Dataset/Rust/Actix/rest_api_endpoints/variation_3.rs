// Variation 3: The Modular Architect - Split by Feature
// Organizes code into modules (handlers, services, models) for better separation of concerns.
// Handlers are thin, delegating business logic to a dedicated service layer.
//
// Cargo.toml dependencies:
// actix-web = "4"
// serde = { version = "1.0", features = ["derive"] }
// serde_json = "1.0"
// uuid = { version = "1", features = ["v4", "serde"] }
// chrono = { version = "0.4", features = ["serde"] }
// tokio = { version = "1", features = ["full"] }

use actix_web::{web, App, HttpServer};
use std::sync::{Arc, Mutex};

// --- Error Module ---
mod errors {
    use actix_web::{HttpResponse, ResponseError, http::StatusCode};
    use std::fmt;

    #[derive(Debug)]
    pub enum ServiceError {
        NotFound(String),
        Conflict(String),
        InternalError,
    }

    impl fmt::Display for ServiceError {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            match self {
                Self::NotFound(msg) => write!(f, "Not Found: {}", msg),
                Self::Conflict(msg) => write!(f, "Conflict: {}", msg),
                Self::InternalError => write!(f, "Internal Server Error"),
            }
        }
    }

    impl ResponseError for ServiceError {
        fn status_code(&self) -> StatusCode {
            match self {
                Self::NotFound(_) => StatusCode::NOT_FOUND,
                Self::Conflict(_) => StatusCode::CONFLICT,
                Self::InternalError => StatusCode::INTERNAL_SERVER_ERROR,
            }
        }
        fn error_response(&self) -> HttpResponse {
            HttpResponse::build(self.status_code()).json(serde_json::json!({
                "error": self.to_string()
            }))
        }
    }
}

// --- User Feature Module ---
mod user {
    use super::errors::ServiceError;
    use actix_web::{web, HttpResponse, Responder};
    use chrono::{DateTime, Utc};
    use serde::{Deserialize, Serialize};
    use std::collections::HashMap;
    use std::sync::{Mutex, Arc};
    use uuid::Uuid;

    // --- user::models ---
    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub enum Role {
        ADMIN,
        USER,
    }

    #[derive(Debug, Serialize, Clone)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        #[serde(skip_serializing)]
        pub password_hash: String,
        pub role: Role,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Deserialize)]
    pub struct CreateUserDto {
        pub email: String,
        pub password: String,
    }

    #[derive(Deserialize)]
    pub struct UpdateUserDto {
        pub email: Option<String>,
        pub role: Option<Role>,
        pub is_active: Option<bool>,
    }
    
    #[derive(Deserialize)]
    pub struct FilterQuery {
        pub page: Option<usize>,
        pub limit: Option<usize>,
        pub role: Option<String>,
    }

    // --- user::service ---
    type Db = Arc<Mutex<HashMap<Uuid, User>>>;

    #[derive(Clone)]
    pub struct UserService {
        db: Db,
    }

    impl UserService {
        pub fn new(db: Db) -> Self {
            UserService { db }
        }

        pub fn create(&self, dto: CreateUserDto) -> Result<User, ServiceError> {
            let mut db_lock = self.db.lock().map_err(|_| ServiceError::InternalError)?;
            if db_lock.values().any(|u| u.email == dto.email) {
                return Err(ServiceError::Conflict("Email already exists".to_string()));
            }
            let new_user = User {
                id: Uuid::new_v4(),
                email: dto.email,
                password_hash: format!("hashed::{}", dto.password),
                role: Role::USER,
                is_active: true,
                created_at: Utc::now(),
            };
            db_lock.insert(new_user.id, new_user.clone());
            Ok(new_user)
        }

        pub fn find_by_id(&self, id: Uuid) -> Result<User, ServiceError> {
            let db_lock = self.db.lock().map_err(|_| ServiceError::InternalError)?;
            db_lock.get(&id).cloned().ok_or_else(|| ServiceError::NotFound("User not found".to_string()))
        }
        
        pub fn find_all(&self, query: FilterQuery) -> Result<Vec<User>, ServiceError> {
            let db_lock = self.db.lock().map_err(|_| ServiceError::InternalError)?;
            let mut users: Vec<User> = db_lock.values().cloned().collect();

            if let Some(role_str) = query.role {
                if role_str.to_uppercase() == "ADMIN" {
                    users.retain(|u| matches!(u.role, Role::ADMIN));
                } else if role_str.to_uppercase() == "USER" {
                    users.retain(|u| matches!(u.role, Role::USER));
                }
            }
            
            let page = query.page.unwrap_or(1);
            let limit = query.limit.unwrap_or(10);
            let skip = (page - 1) * limit;
            
            Ok(users.into_iter().skip(skip).take(limit).collect())
        }

        pub fn update(&self, id: Uuid, dto: UpdateUserDto) -> Result<User, ServiceError> {
            let mut db_lock = self.db.lock().map_err(|_| ServiceError::InternalError)?;
            let user = db_lock.get_mut(&id).ok_or_else(|| ServiceError::NotFound("User not found".to_string()))?;
            
            if let Some(email) = dto.email { user.email = email; }
            if let Some(role) = dto.role { user.role = role; }
            if let Some(is_active) = dto.is_active { user.is_active = is_active; }

            Ok(user.clone())
        }

        pub fn delete(&self, id: Uuid) -> Result<(), ServiceError> {
            let mut db_lock = self.db.lock().map_err(|_| ServiceError::InternalError)?;
            if db_lock.remove(&id).is_some() {
                Ok(())
            } else {
                Err(ServiceError::NotFound("User not found".to_string()))
            }
        }
    }

    // --- user::handlers ---
    pub async fn create_user_handler(
        service: web::Data<UserService>,
        dto: web::Json<CreateUserDto>,
    ) -> Result<impl Responder, ServiceError> {
        let user = service.create(dto.into_inner())?;
        Ok(HttpResponse::Created().json(user))
    }

    pub async fn get_user_handler(
        service: web::Data<UserService>,
        path: web::Path<Uuid>,
    ) -> Result<impl Responder, ServiceError> {
        let user = service.find_by_id(path.into_inner())?;
        Ok(HttpResponse::Ok().json(user))
    }
    
    pub async fn list_users_handler(
        service: web::Data<UserService>,
        query: web::Query<FilterQuery>,
    ) -> Result<impl Responder, ServiceError> {
        let users = service.find_all(query.into_inner())?;
        Ok(HttpResponse::Ok().json(users))
    }

    pub async fn update_user_handler(
        service: web::Data<UserService>,
        path: web::Path<Uuid>,
        dto: web::Json<UpdateUserDto>,
    ) -> Result<impl Responder, ServiceError> {
        let user = service.update(path.into_inner(), dto.into_inner())?;
        Ok(HttpResponse::Ok().json(user))
    }

    pub async fn delete_user_handler(
        service: web::Data<UserService>,
        path: web::Path<Uuid>,
    ) -> Result<impl Responder, ServiceError> {
        service.delete(path.into_inner())?;
        Ok(HttpResponse::NoContent().finish())
    }

    // --- user::routes ---
    pub fn configure_routes(cfg: &mut web::ServiceConfig) {
        cfg.service(
            web::scope("/users")
                .route("", web::post().to(create_user_handler))
                .route("", web::get().to(list_users_handler))
                .route("/{id}", web::get().to(get_user_handler))
                .route("/{id}", web::patch().to(update_user_handler)) // Using PATCH for partial update
                .route("/{id}", web::delete().to(delete_user_handler)),
        );
    }
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    // Initialize mock database
    let db = Arc::new(Mutex::new(std::collections::HashMap::new()));
    
    // Add a sample user
    let mut db_lock = db.lock().unwrap();
    let sample_user = user::User {
        id: uuid::Uuid::new_v4(),
        email: "admin@corp.com".to_string(),
        password_hash: "secret".to_string(),
        role: user::Role::ADMIN,
        is_active: true,
        created_at: chrono::Utc::now(),
    };
    db_lock.insert(sample_user.id, sample_user);
    drop(db_lock);

    // Create the service layer
    let user_service = user::UserService::new(db);
    let app_data = web::Data::new(user_service);

    println!("Server running at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(app_data.clone())
            .configure(user::configure_routes)
    })
    .bind(("127.0.0.1", 8080))?
    .run()
    .await
}