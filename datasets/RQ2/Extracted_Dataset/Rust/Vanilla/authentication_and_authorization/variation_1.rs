use std::collections::HashMap;
use std::time::{SystemTime, UNIX_EPOCH};

// --- DOMAIN MODELS ---

#[derive(Debug, Clone, PartialEq)]
enum Role {
    ADMIN,
    USER,
}

#[derive(Debug, Clone)]
#[allow(dead_code)]
struct User {
    id: String,
    email: String,
    password_hash: String,
    role: Role,
    is_active: bool,
    created_at: u64,
}

#[derive(Debug, Clone, PartialEq)]
#[allow(dead_code)]
enum PostStatus {
    DRAFT,
    PUBLISHED,
}

#[derive(Debug, Clone)]
#[allow(dead_code)]
struct Post {
    id: String,
    user_id: String,
    title: String,
    content: String,
    status: PostStatus,
}

// --- MOCK DATABASE ---

struct Database {
    users: HashMap<String, User>,
    posts: HashMap<String, Post>,
}

impl Database {
    fn new() -> Self {
        let mut users = HashMap::new();
        let admin_id = "a1b2c3d4-admin".to_string();
        let user_id = "e5f6g7h8-user".to_string();

        users.insert(
            "admin@example.com".to_string(),
            User {
                id: admin_id.clone(),
                email: "admin@example.com".to_string(),
                password_hash: auth::hash_password("admin123"),
                role: Role::ADMIN,
                is_active: true,
                created_at: current_timestamp(),
            },
        );
        users.insert(
            "user@example.com".to_string(),
            User {
                id: user_id.clone(),
                email: "user@example.com".to_string(),
                password_hash: auth::hash_password("user123"),
                role: Role::USER,
                is_active: true,
                created_at: current_timestamp(),
            },
        );
        Database {
            users,
            posts: HashMap::new(),
        }
    }
}

// --- UTILITY FUNCTIONS ---

fn current_timestamp() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("Time went backwards")
        .as_secs()
}

fn generate_id(prefix: &str) -> String {
    format!("{}-{}", prefix, current_timestamp())
}

// --- AUTHENTICATION & AUTHORIZATION MODULE ---

mod auth {
    use super::{Role, User};

    // WARNING: In production, use a strong KDF like Argon2/bcrypt from a reputable crate.
    // This mock is for demonstration purposes due to std-lib-only constraints.
    pub fn hash_password(password: &str) -> String {
        format!("hashed:{}", password)
    }

    pub fn verify_password(password: &str, hash: &str) -> bool {
        hash == hash_password(password)
    }

    // WARNING: This is a mock JWT implementation. A real one would use HMAC-SHA256
    // and proper Base64URL encoding from a crypto library.
    pub mod jwt {
        use super::super::Role;

        pub fn create_token(user_id: &str, role: &Role, secret: &str) -> Result<String, &'static str> {
            let role_str = match role {
                Role::ADMIN => "ADMIN",
                Role::USER => "USER",
            };
            let header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
            let payload = format!("{{\"sub\":\"{}\",\"role\":\"{}\"}}", user_id, role_str);
            let signature = format!("{}.{}.{}", header, payload, secret); // Mock signature
            Ok(signature)
        }

        pub fn validate_token(token: &str, secret: &str) -> Result<(String, Role), &'static str> {
            let parts: Vec<&str> = token.split('.').collect();
            if parts.len() != 3 { return Err("Invalid token format"); }
            
            let expected_signature = format!("{}.{}.{}", parts[0], parts[1], secret);
            if parts[2] != expected_signature { return Err("Invalid signature"); }

            // Mock payload parsing
            let payload = parts[1];
            let sub_part = payload.split(',').find(|s| s.contains("\"sub\""));
            let role_part = payload.split(',').find(|s| s.contains("\"role\""));

            if let (Some(sub_str), Some(role_str)) = (sub_part, role_part) {
                let user_id = sub_str.split(':').nth(1).unwrap_or("").replace('"', "");
                let role_val = role_str.split(':').nth(1).unwrap_or("").replace(['"', '}'], "");
                let role = match role_val.as_str() {
                    "ADMIN" => Role::ADMIN,
                    "USER" => Role::USER,
                    _ => return Err("Invalid role in token"),
                };
                Ok((user_id, role))
            } else {
                Err("Invalid payload")
            }
        }
    }

    // --- RBAC ---
    pub fn require_role(user_role: &Role, required_role: Role) -> Result<(), &'static str> {
        if *user_role == required_role || *user_role == Role::ADMIN {
            Ok(())
        } else {
            Err("Permission denied: Insufficient role")
        }
    }
}

// --- OAUTH2 MOCK ---
mod oauth2 {
    pub struct Client {
        client_id: String,
        redirect_uri: String,
    }

    impl Client {
        pub fn new(client_id: &str, redirect_uri: &str) -> Self {
            Self { client_id: client_id.to_string(), redirect_uri: redirect_uri.to_string() }
        }

        pub fn get_authorization_url(&self) -> String {
            format!("https://oauth.provider.com/auth?client_id={}&redirect_uri={}", self.client_id, self.redirect_uri)
        }

        pub fn exchange_code_for_user_email(&self, code: &str) -> Result<String, &'static str> {
            if code == "valid_oauth_code" {
                Ok("oauth_user@example.com".to_string())
            } else {
                Err("Invalid OAuth2 code")
            }
        }
    }
}

// --- APPLICATION LOGIC / SERVICES ---

fn login_user<'a>(db: &'a Database, email: &str, password: &str, secret: &str) -> Result<String, &'static str> {
    let user = db.users.get(email).ok_or("User not found")?;
    if !user.is_active { return Err("User account is inactive"); }
    if !auth::verify_password(password, &user.password_hash) { return Err("Invalid credentials"); }
    
    auth::jwt::create_token(&user.id, &user.role, secret)
}

fn create_post(db: &mut Database, token: &str, secret: &str, title: String, content: String) -> Result<Post, &'static str> {
    let (user_id, _user_role) = auth::jwt::validate_token(token, secret)?;
    
    let new_post = Post {
        id: generate_id("post"),
        user_id,
        title,
        content,
        status: PostStatus::DRAFT,
    };
    
    db.posts.insert(new_post.id.clone(), new_post.clone());
    Ok(new_post)
}

fn publish_post(db: &mut Database, token: &str, secret: &str, post_id: &str) -> Result<(), &'static str> {
    let (_user_id, user_role) = auth::jwt::validate_token(token, secret)?;
    auth::require_role(&user_role, Role::ADMIN)?;

    let post = db.posts.get_mut(post_id).ok_or("Post not found")?;
    post.status = PostStatus::PUBLISHED;
    Ok(())
}


// --- MAIN ---

fn main() {
    let mut db = Database::new();
    let jwt_secret = "my-super-secret-key-that-is-long";

    println!("--- 1. User Login ---");
    let login_result = login_user(&db, "user@example.com", "user123", jwt_secret);
    let user_token = match login_result {
        Ok(token) => {
            println!("User login successful. Token: {}", token);
            token
        }
        Err(e) => {
            println!("User login failed: {}", e);
            return;
        }
    };

    println!("\n--- 2. Admin Login ---");
    let admin_token = login_user(&db, "admin@example.com", "admin123", jwt_secret).unwrap();
    println!("Admin login successful.");

    println!("\n--- 3. User creates a post (Success) ---");
    let post = create_post(&mut db, &user_token, jwt_secret, "My First Post".to_string(), "Hello World!".to_string()).unwrap();
    println!("Post created: {:?}", post);

    println!("\n--- 4. User tries to publish a post (Failure) ---");
    let publish_result = publish_post(&mut db, &user_token, jwt_secret, &post.id);
    match publish_result {
        Ok(_) => println!("This should not happen!"),
        Err(e) => println!("As expected, user failed to publish: {}", e),
    }

    println!("\n--- 5. Admin publishes the post (Success) ---");
    publish_post(&mut db, &admin_token, jwt_secret, &post.id).unwrap();
    println!("Post published successfully. New status: {:?}", db.posts.get(&post.id).unwrap().status);
    
    println!("\n--- 6. OAuth2 Flow Simulation ---");
    let oauth_client = oauth2::Client::new("my-client-id", "http://localhost/callback");
    println!("Authorization URL: {}", oauth_client.get_authorization_url());
    let user_email = oauth_client.exchange_code_for_user_email("valid_oauth_code").unwrap();
    println!("OAuth user email retrieved: {}", user_email);
}