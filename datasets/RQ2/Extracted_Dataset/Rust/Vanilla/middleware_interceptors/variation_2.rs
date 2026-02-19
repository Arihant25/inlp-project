use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

// --- Domain Schema ---

#[derive(Debug)]
enum UserRole { ADMIN, USER }

#[derive(Debug)]
struct User {
    id: String,
    email: String,
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: u64,
}

#[derive(Debug)]
enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug)]
struct Post {
    id: String,
    user_id: String,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Core HTTP Abstractions ---

#[derive(Clone)]
struct HttpRequest {
    method: String,
    path: String,
    headers: HashMap<String, String>,
    remote_ip: String,
}

struct HttpResponse {
    status_code: u16,
    headers: HashMap<String, String>,
    body: String,
}

impl HttpResponse {
    fn from_error(code: u16, message: &str) -> Self {
        HttpResponse {
            status_code: code,
            headers: HashMap::from([("Content-Type".to_string(), "application/json".to_string())]),
            body: format!("{{\"error\":\"{}\"}}", message),
        }
    }
}

// --- Handler and Middleware Definitions (OOP/Trait-based Decorator) ---

// The base component interface
trait HttpHandler: Send + Sync {
    fn handle(&self, request: HttpRequest) -> Result<HttpResponse, String>;
}

// A concrete component: the final application logic
struct AppRouter;

impl HttpHandler for AppRouter {
    fn handle(&self, request: HttpRequest) -> Result<HttpResponse, String> {
        match (request.method.as_str(), request.path.as_str()) {
            ("GET", "/user") => {
                let user = User {
                    id: "uuid-user-123".to_string(),
                    email: "dev2@example.com".to_string(),
                    password_hash: "hashed_secret".to_string(),
                    role: UserRole::USER,
                    is_active: true,
                    created_at: 1678886400,
                };
                Ok(HttpResponse {
                    status_code: 200,
                    headers: HashMap::from([("Content-Type".to_string(), "application/json".to_string())]),
                    body: format!("{{\"id\":\"{}\",\"email\":\"{}\"}}", user.id, user.email),
                })
            }
            ("GET", "/post") => {
                let post = Post {
                    id: "uuid-post-456".to_string(),
                    user_id: "uuid-user-123".to_string(),
                    title: "My First Post".to_string(),
                    content: "This is the content.".to_string(),
                    status: PostStatus::PUBLISHED,
                };
                Ok(HttpResponse {
                    status_code: 200,
                    headers: HashMap::from([("Content-Type".to_string(), "application/json".to_string())]),
                    body: format!("{{\"id\":\"{}\",\"title\":\"{}\"}}", post.id, post.title),
                })
            }
            ("GET", "/error") => {
                // Simulate a business logic error
                Err("Database connection failed".to_string())
            }
            _ => Ok(HttpResponse {
                status_code: 404,
                headers: HashMap::new(),
                body: "Not Found".to_string(),
            }),
        }
    }
}

// Base Decorator struct
struct MiddlewareWrapper {
    m_next: Box<dyn HttpHandler>,
}

// Concrete Decorator for Logging
struct LoggingInterceptor {
    m_next: Box<dyn HttpHandler>,
}

impl HttpHandler for LoggingInterceptor {
    fn handle(&self, request: HttpRequest) -> Result<HttpResponse, String> {
        println!("[Logger] Incoming: {} {} from {}", request.method, request.path, request.remote_ip);
        let result = self.m_next.handle(request);
        match &result {
            Ok(response) => println!("[Logger] Outgoing: Status {}", response.status_code),
            Err(e) => println!("[Logger] Outgoing: Error '{}'", e),
        }
        result
    }
}

// Concrete Decorator for CORS
struct CorsInterceptor {
    m_next: Box<dyn HttpHandler>,
}

impl HttpHandler for CorsInterceptor {
    fn handle(&self, request: HttpRequest) -> Result<HttpResponse, String> {
        if request.method == "OPTIONS" {
            return Ok(HttpResponse {
                status_code: 204,
                headers: HashMap::from([
                    ("Access-Control-Allow-Origin".to_string(), "*".to_string()),
                    ("Access-Control-Allow-Methods".to_string(), "GET, POST".to_string()),
                ]),
                body: "".to_string(),
            });
        }
        let mut response = self.m_next.handle(request)?;
        response.headers.insert("Access-Control-Allow-Origin".to_string(), "*".to_string());
        Ok(response)
    }
}

// Concrete Decorator for Rate Limiting
struct RateLimiter {
    m_next: Box<dyn HttpHandler>,
    m_state: Arc<Mutex<HashMap<String, u32>>>,
    m_limit: u32,
}

impl HttpHandler for RateLimiter {
    fn handle(&self, request: HttpRequest) -> Result<HttpResponse, String> {
        let mut state = self.m_state.lock().unwrap();
        let count = state.entry(request.remote_ip.clone()).or_insert(0);
        *count += 1;

        if *count > self.m_limit {
            return Ok(HttpResponse::from_error(429, "Too Many Requests"));
        }
        
        drop(state); // Release lock before calling next handler
        self.m_next.handle(request)
    }
}

// Concrete Decorator for Request/Response Transformation
struct TransformInterceptor {
    m_next: Box<dyn HttpHandler>,
}

impl HttpHandler for TransformInterceptor {
    fn handle(&self, mut request: HttpRequest) -> Result<HttpResponse, String> {
        // Request transform
        request.headers.insert("X-Internal-Trace-ID".to_string(), "trace-123".to_string());
        
        // Response transform
        let mut response = self.m_next.handle(request)?;
        response.headers.insert("X-Server-Version".to_string(), "Rust-Vanilla-V2".to_string());
        Ok(response)
    }
}

// The outermost wrapper for catching all errors
struct ErrorHandler {
    m_next: Box<dyn HttpHandler>,
}

impl ErrorHandler {
    // This is not part of the trait, but the final entry point
    fn process_request(&self, request: HttpRequest) -> HttpResponse {
        match self.m_next.handle(request) {
            Ok(response) => response,
            Err(e) => {
                eprintln!("[Error Handler] Caught unhandled error: {}", e);
                HttpResponse::from_error(500, "An unexpected error occurred")
            }
        }
    }
}

fn main() {
    // 1. Create shared state for stateful middleware
    let rate_limit_state = Arc::new(Mutex::new(HashMap::new()));

    // 2. Compose the handler stack using the Decorator pattern
    // Start with the innermost component (the application)
    let app: Box<dyn HttpHandler> = Box::new(AppRouter);
    
    // Wrap it with decorators
    let transformed_app = Box::new(TransformInterceptor { m_next: app });
    let rate_limited_app = Box::new(RateLimiter { 
        m_next: transformed_app, 
        m_state: rate_limit_state.clone(), 
        m_limit: 3 
    });
    let cors_app = Box::new(CorsInterceptor { m_next: rate_limited_app });
    let logged_app = Box::new(LoggingInterceptor { m_next: cors_app });
    
    // The final, outermost layer is the error handler
    let server = ErrorHandler { m_next: logged_app };

    // 3. Simulate requests
    println!("--- Simulating a successful request ---");
    let req1 = HttpRequest {
        method: "GET".to_string(),
        path: "/user".to_string(),
        headers: HashMap::new(),
        remote_ip: "10.0.0.1".to_string(),
    };
    let res1 = server.process_request(req1);
    println!("Response Status: {}, Body: {}\n", res1.status_code, res1.body);

    println!("--- Simulating a request that causes an internal error ---");
    let req2 = HttpRequest {
        method: "GET".to_string(),
        path: "/error".to_string(),
        headers: HashMap::new(),
        remote_ip: "10.0.0.2".to_string(),
    };
    let res2 = server.process_request(req2);
    println!("Response Status: {}, Body: {}\n", res2.status_code, res2.body);

    println!("--- Simulating rate limiting ---");
    for _ in 0..4 {
        let req = HttpRequest {
            method: "GET".to_string(),
            path: "/post".to_string(),
            headers: HashMap::new(),
            remote_ip: "10.0.0.3".to_string(),
        };
        let res = server.process_request(req.clone());
        println!("Response Status: {}, Body: {}", res.status_code, res.body);
    }
}