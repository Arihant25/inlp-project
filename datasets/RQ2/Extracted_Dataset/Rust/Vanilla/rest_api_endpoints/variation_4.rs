use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex, Once, atomic::{AtomicU128, Ordering}};
use std::thread;
use std::time::{SystemTime, UNIX_EPOCH};

// --- Minimalist Domain ---
#[derive(Debug, Clone, PartialEq)]
enum Role { ADMIN, USER }
#[derive(Debug, Clone)]
struct User { id: u128, email: String, p_hash: String, role: Role, active: bool, created: u64 }

// --- Global DB via std::sync::Once (lazy_static simulation) ---
static mut DB: Option<Arc<Mutex<HashMap<u128, User>>>> = None;
static DB_INIT: Once = Once::new();

fn db() -> Arc<Mutex<HashMap<u128, User>>> {
    DB_INIT.call_once(|| {
        let mut map = HashMap::new();
        let id = gen_id();
        map.insert(id, User {
            id,
            email: "root@localhost".into(),
            p_hash: "root".into(),
            role: Role::ADMIN,
            active: true,
            created: 0,
        });
        unsafe { DB = Some(Arc::new(Mutex::new(map))); }
    });
    unsafe { DB.as_ref().unwrap().clone() }
}

fn gen_id() -> u128 {
    static ID_COUNTER: AtomicU128 = AtomicU128::new(1);
    ID_COUNTER.fetch_add(1, Ordering::Relaxed)
}

// --- Minimalist JSON & HTTP Helpers ---
fn to_json(u: &User) -> String {
    format!(r#"{{"id":"{}","email":"{}","role":"{:?}","is_active":{},"created_at":{}}}"#,
        u.id, u.email, u.role, u.active, u.created)
}

fn resp(code: u16, text: &str, body: String) -> String {
    format!("HTTP/1.1 {} {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{}",
        code, text, body.len(), body)
}

fn err_resp(code: u16, text: &str, msg: &str) -> String {
    resp(code, text, format!(r#"{{"error":"{}"}}"#, msg))
}

// --- Main Request Handler ---
fn handle_req(mut stream: TcpStream) {
    let mut reader = BufReader::new(&mut stream);
    let mut line_one = String::new();
    if reader.read_line(&mut line_one).is_err() { return; }

    let parts: Vec<_> = line_one.trim().split_whitespace().collect();
    if parts.len() < 2 { return; }
    let (method, full_path) = (parts[0], parts[1]);
    
    let (path, query_str) = full_path.split_once('?').unwrap_or((full_path, ""));
    let segments: Vec<_> = path.split('/').filter(|s| !s.is_empty()).collect();

    let mut content_len = 0;
    for line in reader.by_ref().lines().map_while(Result::ok) {
        if line.is_empty() { break; }
        if let Some(len) = line.to_lowercase().strip_prefix("content-length: ") {
            content_len = len.parse().unwrap_or(0);
        }
    }
    let mut body_buf = vec![0; content_len];
    if content_len > 0 {
        let _ = reader.read_exact(&mut body_buf);
    }
    let body = String::from_utf8_lossy(&body_buf);

    let response = match (method, segments.as_slice()) {
        ("GET", ["users"]) => {
            let db_lock = db().lock().unwrap();
            let mut users: Vec<_> = db_lock.values().collect();

            // Filtering
            let query_map: HashMap<_, _> = query_str.split('&')
                .filter_map(|s| s.split_once('='))
                .collect();

            if let Some(email) = query_map.get("email") {
                users.retain(|u| u.email.contains(email));
            }
            if let Some(role) = query_map.get("role") {
                let role_enum = if role.eq_ignore_ascii_case("ADMIN") { Some(Role::ADMIN) } else if role.eq_ignore_ascii_case("USER") { Some(Role::USER) } else { None };
                if let Some(r) = role_enum { users.retain(|u| u.role == r); }
            }

            // Pagination
            let page: usize = query_map.get("page").and_then(|s| s.parse().ok()).unwrap_or(1);
            let limit: usize = query_map.get("limit").and_then(|s| s.parse().ok()).unwrap_or(10);
            let start = (page - 1) * limit;

            let user_list_json = users.iter().skip(start).take(limit).map(|&u| to_json(u)).collect::<Vec<_>>().join(",");
            resp(200, "OK", format!("[{}]", user_list_json))
        }

        ("GET", ["users", id_str]) => {
            if let Ok(id) = id_str.parse::<u128>() {
                let db_lock = db().lock().unwrap();
                match db_lock.get(&id) {
                    Some(u) => resp(200, "OK", to_json(u)),
                    None => err_resp(404, "Not Found", "User not found"),
                }
            } else {
                err_resp(400, "Bad Request", "Invalid user ID")
            }
        }

        ("POST", ["users"]) => {
            // Brittle, inline JSON parsing
            let mut email = None;
            let mut password = None;
            for pair in body.trim_matches(|c| c == '{' || c == '}').split(',') {
                if let Some((k, v)) = pair.split_once(':') {
                    let key = k.trim().trim_matches('"');
                    let val = v.trim().trim_matches('"');
                    if key == "email" { email = Some(val.to_string()); }
                    if key == "password" { password = Some(val.to_string()); }
                }
            }
            
            if let (Some(email), Some(password)) = (email, password) {
                let mut db_lock = db().lock().unwrap();
                let new_user = User {
                    id: gen_id(),
                    email,
                    p_hash: format!("h-{}", password),
                    role: Role::USER,
                    active: true,
                    created: SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs(),
                };
                db_lock.insert(new_user.id, new_user.clone());
                resp(201, "Created", to_json(&new_user))
            } else {
                err_resp(400, "Bad Request", "Missing email or password")
            }
        }

        ("PUT", ["users", id_str]) | ("PATCH", ["users", id_str]) => {
            if let Ok(id) = id_str.parse::<u128>() {
                let mut db_lock = db().lock().unwrap();
                if let Some(user) = db_lock.get_mut(&id) {
                    for pair in body.trim_matches(|c| c == '{' || c == '}').split(',') {
                        if let Some((k, v)) = pair.split_once(':') {
                            let key = k.trim().trim_matches('"');
                            let val = v.trim();
                            if key == "email" { user.email = val.trim_matches('"').to_string(); }
                            if key == "is_active" { user.active = val.parse().unwrap_or(user.active); }
                        }
                    }
                    resp(200, "OK", to_json(user))
                } else {
                    err_resp(404, "Not Found", "User not found")
                }
            } else {
                err_resp(400, "Bad Request", "Invalid user ID")
            }
        }

        ("DELETE", ["users", id_str]) => {
            if let Ok(id) = id_str.parse::<u128>() {
                let mut db_lock = db().lock().unwrap();
                if db_lock.remove(&id).is_some() {
                    resp(204, "No Content", "".to_string())
                } else {
                    err_resp(404, "Not Found", "User not found")
                }
            } else {
                err_resp(400, "Bad Request", "Invalid user ID")
            }
        }

        _ => err_resp(404, "Not Found", "Endpoint does not exist"),
    };

    let _ = stream.write_all(response.as_bytes());
}

fn main() {
    let listener = TcpListener::bind("127.0.0.1:8083").unwrap();
    println!("Minimalist server listening on port 8083");

    // Initialize DB on main thread
    db();

    for stream in listener.incoming() {
        match stream {
            Ok(s) => { thread::spawn(|| handle_req(s)); }
            Err(e) => { eprintln!("Connection failed: {}", e); }
        }
    }
}