use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::future::Future;
use std::pin::Pin;
use std::time::{SystemTime, UNIX_EPOCH};

// --- Domain Schema ---
#[derive(Debug)]
enum UserRole { ADMIN, USER }
#[derive(Debug)]
struct User { id: String, email: String, role: UserRole }

// --- Core HTTP Abstractions ---
#[derive(Clone)]
struct Request {
    method: String,
    path: String,
    headers: HashMap<String, String>,
    client_ip: String,
}

#[derive(Debug)]
struct Response {
    status: u16,
    headers: HashMap<String, String>,
    body: String,
}

// --- Handler and Middleware Definitions ("Onion" / Tower-style) ---

// The 'Next' function in the chain. It's a closure that takes a request and calls the next layer.
type Next<'a> = Box<dyn FnOnce(Request) -> Pin<Box<dyn Future<Output = Response> + Send>> + Send + 'a>;

// A middleware is a function that takes a request and the 'Next' function.
// This is an async-flavored API, even though we'll resolve futures immediately for this example.
type Middleware = Arc<dyn for<'a> Fn(Request, Next<'a>) -> Pin<Box<dyn Future<Output = Response> + Send>> + Send + Sync>;

// The final service/handler at the center of the onion.
type Service = Arc<dyn Fn(Request) -> Pin<Box<dyn Future<Output = Response> + Send>> + Send + Sync>;

// --- Middleware Implementations ---

async fn logging_mw(req: Request, next: Next<'_>) -> Response {
    let start = SystemTime::now();
    println!("[Log] --> {} {}", req.method, req.path);
    
    let response = next(req).await;
    
    let elapsed = start.elapsed().unwrap_or_default().as_micros();
    println!("[Log] <-- {} {} | Status: {} | Took: {}us", response.headers.get("method").unwrap_or(&"".to_string()), response.headers.get("path").unwrap_or(&"".to_string()), response.status, elapsed);
    response
}

async fn cors_mw(req: Request, next: Next<'_>) -> Response {
    if req.method == "OPTIONS" {
        let mut headers = HashMap::new();
        headers.insert("Access-Control-Allow-Origin".to_string(), "*".to_string());
        headers.insert("Access-Control-Allow-Methods".to_string(), "GET, POST".to_string());
        return Response { status: 204, headers, body: "".to_string() };
    }
    
    let mut response = next(req).await;
    response.headers.insert("Access-Control-Allow-Origin".to_string(), "*".to_string());
    response
}

// Rate Limiting state: IP -> (window_start_timestamp, count)
type RateLimitStore = Arc<Mutex<HashMap<String, (u64, u32)>>>;
const RATE_LIMIT_WINDOW_SECS: u64 = 10;
const RATE_LIMIT_MAX_REQUESTS: u32 = 5;

async fn rate_limit_mw(req: Request, next: Next<'_>, store: RateLimitStore) -> Response {
    let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();
    let mut store_lock = store.lock().unwrap();
    let entry = store_lock.entry(req.client_ip.clone()).or_insert((now, 0));

    if now > entry.0 + RATE_LIMIT_WINDOW_SECS {
        // Window expired, reset it
        entry.0 = now;
        entry.1 = 1;
    } else {
        entry.1 += 1;
    }

    if entry.1 > RATE_LIMIT_MAX_REQUESTS {
        return Response {
            status: 429,
            headers: HashMap::new(),
            body: "Rate limit exceeded".to_string(),
        };
    }
    
    drop(store_lock);
    next(req).await
}

async fn transform_mw(req: Request, next: Next<'_>) -> Response {
    // No request transformation in this model, as `req` is moved.
    // We can add headers to the response though.
    let mut response = next(req).await;
    response.headers.insert("X-Server-Engine".to_string(), "Rust-Vanilla-V3-Onion".to_string());
    response
}

async fn error_handling_mw(req: Request, next: Next<'_>) -> Response {
    // This model doesn't use Result, so error handling is about panics or a custom error type.
    // For simplicity, we'll just pass through. In a real app, this would use `catch_unwind`.
    // The final service can return error responses directly.
    let req_method = req.method.clone();
    let req_path = req.path.clone();
    let mut response = next(req).await;
    // Add original request info to response headers for logging
    response.headers.insert("method".to_string(), req_method);
    response.headers.insert("path".to_string(), req_path);
    response
}

// --- Application Service (the center of the onion) ---

fn create_app_service() -> Service {
    Arc::new(|req: Request| {
        Box::pin(async move {
            match (req.method.as_str(), req.path.as_str()) {
                ("GET", "/api/user") => {
                    let user = User { id: "uuid-1".to_string(), email: "onion@example.com".to_string(), role: UserRole::ADMIN };
                    Response {
                        status: 200,
                        headers: HashMap::from([("Content-Type".to_string(), "application/json".to_string())]),
                        body: format!("{{\"id\":\"{}\",\"email\":\"{}\"}}", user.id, user.email),
                    }
                }
                _ => Response {
                    status: 404,
                    headers: HashMap::new(),
                    body: "Endpoint not found".to_string(),
                },
            }
        })
    })
}

// --- Server/Executor ---

struct App {
    middleware_stack: Vec<Middleware>,
    service: Service,
}

impl App {
    fn new(service: Service) -> Self {
        App { middleware_stack: Vec::new(), service }
    }

    fn with(mut self, mw: Middleware) -> Self {
        self.middleware_stack.push(mw);
        self
    }

    fn handle(&self, req: Request) -> Pin<Box<dyn Future<Output = Response> + Send>> {
        // The magic of the onion pattern: build the chain of calls recursively.
        // The `next` closure captures the rest of the stack.
        let service = self.service.clone();
        let mut stack = self.middleware_stack.iter().rev().peekable();

        let final_call = Box::new(move |req: Request| service(req));
        
        let chain = stack.fold(final_call, |next_fn, mw| {
            let mw = mw.clone();
            Box::new(move |req| mw(req, next_fn))
        });

        chain(req)
    }
}

// A simple async runtime executor for our futures
fn block_on<F: Future>(future: F) -> F::Output {
    // This is a placeholder for a real async runtime like tokio or async-std.
    // For this std-only example, we can't truly run async code, so we'll just poll it once.
    // A real implementation would involve a loop and wakers.
    // This simplification is necessary to adhere to the "std only" constraint.
    // We'll use a trick with a no-op waker to poll the future to completion if it's ready.
    use std::task::{Context, Poll, RawWaker, RawWakerVTable, Waker};
    fn dummy_raw_waker() -> RawWaker {
        fn no_op(_: *const ()) {}
        fn clone(_: *const ()) -> RawWaker { dummy_raw_waker() }
        let vtable = &RawWakerVTable::new(clone, no_op, no_op, no_op);
        RawWaker::new(std::ptr::null(), vtable)
    }
    fn dummy_waker() -> Waker { unsafe { Waker::from_raw(dummy_raw_waker()) } }
    let mut cx = Context::from_waker(&dummy_waker());
    let mut pinned_future = future;
    let mut future = unsafe { Pin::new_unchecked(&mut pinned_future) };
    match future.as_mut().poll(&mut cx) {
        Poll::Ready(val) => val,
        Poll::Pending => panic!("Future did not complete immediately"),
    }
}


fn main() {
    let rate_limit_store = Arc::new(Mutex::new(HashMap::new()));
    let app_service = create_app_service();

    let app = App::new(app_service)
        .with(Arc::new(error_handling_mw)) // Outermost
        .with(Arc::new(logging_mw))
        .with(Arc::new(cors_mw))
        .with(Arc::new(move |req, next| {
            // Middleware with state needs to be wrapped in a closure
            Box::pin(rate_limit_mw(req, next, rate_limit_store.clone()))
        }))
        .with(Arc::new(transform_mw)); // Innermost

    println!("--- Simulating a valid request ---");
    let req1 = Request {
        method: "GET".to_string(),
        path: "/api/user".to_string(),
        headers: HashMap::new(),
        client_ip: "127.0.0.1".to_string(),
    };
    let res1 = block_on(app.handle(req1));
    println!("Response: {:?}\n", res1);

    println!("--- Simulating a 404 request ---");
    let req2 = Request {
        method: "POST".to_string(),
        path: "/api/user".to_string(),
        headers: HashMap::new(),
        client_ip: "127.0.0.1".to_string(),
    };
    let res2 = block_on(app.handle(req2));
    println!("Response: {:?}\n", res2);
    
    println!("--- Simulating rate limited requests ---");
    for i in 0..6 {
        let req = Request {
            method: "GET".to_string(),
            path: "/api/user".to_string(),
            headers: HashMap::new(),
            client_ip: "192.168.1.5".to_string(),
        };
        let res = block_on(app.handle(req));
        println!("Request #{}: Status {}", i + 1, res.status);
    }
}