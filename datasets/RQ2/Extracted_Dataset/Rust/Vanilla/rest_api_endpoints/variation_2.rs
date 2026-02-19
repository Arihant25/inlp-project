use std::collections::HashMap;
use std::io::{self, BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex, atomic::{AtomicU128, Ordering}};
use std::thread;
use std::time::{SystemTime, UNIX_EPOCH};

// --- Mock UUID ---
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct EntityId(u128);

impl EntityId {
    fn new() -> Self {
        static NEXT_ID: AtomicU128 = AtomicU128::new(1);
        EntityId(NEXT_ID.fetch_add(1, Ordering::Relaxed))
    }

    fn from_string(s: &str) -> Result<Self, &'static str> {
        s.parse::<u128>().map(EntityId).map_err(|_| "Invalid ID format")
    }
}

impl std::fmt::Display for EntityId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

// --- Domain Models ---
#[derive(Debug, Clone)]
enum Role { ADMIN, USER }
impl Role {
    fn from_string(s: &str) -> Option<Self> {
        match s.to_uppercase().as_str() {
            "ADMIN" => Some(Role::ADMIN),
            "USER" => Some(Role::USER),
            _ => None,
        }
    }
}
impl std::fmt::Display for Role {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}", self)
    }
}

#[derive(Debug, Clone)]
struct User {
    id: EntityId,
    email: String,
    password_hash: String,
    role: Role,
    is_active: bool,
    created_at: u64,
}

// --- Data Store (OOP Style) ---
struct UserStore {
    users: Mutex<HashMap<EntityId, User>>,
}

impl UserStore {
    fn new() -> Self {
        UserStore {
            users: Mutex::new(HashMap::new()),
        }
    }
}

// --- HTTP Abstractions ---
struct Request {
    method: String,
    path: String,
    query_params: HashMap<String, String>,
    body: String,
}

struct Response {
    status_code: u16,
    status_text: String,
    body: String,
}

impl Response {
    fn new(status_code: u16, status_text: &str, body: String) -> Self {
        Response {
            status_code,
            status_text: status_text.to_string(),
            body,
        }
    }

    fn to_http_string(&self) -> String {
        format!(
            "HTTP/1.1 {} {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{}",
            self.status_code,
            self.status_text,
            self.body.len(),
            self.body
        )
    }
}

// --- API Server (OOP Style) ---
struct ApiServer {
    address: String,
    user_store: Arc<UserStore>,
}

impl ApiServer {
    fn new(address: String, user_store: Arc<UserStore>) -> Self {
        ApiServer { address, user_store }
    }

    fn run(&self) {
        let listener = TcpListener::bind(&self.address).expect("Failed to bind to address");
        println!("Server running on {}", self.address);

        for stream in listener.incoming() {
            match stream {
                Ok(stream) => {
                    let user_store_clone = self.user_store.clone();
                    thread::spawn(move || {
                        Self::handle_client(stream, user_store_clone);
                    });
                }
                Err(e) => {
                    eprintln!("Error accepting connection: {}", e);
                }
            }
        }
    }

    fn handle_client(mut stream: TcpStream, user_store: Arc<UserStore>) {
        if let Ok(Some(request)) = Self::parse_request(&mut stream) {
            let response = Self::route(request, user_store);
            if let Err(e) = stream.write_all(response.to_http_string().as_bytes()) {
                eprintln!("Failed to write response: {}", e);
            }
        }
    }

    fn parse_request(stream: &mut TcpStream) -> io::Result<Option<Request>> {
        let mut reader = BufReader::new(stream);
        let mut request_line = String::new();
        reader.read_line(&mut request_line)?;

        let parts: Vec<&str> = request_line.trim().split_whitespace().collect();
        if parts.len() < 2 { return Ok(None); }

        let method = parts[0].to_string();
        let full_path = parts[1];
        let (path, query_str) = full_path.split_once('?').unwrap_or((full_path, ""));
        
        let query_params = url_encoded_parser::parse(query_str);

        let mut content_length = 0;
        loop {
            let mut header_line = String::new();
            reader.read_line(&mut header_line)?;
            if header_line.trim().is_empty() { break; }
            if header_line.to_lowercase().starts_with("content-length:") {
                content_length = header_line[15..].trim().parse().unwrap_or(0);
            }
        }

        let mut body_bytes = vec![0; content_length];
        if content_length > 0 {
            reader.read_exact(&mut body_bytes)?;
        }
        let body = String::from_utf8_lossy(&body_bytes).to_string();

        Ok(Some(Request { method, path: path.to_string(), query_params, body }))
    }

    fn route(req: Request, user_store: Arc<UserStore>) -> Response {
        let path_segments: Vec<&str> = req.path.split('/').filter(|s| !s.is_empty()).collect();
        
        match (req.method.as_str(), path_segments.as_slice()) {
            ("GET", ["users"]) => Self::get_user_list(&req, user_store),
            ("POST", ["users"]) => Self::create_user(&req, user_store),
            ("GET", ["users", id_str]) => Self::get_user_by_id(id_str, user_store),
            ("PUT", ["users", id_str]) | ("PATCH", ["users", id_str]) => Self::update_user(id_str, &req, user_store),
            ("DELETE", ["users", id_str]) => Self::delete_user(id_str, user_store),
            _ => Response::new(404, "Not Found", r#"{"error":"Endpoint not found"}"#.to_string()),
        }
    }

    // --- Endpoint Handlers as static methods ---
    fn get_user_list(req: &Request, store: Arc<UserStore>) -> Response {
        let users_db = store.users.lock().unwrap();
        let mut filtered_users: Vec<User> = users_db.values().cloned().collect();

        if let Some(email) = req.query_params.get("email") {
            filtered_users.retain(|u| u.email.contains(email));
        }
        if let Some(role_str) = req.query_params.get("role") {
            if let Some(role_enum) = Role::from_string(role_str) {
                filtered_users.retain(|u| std::mem::discriminant(&u.role) == std::mem::discriminant(&role_enum));
            }
        }

        let page: usize = req.query_params.get("page").and_then(|s| s.parse().ok()).unwrap_or(1);
        let limit: usize = req.query_params.get("limit").and_then(|s| s.parse().ok()).unwrap_or(10);
        let start_index = (page - 1) * limit;

        let paginated_users: Vec<String> = filtered_users.iter()
            .skip(start_index)
            .take(limit)
            .map(json_helper::serialize_user)
            .collect();

        Response::new(200, "OK", format!("[{}]", paginated_users.join(",")))
    }

    fn get_user_by_id(id_str: &str, store: Arc<UserStore>) -> Response {
        match EntityId::from_string(id_str) {
            Ok(id) => {
                let users_db = store.users.lock().unwrap();
                match users_db.get(&id) {
                    Some(user) => Response::new(200, "OK", json_helper::serialize_user(user)),
                    None => Response::new(404, "Not Found", r#"{"error":"User not found"}"#.to_string()),
                }
            }
            Err(e) => Response::new(400, "Bad Request", format!(r#"{{"error":"{}"}}"#, e)),
        }
    }

    fn create_user(req: &Request, store: Arc<UserStore>) -> Response {
        if let Ok(parsed_body) = json_helper::parse_body(&req.body) {
            let email = parsed_body.get("email").cloned();
            let password = parsed_body.get("password").cloned();

            if let (Some(email), Some(password)) = (email, password) {
                let new_user = User {
                    id: EntityId::new(),
                    email,
                    password_hash: format!("hashed:{}", password),
                    role: Role::USER,
                    is_active: true,
                    created_at: SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs(),
                };
                store.users.lock().unwrap().insert(new_user.id, new_user.clone());
                Response::new(201, "Created", json_helper::serialize_user(&new_user))
            } else {
                Response::new(400, "Bad Request", r#"{"error":"'email' and 'password' are required"}"#.to_string())
            }
        } else {
            Response::new(400, "Bad Request", r#"{"error":"Invalid JSON body"}"#.to_string())
        }
    }
    
    fn update_user(id_str: &str, req: &Request, store: Arc<UserStore>) -> Response {
        match EntityId::from_string(id_str) {
            Ok(id) => {
                let mut users_db = store.users.lock().unwrap();
                if let Some(user) = users_db.get_mut(&id) {
                    if let Ok(parsed_body) = json_helper::parse_body(&req.body) {
                        if let Some(email) = parsed_body.get("email") { user.email = email.clone(); }
                        if let Some(is_active_str) = parsed_body.get("is_active") {
                            user.is_active = is_active_str.parse().unwrap_or(user.is_active);
                        }
                        Response::new(200, "OK", json_helper::serialize_user(user))
                    } else {
                        Response::new(400, "Bad Request", r#"{"error":"Invalid JSON body"}"#.to_string())
                    }
                } else {
                    Response::new(404, "Not Found", r#"{"error":"User not found"}"#.to_string())
                }
            }
            Err(e) => Response::new(400, "Bad Request", format!(r#"{{"error":"{}"}}"#, e)),
        }
    }

    fn delete_user(id_str: &str, store: Arc<UserStore>) -> Response {
        match EntityId::from_string(id_str) {
            Ok(id) => {
                let mut users_db = store.users.lock().unwrap();
                if users_db.remove(&id).is_some() {
                    Response::new(204, "No Content", "".to_string())
                } else {
                    Response::new(404, "Not Found", r#"{"error":"User not found"}"#.to_string())
                }
            }
            Err(e) => Response::new(400, "Bad Request", format!(r#"{{"error":"{}"}}"#, e)),
        }
    }
}

// --- Helper Modules ---
mod json_helper {
    use super::{User, HashMap};
    pub fn serialize_user(user: &User) -> String {
        format!(
            r#"{{"id":"{}","email":"{}","role":"{}","is_active":{},"created_at":{}}}"#,
            user.id, user.email, user.role, user.is_active, user.created_at
        )
    }
    // Very basic and fragile JSON body parser
    pub fn parse_body(body: &str) -> Result<HashMap<String, String>, ()> {
        body.trim_matches(|c| c == '{' || c == '}' || c == '\n' || c == '\r')
            .split(',')
            .map(|pair| {
                let mut parts = pair.splitn(2, ':');
                let key = parts.next()?.trim().trim_matches('"').to_string();
                let value = parts.next()?.trim().trim_matches('"').to_string();
                Some((key, value))
            })
            .collect::<Option<HashMap<String, String>>>()
            .ok_or(())
    }
}

mod url_encoded_parser {
    use super::HashMap;
    pub fn parse(query: &str) -> HashMap<String, String> {
        query.split('&')
            .filter_map(|part| part.split_once('='))
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }
}

fn main() {
    let user_store = Arc::new(UserStore::new());
    // Seed data
    {
        let mut db = user_store.users.lock().unwrap();
        let user1 = User {
            id: EntityId::new(),
            email: "test.user@example.com".to_string(),
            password_hash: "hashed_password".to_string(),
            role: Role::USER,
            is_active: true,
            created_at: SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs(),
        };
        db.insert(user1.id, user1);
    }
    
    let server = ApiServer::new("127.0.0.1:8081".to_string(), user_store);
    server.run();
}