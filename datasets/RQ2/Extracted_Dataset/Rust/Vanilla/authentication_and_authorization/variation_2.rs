use std::collections::HashMap;
use std::time::{SystemTime, UNIX_EPOCH};

// --- Domain Schema ---

#[derive(Debug, Clone, PartialEq)]
pub enum UserRole {
    ADMIN,
    USER,
}

#[derive(Debug, Clone)]
pub struct User {
    id: String,
    email: String,
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub enum PostStatus {
    DRAFT,
    PUBLISHED,
}

#[derive(Debug, Clone)]
pub struct Post {
    id: String,
    user_id: String,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Services & Logic (OOP Style) ---

// A service for handling authentication and user management.
pub struct AuthService {
    db_users: HashMap<String, User>, // Mock user table (email -> User)
    jwt_secret: String,
}

impl AuthService {
    pub fn new(secret: String) -> Self {
        let mut db_users = HashMap::new();
        let admin_id = "uuid-admin-001".to_string();
        let user_id = "uuid-user-002".to_string();

        db_users.insert(
            "admin@example.com".to_string(),
            User {
                id: admin_id.clone(),
                email: "admin@example.com".to_string(),
                password_hash: Self::hash_password("admin_pass"),
                role: UserRole::ADMIN,
                is_active: true,
                created_at: Self::get_timestamp(),
            },
        );
        db_users.insert(
            "user@example.com".to_string(),
            User {
                id: user_id.clone(),
                email: "user@example.com".to_string(),
                password_hash: Self::hash_password("user_pass"),
                role: UserRole::USER,
                is_active: true,
                created_at: Self::get_timestamp(),
            },
        );
        
        AuthService { db_users, jwt_secret: secret }
    }

    // WARNING: In production, use a strong KDF like Argon2/bcrypt from a reputable crate.
    fn hash_password(password: &str) -> String {
        format!("secure_hash::{}", password)
    }

    fn verify_password(password: &str, hash: &str) -> bool {
        hash == Self::hash_password(password)
    }

    pub fn login(&self, email: &str, password: &str) -> Result<String, String> {
        let user = self.db_users.get(email).ok_or_else(|| "User not found".to_string())?;
        
        if !user.is_active {
            return Err("Account is disabled".to_string());
        }

        if !Self::verify_password(password, &user.password_hash) {
            return Err("Invalid password".to_string());
        }

        self.generate_jwt(user)
    }

    // WARNING: This is a mock JWT implementation. A real one would use a proper crypto library.
    fn generate_jwt(&self, user: &User) -> Result<String, String> {
        let role_str = match user.role {
            UserRole::ADMIN => "ADMIN",
            UserRole::USER => "USER",
        };
        // Mocking Base64URL encoding and HMAC-SHA256 signing
        let header = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"; // b64("...")
        let payload = format!("{{\"sub\":\"{}\",\"role\":\"{}\"}}", user.id, role_str);
        let signature = format!("{}.{}.{}", header, payload, self.jwt_secret);
        Ok(signature)
    }
    
    pub fn get_user_from_token(&self, token: &str) -> Result<&User, String> {
        let parts: Vec<&str> = token.split('.').collect();
        if parts.len() != 3 { return Err("Malformed token".to_string()); }

        let expected_signature_base = format!("{}.{}", parts[0], parts[1]);
        if format!("{}.{}", expected_signature_base, self.jwt_secret) != token {
            return Err("Token signature invalid".to_string());
        }
        
        // Mock payload parsing
        let payload = parts[1];
        let sub_part = payload.split(',').find(|s| s.contains("\"sub\""));
        if let Some(sub_str) = sub_part {
            let user_id = sub_str.split(':').nth(1).unwrap_or("").replace('"', "");
            self.db_users.values().find(|u| u.id == user_id).ok_or_else(|| "User from token not found".to_string())
        } else {
            Err("Token payload invalid".to_string())
        }
    }

    fn get_timestamp() -> u64 {
        SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs()
    }
}

pub struct PostService {
    db_posts: HashMap<String, Post>, // Mock post table
}

impl PostService {
    pub fn new() -> Self {
        PostService { db_posts: HashMap::new() }
    }

    pub fn create_post(&mut self, author: &User, title: String, content: String) -> Post {
        let post = Post {
            id: format!("post-{}", self.db_posts.len() + 1),
            user_id: author.id.clone(),
            title,
            content,
            status: PostStatus::DRAFT,
        };
        self.db_posts.insert(post.id.clone(), post.clone());
        post
    }

    // RBAC is implemented inside the method
    pub fn publish_post(&mut self, actor: &User, post_id: &str) -> Result<&Post, String> {
        if actor.role != UserRole::ADMIN {
            return Err("Authorization failed: Only ADMINs can publish posts.".to_string());
        }
        
        let post = self.db_posts.get_mut(post_id).ok_or_else(|| "Post not found".to_string())?;
        post.status = PostStatus::PUBLISHED;
        Ok(post)
    }
}

// Mock OAuth2 Client
pub struct OAuth2Client {
    client_id: String,
}
impl OAuth2Client {
    pub fn new(client_id: &str) -> Self { Self { client_id: client_id.to_string() } }
    pub fn get_auth_link(&self) -> String { format!("https://provider.com/auth?client_id={}", self.client_id) }
    pub fn handle_callback(&self, code: &str) -> Result<String, String> {
        if code == "good_code" { Ok("oauth_user_id_123".to_string()) } else { Err("Bad code".to_string()) }
    }
}

// --- Main Execution ---

fn main() {
    let auth_service = AuthService::new("a_very_secure_secret".to_string());
    let mut post_service = PostService::new();

    println!("--- Scenario: User Login and Post Creation ---");
    let user_token = auth_service.login("user@example.com", "user_pass").expect("User login failed");
    println!("User logged in successfully.");
    
    let regular_user = auth_service.get_user_from_token(&user_token).unwrap();
    let new_post = post_service.create_post(regular_user, "My Thoughts".to_string(), "Content...".to_string());
    println!("User '{}' created post '{}'", regular_user.email, new_post.title);

    println!("\n--- Scenario: User Tries to Publish (RBAC Fail) ---");
    let publish_attempt = post_service.publish_post(regular_user, &new_post.id);
    assert!(publish_attempt.is_err());
    println!("Attempt by user to publish failed as expected: {}", publish_attempt.unwrap_err());

    println!("\n--- Scenario: Admin Login and Publish (RBAC Pass) ---");
    let admin_token = auth_service.login("admin@example.com", "admin_pass").expect("Admin login failed");
    println!("Admin logged in successfully.");
    
    let admin_user = auth_service.get_user_from_token(&admin_token).unwrap();
    let published_post = post_service.publish_post(admin_user, &new_post.id).unwrap();
    println!("Admin successfully published post. New status: {:?}", published_post.status);

    println!("\n--- Scenario: OAuth2 Simulation ---");
    let oauth_client = OAuth2Client::new("app-id-456");
    println!("OAuth Link: {}", oauth_client.get_auth_link());
    let oauth_user = oauth_client.handle_callback("good_code").unwrap();
    println!("OAuth successful, got user ID: {}", oauth_user);
}