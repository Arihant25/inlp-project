use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, Duration};

// --- Domain Models ---

#[derive(Debug, Clone, PartialEq)]
enum Role { ADMIN, USER }

#[derive(Debug, Clone)]
struct User {
    id: String,
    email: String,
    password_hash: String,
    role: Role,
    is_active: bool,
}

#[derive(Debug, Clone, PartialEq)]
enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug, Clone)]
struct Post {
    id: String,
    user_id: String,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Stateful Session Management ---

#[derive(Debug, Clone)]
struct SessionData {
    user_id: String,
    user_role: Role,
    created_at: SystemTime,
}

impl SessionData {
    fn is_expired(&self) -> bool {
        self.created_at.elapsed().unwrap_or(Duration::from_secs(9999)) > Duration::from_secs(3600) // 1 hour expiry
    }
}

// The SessionManager holds the state of all active sessions.
// It's wrapped in Arc<Mutex<>> for safe concurrent access.
type SessionStore = Arc<Mutex<HashMap<String, SessionData>>>;

struct SessionManager {
    store: SessionStore,
}

impl SessionManager {
    fn new() -> Self {
        SessionManager {
            store: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    fn create_session(&self, user: &User) -> String {
        let session_id = Self::generate_session_id();
        let session_data = SessionData {
            user_id: user.id.clone(),
            user_role: user.role.clone(),
            created_at: SystemTime::now(),
        };
        
        let mut store = self.store.lock().unwrap();
        store.insert(session_id.clone(), session_data);
        session_id
    }

    fn get_session(&self, session_id: &str) -> Option<SessionData> {
        let store = self.store.lock().unwrap();
        match store.get(session_id) {
            Some(data) if !data.is_expired() => Some(data.clone()),
            _ => None,
        }
    }

    fn end_session(&self, session_id: &str) {
        let mut store = self.store.lock().unwrap();
        store.remove(session_id);
    }

    fn generate_session_id() -> String {
        // In a real app, use a cryptographically secure random string.
        let now = SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap();
        format!("session_{:?}", now.as_nanos())
    }
}

// --- Mock User Database ---
type UserDB = HashMap<String, User>;

fn create_mock_db() -> UserDB {
    let mut users = HashMap::new();
    users.insert(
        "admin@test.io".to_string(),
        User {
            id: "uuid-admin-99".to_string(),
            email: "admin@test.io".to_string(),
            password_hash: "hash:adminpass".to_string(),
            role: Role::ADMIN,
            is_active: true,
        },
    );
    users.insert(
        "user@test.io".to_string(),
        User {
            id: "uuid-user-88".to_string(),
            email: "user@test.io".to_string(),
            password_hash: "hash:userpass".to_string(),
            role: Role::USER,
            is_active: true,
        },
    );
    users
}

// --- Application Context ---
// Holds all shared state for the application.
struct AppContext {
    session_manager: SessionManager,
    user_db: UserDB,
    post_db: Arc<Mutex<HashMap<String, Post>>>,
}

// --- API-like Functions ---

// WARNING: In production, use a strong KDF like Argon2/bcrypt.
fn verify_password(password: &str, hash: &str) -> bool {
    format!("hash:{}", password) == hash
}

fn api_login(ctx: &AppContext, email: &str, password: &str) -> Result<String, &'static str> {
    let user = ctx.user_db.values().find(|u| u.email == email).ok_or("User not found")?;
    if !user.is_active { return Err("User is inactive"); }
    if !verify_password(password, &user.password_hash) { return Err("Invalid credentials"); }
    
    let session_id = ctx.session_manager.create_session(user);
    Ok(session_id)
}

fn api_create_post(ctx: &AppContext, session_id: &str, title: &str, content: &str) -> Result<Post, &'static str> {
    let session = ctx.session_manager.get_session(session_id).ok_or("Invalid or expired session")?;
    
    let new_post = Post {
        id: format!("post_{}", SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap().as_millis()),
        user_id: session.user_id,
        title: title.to_string(),
        content: content.to_string(),
        status: PostStatus::DRAFT,
    };

    let mut posts = ctx.post_db.lock().unwrap();
    posts.insert(new_post.id.clone(), new_post.clone());
    Ok(new_post)
}

// RBAC check is performed here
fn api_publish_post(ctx: &AppContext, session_id: &str, post_id: &str) -> Result<(), &'static str> {
    let session = ctx.session_manager.get_session(session_id).ok_or("Invalid or expired session")?;
    
    if session.user_role != Role::ADMIN {
        return Err("Access Denied: You must be an ADMIN to publish posts.");
    }

    let mut posts = ctx.post_db.lock().unwrap();
    let post = posts.get_mut(post_id).ok_or("Post not found")?;
    post.status = PostStatus::PUBLISHED;
    Ok(())
}

// Mock OAuth2 Client
struct OAuth2Client;
impl OAuth2Client {
    fn get_redirect_url() -> &'static str { "https://my-provider.com/auth?app=123" }
    fn process_callback(code: &str) -> Result<String, &'static str> {
        if code == "oauth_code_xyz" { Ok("oauth_user@provider.com".to_string()) } else { Err("Bad code") }
    }
}

fn main() {
    let app_context = AppContext {
        session_manager: SessionManager::new(),
        user_db: create_mock_db(),
        post_db: Arc::new(Mutex::new(HashMap::new())),
    };

    println!("--- 1. Login and Session Creation ---");
    let user_session_id = api_login(&app_context, "user@test.io", "userpass").expect("User login failed");
    println!("User logged in, session ID: {}", user_session_id);
    let admin_session_id = api_login(&app_context, "admin@test.io", "adminpass").expect("Admin login failed");
    println!("Admin logged in, session ID: {}", admin_session_id);

    println!("\n--- 2. User Creates a Post ---");
    let post = api_create_post(&app_context, &user_session_id, "My Day", "It was a good day.").unwrap();
    println!("User created post with ID: {}", post.id);

    println!("\n--- 3. RBAC Check: User Tries to Publish (Fails) ---");
    let user_publish_result = api_publish_post(&app_context, &user_session_id, &post.id);
    assert!(user_publish_result.is_err());
    println!("User publish attempt failed as expected: {}", user_publish_result.unwrap_err());

    println!("\n--- 4. RBAC Check: Admin Publishes Post (Succeeds) ---");
    api_publish_post(&app_context, &admin_session_id, &post.id).unwrap();
    println!("Admin successfully published the post.");
    let posts_db = app_context.post_db.lock().unwrap();
    println!("Post status is now: {:?}", posts_db.get(&post.id).unwrap().status);
    
    println!("\n--- 5. Session Management ---");
    app_context.session_manager.end_session(&user_session_id);
    let session_lookup = app_context.session_manager.get_session(&user_session_id);
    assert!(session_lookup.is_none());
    println!("User session ended and cannot be retrieved.");

    println!("\n--- 6. OAuth2 Simulation ---");
    println!("Redirect user to: {}", OAuth2Client::get_redirect_url());
    let oauth_email = OAuth2Client::process_callback("oauth_code_xyz").unwrap();
    println!("OAuth callback processed, got email: {}", oauth_email);
}