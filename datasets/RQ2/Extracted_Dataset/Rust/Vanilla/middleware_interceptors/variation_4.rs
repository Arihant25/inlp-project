use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

// --- Domain Schema ---
#[allow(dead_code)]
enum UserRole { ADMIN, USER }
#[allow(dead_code)]
struct User { id: String, email: String, role: UserRole }

#[allow(dead_code)]
enum PostStatus { DRAFT, PUBLISHED }
#[allow(dead_code)]
struct Post { id: String, user_id: String, title: String, status: PostStatus }

// --- Core HTTP Abstractions ---
#[derive(Clone)]
struct HttpRequest {
    method: String,
    uri: String,
    headers: HashMap<String, String>,
    body: Vec<u8>,
    context: Arc<Mutex<HashMap<String, String>>>, // For passing data between middleware
}

#[derive(Debug)]
struct HttpResponse {
    status: u16,
    headers: HashMap<String, String>,
    body: Vec<u8>,
}

impl HttpResponse {
    fn new(status: u16, body: &str) -> Self {
        HttpResponse {
            status,
            headers: HashMap::new(),
            body: body.as_bytes().to_vec(),
        }
    }
}

// --- Handler and Middleware Definitions (Builder Pattern) ---

type HttpHandlerFn = Arc<dyn Fn(HttpRequest) -> Result<HttpResponse, HttpError> + Send + Sync>;
type MiddlewareFn = Arc<dyn Fn(HttpRequest, HttpHandlerFn) -> Result<HttpResponse, HttpError> + Send + Sync>;

#[derive(Clone)]
struct HttpError {
    status_code: u16,
    message: String,
}

// --- Middleware Implementations ---

// Request logging
fn logger_middleware(req: HttpRequest, next: HttpHandlerFn) -> Result<HttpResponse, HttpError> {
    let start_ts = SystemTime::now();
    println!("[Log] Request Start: {} {}", req.method, req.uri);
    let result = next(req);
    let elapsed_ms = start_ts.elapsed().unwrap_or_default().as_millis();
    match &result {
        Ok(res) => println!("[Log] Request End: Status {} | Duration: {}ms", res.status, elapsed_ms),
        Err(err) => println!("[Log] Request End: Error {} | Duration: {}ms", err.status_code, elapsed_ms),
    }
    result
}

// CORS handling
fn cors_middleware(req: HttpRequest, next: HttpHandlerFn) -> Result<HttpResponse, HttpError> {
    if req.method == "OPTIONS" {
        let mut res = HttpResponse::new(204, "");
        res.headers.insert("Access-Control-Allow-Origin".to_string(), "*".to_string());
        res.headers.insert("Access-Control-Allow-Methods".to_string(), "GET, POST, OPTIONS".to_string());
        return Ok(res);
    }
    let mut res = next(req)?;
    res.headers.insert("Access-Control-Allow-Origin".to_string(), "*".to_string());
    Ok(res)
}

// Rate limiting
fn create_rate_limiter(limit: u64) -> MiddlewareFn {
    let state = Arc::new(Mutex::new(HashMap::<String, u64>::new()));
    Arc::new(move |req: HttpRequest, next: HttpHandlerFn| {
        let ip = req.headers.get("X-Forwarded-For").cloned().unwrap_or("127.0.0.1".to_string());
        let mut state_guard = state.lock().unwrap();
        let count = state_guard.entry(ip).or_insert(0);
        *count += 1;
        if *count > limit {
            return Err(HttpError { status_code: 429, message: "Too many requests".to_string() });
        }
        drop(state_guard);
        next(req)
    })
}

// Request/response transformation
fn transform_middleware(req: HttpRequest, next: HttpHandlerFn) -> Result<HttpResponse, HttpError> {
    // Add a request ID to the context for other middleware/handlers to use
    let request_id = format!("req-{}", UNIX_EPOCH.elapsed().unwrap().as_micros());
    req.context.lock().unwrap().insert("request_id".to_string(), request_id.clone());
    
    let mut res = next(req)?;
    
    // Add the request ID to the response headers
    res.headers.insert("X-Request-ID".to_string(), request_id);
    res.headers.insert("X-Server-Name".to_string(), "Rust-Vanilla-V4-Builder".to_string());
    Ok(res)
}

// --- Router and Server Builder ---

struct Router {
    routes: HashMap<(String, String), HttpHandlerFn>,
    middleware_stack: Vec<MiddlewareFn>,
    not_found_handler: HttpHandlerFn,
}

impl Router {
    pub fn new() -> Self {
        Router {
            routes: HashMap::new(),
            middleware_stack: Vec::new(),
            not_found_handler: Arc::new(|_| Err(HttpError { status_code: 404, message: "Not Found".to_string() })),
        }
    }

    pub fn with_middleware(mut self, middleware: MiddlewareFn) -> Self {
        self.middleware_stack.push(middleware);
        self
    }

    pub fn route(mut self, method: &str, path: &str, handler: HttpHandlerFn) -> Self {
        self.routes.insert((method.to_string(), path.to_string()), handler);
        self
    }

    pub fn build_handler(&self) -> HttpHandlerFn {
        let routes = self.routes.clone();
        let not_found = self.not_found_handler.clone();

        // The core router logic is the innermost handler
        let router_handler: HttpHandlerFn = Arc::new(move |req: HttpRequest| {
            let key = (req.method.clone(), req.uri.clone());
            match routes.get(&key) {
                Some(handler) => handler(req),
                None => not_found(req),
            }
        });

        // Wrap the router with all middleware, from inside out
        let final_handler = self.middleware_stack.iter().rfold(router_handler, |handler, middleware| {
            let middleware = middleware.clone();
            Arc::new(move |req| middleware(req, handler.clone()))
        });

        // The outermost layer is the final error handler that converts Err to Ok(HttpResponse)
        Arc::new(move |req: HttpRequest| {
            match final_handler(req) {
                Ok(res) => Ok(res),
                Err(e) => {
                    let mut res = HttpResponse::new(e.status_code, &format!("{{\"error\":\"{}\"}}", e.message));
                    res.headers.insert("Content-Type".to_string(), "application/json".to_string());
                    Ok(res)
                }
            }
        })
    }
}

// --- Business Logic Handlers ---

fn get_current_user(_req: HttpRequest) -> Result<HttpResponse, HttpError> {
    let user = User { id: "user-abc".to_string(), email: "builder@example.com".to_string(), role: UserRole::USER };
    let body = format!("{{\"id\":\"{}\",\"email\":\"{}\"}}", user.id, user.email);
    let mut res = HttpResponse::new(200, &body);
    res.headers.insert("Content-Type".to_string(), "application/json".to_string());
    Ok(res)
}

fn create_post(req: HttpRequest) -> Result<HttpResponse, HttpError> {
    let request_id = req.context.lock().unwrap().get("request_id").cloned().unwrap_or_default();
    println!("[Handler] Creating post for request: {}", request_id);
    // In a real app, we'd deserialize req.body
    let post = Post { id: "post-xyz".to_string(), user_id: "user-abc".to_string(), title: "New Post".to_string(), status: PostStatus::DRAFT };
    let body = format!("{{\"id\":\"{}\",\"title\":\"{}\"}}", post.id, post.title);
    Ok(HttpResponse::new(201, &body))
}

fn main() {
    // 1. Use the builder to configure the application
    let app_router = Router::new()
        .with_middleware(Arc::new(logger_middleware))
        .with_middleware(Arc::new(cors_middleware))
        .with_middleware(create_rate_limiter(5))
        .with_middleware(Arc::new(transform_middleware))
        .route("GET", "/users/me", Arc::new(get_current_user))
        .route("POST", "/posts", Arc::new(create_post));

    // 2. Build the final, unified handler function
    let app_handler = app_router.build_handler();

    // 3. Simulate requests
    println!("--- Simulating a valid GET request ---");
    let req1 = HttpRequest {
        method: "GET".to_string(),
        uri: "/users/me".to_string(),
        headers: HashMap::new(),
        body: vec![],
        context: Arc::new(Mutex::new(HashMap::new())),
    };
    let res1 = app_handler(req1).unwrap();
    println!("Response: {:?}\n", res1);

    println!("--- Simulating a valid POST request ---");
    let req2 = HttpRequest {
        method: "POST".to_string(),
        uri: "/posts".to_string(),
        headers: HashMap::new(),
        body: vec![],
        context: Arc::new(Mutex::new(HashMap::new())),
    };
    let res2 = app_handler(req2).unwrap();
    println!("Response: {:?}\n", res2);

    println!("--- Simulating a not found request ---");
    let req3 = HttpRequest {
        method: "GET".to_string(),
        uri: "/nonexistent".to_string(),
        headers: HashMap::new(),
        body: vec![],
        context: Arc::new(Mutex::new(HashMap::new())),
    };
    let res3 = app_handler(req3).unwrap();
    println!("Response: {:?}\n", res3);
}