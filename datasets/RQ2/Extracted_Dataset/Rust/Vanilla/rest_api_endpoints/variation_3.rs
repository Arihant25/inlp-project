use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex, atomic::{AtomicU128, Ordering}};
use std::thread;
use std::time::{SystemTime, UNIX_EPOCH};

// --- Core Types ---
type Uuid = u128;
fn generate_uuid() -> Uuid {
    static COUNTER: AtomicU128 = AtomicU128::new(1);
    COUNTER.fetch_add(1, Ordering::SeqCst)
}

// --- Domain Layer ---
#[derive(Debug, Clone, PartialEq)]
pub enum UserRole { ADMIN, USER }
impl UserRole {
    pub fn from_str(s: &str) -> Result<Self, String> {
        match s.to_uppercase().as_str() {
            "ADMIN" => Ok(UserRole::ADMIN),
            "USER" => Ok(UserRole::USER),
            _ => Err(format!("Invalid role: {}", s)),
        }
    }
}
impl std::fmt::Display for UserRole {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}", self)
    }
}

#[derive(Debug, Clone)]
pub struct User {
    pub id: Uuid,
    pub email: String,
    pub password_hash: String,
    pub role: UserRole,
    pub is_active: bool,
    pub created_at: u64,
}

// --- Repository Layer (Data Access) ---
pub trait UserRepository: Send + Sync {
    fn find_by_id(&self, id: Uuid) -> Option<User>;
    fn find_all(&self) -> Vec<User>;
    fn save(&self, user: User) -> User;
    fn delete(&self, id: Uuid) -> bool;
}

pub struct InMemoryUserRepository {
    store: Mutex<HashMap<Uuid, User>>,
}

impl InMemoryUserRepository {
    pub fn new() -> Self {
        Self { store: Mutex::new(HashMap::new()) }
    }
}

impl UserRepository for InMemoryUserRepository {
    fn find_by_id(&self, id: Uuid) -> Option<User> {
        self.store.lock().unwrap().get(&id).cloned()
    }

    fn find_all(&self) -> Vec<User> {
        self.store.lock().unwrap().values().cloned().collect()
    }

    fn save(&self, user: User) -> User {
        let mut store = self.store.lock().unwrap();
        store.insert(user.id, user.clone());
        user
    }

    fn delete(&self, id: Uuid) -> bool {
        self.store.lock().unwrap().remove(&id).is_some()
    }
}

// --- Service Layer (Business Logic) ---
pub struct UserService {
    repo: Arc<dyn UserRepository>,
}

#[derive(Debug)]
pub struct CreateUserDto {
    pub email: String,
    pub password_hash: String,
}

#[derive(Debug)]
pub struct UpdateUserDto {
    pub email: Option<String>,
    pub is_active: Option<bool>,
}

impl UserService {
    pub fn new(repo: Arc<dyn UserRepository>) -> Self {
        Self { repo }
    }

    pub fn create_user(&self, dto: CreateUserDto) -> User {
        let new_user = User {
            id: generate_uuid(),
            email: dto.email,
            password_hash: dto.password_hash,
            role: UserRole::USER,
            is_active: true,
            created_at: SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs(),
        };
        self.repo.save(new_user)
    }

    pub fn get_user(&self, id: Uuid) -> Option<User> {
        self.repo.find_by_id(id)
    }

    pub fn list_and_filter_users(
        &self,
        email_filter: Option<&str>,
        role_filter: Option<UserRole>,
        page: usize,
        limit: usize,
    ) -> Vec<User> {
        let mut users = self.repo.find_all();
        if let Some(email) = email_filter {
            users.retain(|u| u.email.contains(email));
        }
        if let Some(role) = role_filter {
            users.retain(|u| u.role == role);
        }
        users.into_iter().skip((page - 1) * limit).take(limit).collect()
    }

    pub fn update_user(&self, id: Uuid, dto: UpdateUserDto) -> Option<User> {
        if let Some(mut user) = self.repo.find_by_id(id) {
            if let Some(email) = dto.email { user.email = email; }
            if let Some(is_active) = dto.is_active { user.is_active = is_active; }
            Some(self.repo.save(user))
        } else {
            None
        }
    }



    pub fn delete_user(&self, id: Uuid) -> bool {
        self.repo.delete(id)
    }
}

// --- Presentation Layer (HTTP Handling) ---
struct HttpHandler {
    user_service: Arc<UserService>,
}

impl HttpHandler {
    fn new(user_service: Arc<UserService>) -> Self {
        Self { user_service }
    }

    fn handle(&self, mut stream: TcpStream) {
        let mut reader = BufReader::new(&mut stream);
        let mut request_line = String::new();
        if reader.read_line(&mut request_line).is_err() { return; }

        let parts: Vec<_> = request_line.trim().split_whitespace().collect();
        if parts.len() < 2 { return; }
        let (method, full_path) = (parts[0], parts[1]);

        let (path, query) = full_path.split_once('?').unwrap_or((full_path, ""));
        let query_params: HashMap<String, String> = query.split('&')
            .filter_map(|s| s.split_once('=').map(|(k, v)| (k.to_string(), v.to_string())))
            .collect();

        let mut content_length = 0;
        for line in reader.by_ref().lines().map_while(Result::ok) {
            if line.is_empty() { break; }
            if let Some(len) = line.to_lowercase().strip_prefix("content-length: ") {
                content_length = len.trim().parse().unwrap_or(0);
            }
        }
        let mut body = vec![0; content_length];
        if content_length > 0 {
            let _ = reader.read_exact(&mut body);
        }
        let body_str = String::from_utf8_lossy(&body);

        let response = self.route(method, path, &query_params, &body_str);
        let _ = stream.write_all(response.as_bytes());
    }

    fn route(&self, method: &str, path: &str, query: &HashMap<String, String>, body: &str) -> String {
        let segments: Vec<_> = path.split('/').filter(|s| !s.is_empty()).collect();
        match (method, segments.as_slice()) {
            ("GET", ["users"]) => self.list_users_endpoint(query),
            ("POST", ["users"]) => self.create_user_endpoint(body),
            ("GET", ["users", id_str]) => self.get_user_endpoint(id_str),
            ("PUT", ["users", id_str]) | ("PATCH", ["users", id_str]) => self.update_user_endpoint(id_str, body),
            ("DELETE", ["users", id_str]) => self.delete_user_endpoint(id_str),
            _ => self.build_response(404, "Not Found", r#"{"error":"Not Found"}"#),
        }
    }

    // Endpoint implementations
    fn list_users_endpoint(&self, query: &HashMap<String, String>) -> String {
        let page = query.get("page").and_then(|s| s.parse().ok()).unwrap_or(1);
        let limit = query.get("limit").and_then(|s| s.parse().ok()).unwrap_or(10);
        let email = query.get("email").map(|s| s.as_str());
        let role = query.get("role").and_then(|s| UserRole::from_str(s).ok());
        
        let users = self.user_service.list_and_filter_users(email, role, page, limit);
        let json_users: Vec<_> = users.iter().map(Self::user_to_json).collect();
        self.build_response(200, "OK", &format!("[{}]", json_users.join(",")))
    }

    fn get_user_endpoint(&self, id_str: &str) -> String {
        match id_str.parse::<Uuid>() {
            Ok(id) => match self.user_service.get_user(id) {
                Some(user) => self.build_response(200, "OK", &Self::user_to_json(&user)),
                None => self.build_response(404, "Not Found", r#"{"error":"User not found"}"#),
            },
            Err(_) => self.build_response(400, "Bad Request", r#"{"error":"Invalid ID"}"#),
        }
    }

    fn create_user_endpoint(&self, body: &str) -> String {
        // Fragile manual JSON parsing
        let mut email = None; let mut password = None;
        for pair in body.trim_matches(|c| c=='{'||c=='}').split(',') {
            if let Some((k, v)) = pair.split_once(':') {
                let key = k.trim().trim_matches('"');
                let val = v.trim().trim_matches('"').to_string();
                if key == "email" { email = Some(val); }
                if key == "password" { password = Some(val); }
            }
        }
        if let (Some(email), Some(password)) = (email, password) {
            let dto = CreateUserDto { email, password_hash: format!("hash({})", password) };
            let user = self.user_service.create_user(dto);
            self.build_response(201, "Created", &Self::user_to_json(&user))
        } else {
            self.build_response(400, "Bad Request", r#"{"error":"'email' and 'password' required"}"#)
        }
    }
    
    fn update_user_endpoint(&self, id_str: &str, body: &str) -> String {
        let id = match id_str.parse::<Uuid>() {
            Ok(id) => id,
            Err(_) => return self.build_response(400, "Bad Request", r#"{"error":"Invalid ID"}"#),
        };
        let mut email = None; let mut is_active = None;
        for pair in body.trim_matches(|c| c=='{'||c=='}').split(',') {
            if let Some((k, v)) = pair.split_once(':') {
                let key = k.trim().trim_matches('"');
                let val = v.trim();
                if key == "email" { email = Some(val.trim_matches('"').to_string()); }
                if key == "is_active" { is_active = val.parse::<bool>().ok(); }
            }
        }
        let dto = UpdateUserDto { email, is_active };
        match self.user_service.update_user(id, dto) {
            Some(user) => self.build_response(200, "OK", &Self::user_to_json(&user)),
            None => self.build_response(404, "Not Found", r#"{"error":"User not found"}"#),
        }
    }

    fn delete_user_endpoint(&self, id_str: &str) -> String {
        match id_str.parse::<Uuid>() {
            Ok(id) => {
                if self.user_service.delete_user(id) {
                    self.build_response(204, "No Content", "")
                } else {
                    self.build_response(404, "Not Found", r#"{"error":"User not found"}"#)
                }
            },
            Err(_) => self.build_response(400, "Bad Request", r#"{"error":"Invalid ID"}"#),
        }
    }

    // Helpers
    fn user_to_json(user: &User) -> String {
        format!(r#"{{"id":"{}","email":"{}","role":"{}","is_active":{},"created_at":{}}}"#,
            user.id, user.email, user.role, user.is_active, user.created_at)
    }

    fn build_response(&self, code: u16, text: &str, body: &str) -> String {
        format!("HTTP/1.1 {} {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{}",
            code, text, body.len(), body)
    }
}

fn main() {
    let repo = Arc::new(InMemoryUserRepository::new());
    // Seed data
    repo.save(User {
        id: generate_uuid(),
        email: "seed@example.com".to_string(),
        password_hash: "seed_hash".to_string(),
        role: UserRole::ADMIN,
        is_active: true,
        created_at: 0,
    });

    let user_service = Arc::new(UserService::new(repo));
    let handler = Arc::new(HttpHandler::new(user_service));

    let listener = TcpListener::bind("127.0.0.1:8082").unwrap();
    println!("Server with layered architecture listening on port 8082");

    for stream in listener.incoming() {
        if let Ok(stream) = stream {
            let handler_clone = handler.clone();
            thread::spawn(move || {
                handler_clone.handle(stream);
            });
        }
    }
}