use std::collections::HashMap;
use std::time::{SystemTime, UNIX_EPOCH};

// --- Core Abstractions (Traits) ---

// Represents an entity that can perform actions (an "actor").
trait Authorizable {
    fn get_id(&self) -> &str;
    fn get_role(&self) -> &Role;
}

// Represents an action that can be performed.
enum Action {
    Create,
    Read,
    Update,
    Delete,
    Publish,
}

// Represents a resource that actions can be performed on.
trait Resource {
    fn get_owner_id(&self) -> &str;
}

// --- Domain Model Implementation ---

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
enum Role {
    ADMIN,
    USER,
}

#[derive(Debug, Clone)]
struct User {
    id: String,
    email: String,
    password_hash: String,
    role: Role,
    is_active: bool,
    created_at: u64,
}

impl Authorizable for User {
    fn get_id(&self) -> &str { &self.id }
    fn get_role(&self) -> &Role { &self.role }
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

impl Resource for Post {
    fn get_owner_id(&self) -> &str { &self.user_id }
}

// --- Generic Permission Service ---

struct PermissionService;

impl PermissionService {
    // A generic function to check permissions based on traits.
    fn can_perform<A: Authorizable, R: Resource>(actor: &A, action: &Action, resource: &R) -> Result<(), String> {
        match action {
            Action::Create => Ok(()), // Anyone can create
            Action::Read => Ok(()),   // Anyone can read
            Action::Update | Action::Delete => {
                // Only owner or admin can update/delete
                if actor.get_id() == resource.get_owner_id() || *actor.get_role() == Role::ADMIN {
                    Ok(())
                } else {
                    Err("Permission Denied: Actor must be the owner or an admin.".to_string())
                }
            }
            Action::Publish => {
                // Only admin can publish
                if *actor.get_role() == Role::ADMIN {
                    Ok(())
                } else {
                    Err("Permission Denied: Only admins can publish.".to_string())
                }
            }
        }
    }
}

// --- Authentication Logic ---

// WARNING: This is a mock implementation due to std-lib-only constraints.
// Use a proper crypto library in production.
mod security {
    pub fn hash_password(password: &str) -> String {
        let salt = "static_salt"; // In reality, use a unique salt per user
        format!("hashed:{}:{}", salt, password)
    }

    pub fn verify_password(password: &str, stored_hash: &str) -> bool {
        stored_hash == hash_password(password)
    }

    // Mock JWT Token
    pub struct Token {
        pub raw: String,
    }

    impl Token {
        pub fn new(user_id: &str, role: &super::Role) -> Self {
            let role_str = format!("{:?}", role);
            // Mocking: In reality, this would be a signed, Base64Url-encoded structure.
            let raw = format!("header.{}__{}.signature", user_id, role_str);
            Token { raw }
        }

        pub fn validate_and_get_principal(token_str: &str) -> Result<(String, super::Role), String> {
            let parts: Vec<&str> = token_str.split('.').collect();
            if parts.len() != 3 { return Err("Invalid token structure".to_string()); }
            
            let payload_parts: Vec<&str> = parts[1].split("__").collect();
            if payload_parts.len() != 2 { return Err("Invalid payload structure".to_string()); }

            let user_id = payload_parts[0].to_string();
            let role = match payload_parts[1] {
                "ADMIN" => super::Role::ADMIN,
                "USER" => super::Role::USER,
                _ => return Err("Unknown role in token".to_string()),
            };
            Ok((user_id, role))
        }
    }
}

// --- Mock Data Store ---
struct Store {
    users: HashMap<String, User>,
    posts: HashMap<String, Post>,
}

impl Store {
    fn new() -> Self {
        let mut users = HashMap::new();
        let admin = User {
            id: "uuid-admin-1".to_string(),
            email: "admin@corp.com".to_string(),
            password_hash: security::hash_password("secure_admin_pass"),
            role: Role::ADMIN, is_active: true, created_at: 0
        };
        let user = User {
            id: "uuid-user-2".to_string(),
            email: "user@corp.com".to_string(),
            password_hash: security::hash_password("secure_user_pass"),
            role: Role::USER, is_active: true, created_at: 0
        };
        users.insert(admin.id.clone(), admin);
        users.insert(user.id.clone(), user);
        Self { users, posts: HashMap::new() }
    }
    fn find_user_by_email(&self, email: &str) -> Option<&User> {
        self.users.values().find(|u| u.email == email)
    }
}

// --- OAuth2 Client Mock ---
struct OAuth2Client;
impl OAuth2Client {
    fn exchange_code_for_principal_id(code: &str) -> Result<String, String> {
        if code == "valid_oauth_code" { Ok("oauth-user-id-from-provider".to_string()) } else { Err("Invalid code".to_string()) }
    }
}

// --- Main Application Flow ---
fn main() {
    let mut store = Store::new();

    // 1. Login
    println!("--- 1. Login Process ---");
    let user_to_login = store.find_user_by_email("user@corp.com").unwrap();
    let login_success = security::verify_password("secure_user_pass", &user_to_login.password_hash);
    assert!(login_success);
    let user_token = security::Token::new(user_to_login.get_id(), user_to_login.get_role());
    println!("User login successful. Token: {}", user_token.raw);

    let admin_to_login = store.find_user_by_email("admin@corp.com").unwrap();
    let admin_token = security::Token::new(admin_to_login.get_id(), admin_to_login.get_role());
    println!("Admin login successful.");

    // 2. Create a resource
    println!("\n--- 2. Resource Creation ---");
    let (user_id, _) = security::Token::validate_and_get_principal(&user_token.raw).unwrap();
    let author = store.users.get(&user_id).unwrap();
    let mut post = Post {
        id: "post-123".to_string(),
        user_id: author.get_id().to_string(),
        title: "A Post by a User".to_string(),
        content: "...".to_string(),
        status: PostStatus::DRAFT,
    };
    store.posts.insert(post.id.clone(), post.clone());
    println!("Post '{}' created by user '{}'", post.title, author.email);

    // 3. RBAC checks using the generic PermissionService
    println!("\n--- 3. RBAC Checks ---");
    let user_actor = store.users.get(user_to_login.get_id()).unwrap();
    let admin_actor = store.users.get(admin_to_login.get_id()).unwrap();
    let resource_to_check = store.posts.get("post-123").unwrap();

    // User tries to publish -> Fail
    let res = PermissionService::can_perform(user_actor, &Action::Publish, resource_to_check);
    println!("User publishing post? -> {}", res.is_ok());
    assert!(res.is_err());

    // Admin tries to publish -> Success
    let res = PermissionService::can_perform(admin_actor, &Action::Publish, resource_to_check);
    println!("Admin publishing post? -> {}", res.is_ok());
    assert!(res.is_ok());
    if res.is_ok() {
        post.status = PostStatus::PUBLISHED;
        println!("Post status updated to: {:?}", post.status);
    }
    
    // 4. OAuth2 Simulation
    println!("\n--- 4. OAuth2 Simulation ---");
    let oauth_id = OAuth2Client::exchange_code_for_principal_id("valid_oauth_code").unwrap();
    println!("Successfully exchanged OAuth code for principal ID: {}", oauth_id);
}