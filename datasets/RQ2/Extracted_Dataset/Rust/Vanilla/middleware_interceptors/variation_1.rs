use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

// --- Domain Schema ---

#[derive(Debug, Clone)]
enum UserRole {
    ADMIN,
    USER,
}

#[derive(Debug, Clone)]
struct User {
    id: String, // Using String for UUID simplicity
    email: String,
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: u64,
}

// --- Core HTTP Abstractions ---

#[derive(Debug, Clone)]
struct Request {
    method: String,
    path: String,
    headers: HashMap<String, String>,
    body: String,
    client_addr: String,
}

#[derive(Debug, Clone)]
struct Response {
    status_code: u16,
    headers: HashMap<String, String>,
    body: String,
}

impl Response {
    fn new(status_code: u16, body: String) -> Self {
        Response {
            status_code,
            headers: HashMap::new(),
            body,
        }
    }

    fn with_header(mut self, key: &str, value: &str) -> Self {
        self.headers.insert(key.to_string(), value.to_string());
        self
    }
}

// --- Handler and Middleware Definitions (Functional Approach) ---

// A handler is a function that takes a Request and returns a Response.
// We use Box<dyn ...> for type erasure, allowing different functions to be treated as the same type.
type Handler = Box<dyn Fn(Request) -> Response + Send + Sync>;

// A middleware is a higher-order function. It takes a handler and returns a new, wrapped handler.
type Middleware = Box<dyn Fn(Handler) -> Handler + Send + Sync>;

// --- Middleware Implementations ---

fn logging_middleware(next: Handler) -> Handler {
    Box::new(move |req: Request| {
        let start_time = SystemTime::now();
        println!(
            "[Log] Request received: {} {} from {}",
            req.method, req.path, req.client_addr
        );

        // Clone request for logging after response
        let req_clone_for_log = req.clone();
        let response = next(req);

        let duration = start_time.elapsed().unwrap_or_default();
        println!(
            "[Log] Responded to {} {} with status {} in {}ms",
            req_clone_for_log.method,
            req_clone_for_log.path,
            response.status_code,
            duration.as_millis()
        );
        response
    })
}

fn cors_middleware(next: Handler) -> Handler {
    Box::new(move |req: Request| {
        if req.method == "OPTIONS" {
            return Response::new(204, "".to_string())
                .with_header("Access-Control-Allow-Origin", "*")
                .with_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                .with_header("Access-Control-Allow-Headers", "Content-Type, X-Request-ID");
        }

        let mut response = next(req);
        response
            .headers
            .insert("Access-Control-Allow-Origin".to_string(), "*".to_string());
        response
    })
}

// Rate Limiting State: (last_request_time, request_count)
type RateLimitState = Arc<Mutex<HashMap<String, (u64, u32)>>>;

fn rate_limiting_middleware(next: Handler, state: RateLimitState) -> Handler {
    Box::new(move |req: Request| {
        let mut state_guard = state.lock().unwrap();
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs();
        
        let (last_time, count) = state_guard.entry(req.client_addr.clone()).or_insert((now, 0));

        if now > *last_time {
            // New time window, reset
            *last_time = now;
            *count = 1;
        } else {
            *count += 1;
        }

        // Limit: 5 requests per second per IP
        if *count > 5 {
            return Response::new(429, "Too Many Requests".to_string());
        }
        
        // Drop the lock before calling the next handler
        drop(state_guard);

        next(req)
    })
}

fn transform_middleware(next: Handler) -> Handler {
    Box::new(move |mut req: Request| {
        // Request transformation: ensure a request ID exists
        req.headers
            .entry("X-Request-ID".to_string())
            .or_insert_with(|| format!("req_{}", SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos()));

        // Response transformation: add a server header
        let mut response = next(req);
        response
            .headers
            .insert("X-Powered-By".to_string(), "Rust-Vanilla-V1".to_string());
        response
    })
}

// This middleware is special: it doesn't take a `Handler` but a `FallibleHandler`.
// It converts potential errors into proper HTTP responses.
type FallibleHandler = Box<dyn Fn(Request) -> Result<Response, String> + Send + Sync>;

fn error_handling_middleware(next: FallibleHandler) -> Handler {
    Box::new(move |req: Request| {
        match next(req) {
            Ok(response) => response,
            Err(e) => {
                eprintln!("[Error] Handler error: {}", e);
                Response::new(500, format!("{{\"error\": \"Internal Server Error: {}\"}}", e))
                    .with_header("Content-Type", "application/json")
            }
        }
    })
}

// --- Business Logic Handlers ---

fn get_user_handler(req: Request) -> Result<Response, String> {
    if req.path != "/users/1" {
        return Ok(Response::new(404, "Not Found".to_string()));
    }
    let user = User {
        id: "1".to_string(),
        email: "test@example.com".to_string(),
        password_hash: "secret".to_string(),
        role: UserRole::ADMIN,
        is_active: true,
        created_at: 1678886400,
    };
    let user_json = format!(
        "{{\"id\":\"{}\",\"email\":\"{}\",\"role\":\"{:?}\"}}",
        user.id, user.email, user.role
    );
    Ok(Response::new(200, user_json).with_header("Content-Type", "application/json"))
}

fn root_handler(_req: Request) -> Result<Response, String> {
    Ok(Response::new(200, "Welcome!".to_string()))
}

// --- Main Application ---

fn main() {
    // 1. Define the core application logic (router)
    let router: FallibleHandler = Box::new(|req: Request| {
        match (req.method.as_str(), req.path.as_str()) {
            ("GET", "/users/1") => get_user_handler(req),
            ("GET", "/") => root_handler(req),
            _ => Ok(Response::new(404, "Not Found".to_string())),
        }
    });

    // 2. Create shared state for stateful middleware
    let rate_limit_state = Arc::new(Mutex::new(HashMap::new()));

    // 3. Compose the middleware stack, wrapping from the inside out.
    // The error handler is the outermost layer in terms of converting Result to Response.
    let handler_with_errors = error_handling_middleware(router);
    
    // The remaining middleware wrap the now-infallible handler.
    let handler_with_transform = transform_middleware(handler_with_errors);
    let handler_with_rate_limit = rate_limiting_middleware(handler_with_transform, rate_limit_state);
    let handler_with_cors = cors_middleware(handler_with_rate_limit);
    let final_handler = logging_middleware(handler_with_cors);

    // 4. Simulate incoming requests
    println!("--- Simulating a valid request ---");
    let req1 = Request {
        method: "GET".to_string(),
        path: "/users/1".to_string(),
        headers: HashMap::new(),
        body: "".to_string(),
        client_addr: "127.0.0.1".to_string(),
    };
    let res1 = final_handler(req1);
    println!("Response: {:?}\n", res1);

    println!("--- Simulating an OPTIONS preflight request ---");
    let req2 = Request {
        method: "OPTIONS".to_string(),
        path: "/users/1".to_string(),
        headers: HashMap::new(),
        body: "".to_string(),
        client_addr: "127.0.0.1".to_string(),
    };
    let res2 = final_handler(req2);
    println!("Response: {:?}\n", res2);

    println!("--- Simulating a rate-limited request ---");
    for i in 0..7 {
        println!("Request #{}", i + 1);
        let req = Request {
            method: "GET".to_string(),
            path: "/".to_string(),
            headers: HashMap::new(),
            body: "".to_string(),
            client_addr: "192.168.1.100".to_string(),
        };
        let res = final_handler(req);
        if i == 6 {
            println!("Final Response: {:?}", res);
        }
    }
}