use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex, atomic::{AtomicU128, Ordering}};
use std::thread;
use std::time::{SystemTime, UNIX_EPOCH};

// --- Mock UUID ---
// In a real app, use the 'uuid' crate.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct Uuid(u128);

impl Uuid {
    fn new() -> Self {
        static COUNTER: AtomicU128 = AtomicU128::new(1);
        Uuid(COUNTER.fetch_add(1, Ordering::Relaxed))
    }

    fn from_str(s: &str) -> Option<Self> {
        s.parse::<u128>().ok().map(Uuid)
    }
}

impl std::fmt::Display for Uuid {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

// --- Domain Models ---
#[derive(Debug, Clone, PartialEq)]
enum UserRole {
    ADMIN,
    USER,
}

impl UserRole {
    fn from_str(s: &str) -> Option<Self> {
        match s.to_uppercase().as_str() {
            "ADMIN" => Some(UserRole::ADMIN),
            "USER" => Some(UserRole::USER),
            _ => None,
        }
    }
}

impl std::fmt::Display for UserRole {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}", self)
    }
}

#[derive(Debug, Clone)]
struct User {
    id: Uuid,
    email: String,
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: u64, // Unix timestamp
}

// --- In-Memory Database ---
type Db = Arc<Mutex<HashMap<Uuid, User>>>;

// --- JSON Serialization (Manual) ---
fn user_to_json(user: &User) -> String {
    format!(
        r#"{{"id":"{}","email":"{}","role":"{}","is_active":{},"created_at":{}}}"#,
        user.id, user.email, user.role, user.is_active, user.created_at
    )
}

fn users_to_json(users: Vec<&User>) -> String {
    let users_json: Vec<String> = users.iter().map(|u| user_to_json(u)).collect();
    format!("[{}]", users_json.join(","))
}

// --- Request/Response Handling (Functional Style) ---

fn create_response(status_code: u16, status_text: &str, content_type: &str, body: String) -> String {
    format!(
        "HTTP/1.1 {} {}\r\nContent-Type: {}\r\nContent-Length: {}\r\n\r\n{}",
        status_code,
        status_text,
        content_type,
        body.len(),
        body
    )
}

fn not_found_response() -> String {
    create_response(404, "Not Found", "application/json", r#"{"error":"Not Found"}"#.to_string())
}

fn bad_request_response(message: &str) -> String {
    create_response(400, "Bad Request", "application/json", format!(r#"{{"error":"{}"}}"#, message))
}

fn server_error_response(message: &str) -> String {
    create_response(500, "Internal Server Error", "application/json", format!(r#"{{"error":"{}"}}"#, message))
}

fn handle_list_users(query_params: &HashMap<String, String>, db: &Db) -> String {
    let db_lock = db.lock().unwrap();
    let mut users: Vec<&User> = db_lock.values().collect();

    // Filtering
    if let Some(email) = query_params.get("email") {
        users.retain(|u| u.email.contains(email));
    }
    if let Some(role_str) = query_params.get("role") {
        if let Some(role) = UserRole::from_str(role_str) {
            users.retain(|u| u.role == role);
        }
    }

    // Pagination
    let page = query_params.get("page").and_then(|s| s.parse::<usize>().ok()).unwrap_or(1);
    let limit = query_params.get("limit").and_then(|s| s.parse::<usize>().ok()).unwrap_or(10);
    let start = (page - 1) * limit;
    
    let paginated_users: Vec<&User> = users.into_iter().skip(start).take(limit).collect();
    
    create_response(200, "OK", "application/json", users_to_json(paginated_users))
}

fn handle_get_user(user_id: Uuid, db: &Db) -> String {
    let db_lock = db.lock().unwrap();
    match db_lock.get(&user_id) {
        Some(user) => create_response(200, "OK", "application/json", user_to_json(user)),
        None => not_found_response(),
    }
}

fn handle_create_user(body: &str, db: &Db) -> String {
    // Manual, fragile JSON parsing
    let mut email: Option<String> = None;
    let mut password: Option<String> = None;
    for part in body.trim_matches(|c| c == '{' || c == '}').split(',') {
        let kv: Vec<&str> = part.split(':').map(|s| s.trim().trim_matches('"')).collect();
        if kv.len() == 2 {
            match kv[0] {
                "email" => email = Some(kv[1].to_string()),
                "password" => password = Some(kv[1].to_string()),
                _ => {}
            }
        }
    }

    if let (Some(email), Some(password)) = (email, password) {
        let mut new_user = User {
            id: Uuid::new(),
            email,
            password_hash: format!("hashed_{}", password), // Mock hashing
            role: UserRole::USER,
            is_active: true,
            created_at: SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs(),
        };
        let mut db_lock = db.lock().unwrap();
        db_lock.insert(new_user.id, new_user.clone());
        create_response(201, "Created", "application/json", user_to_json(&new_user))
    } else {
        bad_request_response("Missing 'email' or 'password' in request body")
    }
}

fn handle_update_user(user_id: Uuid, body: &str, db: &Db) -> String {
    let mut db_lock = db.lock().unwrap();
    if let Some(user) = db_lock.get_mut(&user_id) {
        // Manual, fragile JSON parsing for updates
        for part in body.trim_matches(|c| c == '{' || c == '}').split(',') {
            let kv: Vec<&str> = part.split(':').map(|s| s.trim().trim_matches('"')).collect();
            if kv.len() == 2 {
                match kv[0] {
                    "email" => user.email = kv[1].to_string(),
                    "is_active" => user.is_active = kv[1].parse::<bool>().unwrap_or(user.is_active),
                    _ => {}
                }
            }
        }
        create_response(200, "OK", "application/json", user_to_json(user))
    } else {
        not_found_response()
    }
}

fn handle_delete_user(user_id: Uuid, db: &Db) -> String {
    let mut db_lock = db.lock().unwrap();
    if db_lock.remove(&user_id).is_some() {
        create_response(204, "No Content", "text/plain", "".to_string())
    } else {
        not_found_response()
    }
}

fn route_request(method: &str, path: &str, query_params: &HashMap<String, String>, body: &str, db: Db) -> String {
    let path_segments: Vec<&str> = path.split('/').filter(|s| !s.is_empty()).collect();

    match (method, path_segments.as_slice()) {
        ("GET", ["users"]) => handle_list_users(query_params, &db),
        ("POST", ["users"]) => handle_create_user(body, &db),
        ("GET", ["users", id_str]) => {
            match Uuid::from_str(id_str) {
                Some(id) => handle_get_user(id, &db),
                None => bad_request_response("Invalid user ID format"),
            }
        },
        ("PUT", ["users", id_str]) | ("PATCH", ["users", id_str]) => {
            match Uuid::from_str(id_str) {
                Some(id) => handle_update_user(id, body, &db),
                None => bad_request_response("Invalid user ID format"),
            }
        },
        ("DELETE", ["users", id_str]) => {
            match Uuid::from_str(id_str) {
                Some(id) => handle_delete_user(id, &db),
                None => bad_request_response("Invalid user ID format"),
            }
        },
        _ => not_found_response(),
    }
}

fn handle_connection(mut stream: TcpStream, db: Db) {
    let mut reader = BufReader::new(&mut stream);
    let mut request_line = String::new();
    if reader.read_line(&mut request_line).is_err() {
        return;
    }

    let parts: Vec<&str> = request_line.trim().split_whitespace().collect();
    if parts.len() < 2 {
        return;
    }
    let method = parts[0];
    let full_path = parts[1];

    let (path, query_string) = full_path.split_once('?').unwrap_or((full_path, ""));
    let query_params: HashMap<String, String> = query_string
        .split('&')
        .filter_map(|s| s.split_once('='))
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect();

    let mut content_length = 0;
    let mut headers = String::new();
    for line in reader.by_ref().lines() {
        let line = match line {
            Ok(l) => l,
            Err(_) => break,
        };
        if line.is_empty() {
            break;
        }
        headers.push_str(&line);
        if line.to_lowercase().starts_with("content-length:") {
            if let Some(len_str) = line.split(':').nth(1) {
                content_length = len_str.trim().parse::<usize>().unwrap_or(0);
            }
        }
    }

    let mut body = vec![0; content_length];
    if content_length > 0 {
        if reader.read_exact(&mut body).is_err() {
            return;
        }
    }
    let body_str = String::from_utf8_lossy(&body);

    let response = route_request(method, path, &query_params, &body_str, db);
    
    if let Err(e) = stream.write_all(response.as_bytes()) {
        eprintln!("Failed to write response: {}", e);
    }
}

fn main() {
    let listener = TcpListener::bind("127.0.0.1:8080").unwrap();
    println!("Server listening on port 8080");

    let db: Db = Arc::new(Mutex::new(HashMap::new()));
    // Pre-populate with some data
    {
        let mut db_lock = db.lock().unwrap();
        let user1 = User {
            id: Uuid::new(),
            email: "admin@example.com".to_string(),
            password_hash: "hashed_admin_pass".to_string(),
            role: UserRole::ADMIN,
            is_active: true,
            created_at: SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs(),
        };
        db_lock.insert(user1.id, user1);
    }

    for stream in listener.incoming() {
        match stream {
            Ok(stream) => {
                let db_clone = Arc::clone(&db);
                thread::spawn(move || {
                    handle_connection(stream, db_clone);
                });
            }
            Err(e) => {
                eprintln!("Connection failed: {}", e);
            }
        }
    }
}