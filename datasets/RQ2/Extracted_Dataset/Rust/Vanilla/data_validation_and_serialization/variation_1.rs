use std::time::{SystemTime, UNIX_EPOCH};
use std::str::FromStr;
use std::fmt;

// --- Mock/Vanilla UUID Implementation ---
#[derive(Debug, PartialEq, Eq, Clone, Copy, Default)]
struct Uuid([u8; 16]);

impl Uuid {
    fn new() -> Self {
        // In a real app, this would be random. For demonstration, we use a counter.
        // This is a simplified, non-standard way to get unique-ish IDs for the example.
        let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let bytes = now.to_le_bytes();
        let mut uuid_bytes = [0u8; 16];
        uuid_bytes[..bytes.len()].copy_from_slice(&bytes);
        Uuid(uuid_bytes)
    }
}

impl fmt::Display for Uuid {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let hex_chars: Vec<String> = self.0.iter().map(|b| format!("{:02x}", b)).collect();
        write!(f, "{}", hex_chars.join(""))
    }
}

impl FromStr for Uuid {
    type Err = String;
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        if s.len() != 32 {
            return Err("Invalid UUID string length".to_string());
        }
        let mut bytes = [0u8; 16];
        for i in 0..16 {
            let hex_pair = &s[i*2..i*2+2];
            bytes[i] = u8::from_str_radix(hex_pair, 16).map_err(|e| e.to_string())?;
        }
        Ok(Uuid(bytes))
    }
}

// --- Domain Model ---
#[derive(Debug, Clone)]
enum UserRole { ADMIN, USER }
#[derive(Debug, Clone)]
enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug, Clone)]
struct User {
    id: Uuid,
    email: String,
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: u64, // Unix timestamp
}

#[derive(Debug, Clone)]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Validation Functions ---
fn validate_email(email: &str) -> bool {
    if let Some(at_pos) = email.find('@') {
        if let Some(dot_pos) = email[at_pos..].find('.') {
            return at_pos > 0 && dot_pos > 1 && (at_pos + dot_pos + 1) < email.len();
        }
    }
    false
}

fn validate_user_data(email: &str, pass_hash: &str) -> Result<(), Vec<String>> {
    let mut errors = Vec::new();
    if email.trim().is_empty() {
        errors.push("Email is a required field.".to_string());
    } else if !validate_email(email) {
        errors.push("Email format is invalid.".to_string());
    }
    if pass_hash.trim().is_empty() {
        errors.push("Password hash is a required field.".to_string());
    }
    if errors.is_empty() { Ok(()) } else { Err(errors) }
}

fn validate_post_data(title: &str, content: &str) -> Result<(), Vec<String>> {
    let mut errors = Vec::new();
    if title.trim().is_empty() {
        errors.push("Title is a required field.".to_string());
    }
    if content.trim().is_empty() {
        errors.push("Content is a required field.".to_string());
    }
    if errors.is_empty() { Ok(()) } else { Err(errors) }
}

// --- Serialization / Deserialization Functions ---

// JSON
fn serialize_user_to_json(user: &User) -> String {
    format!(
        r#"{{"id":"{}","email":"{}","password_hash":"{}","role":"{:?}","is_active":{},"created_at":{}}}"#,
        user.id, user.email, user.password_hash, user.role, user.is_active, user.created_at
    )
}

fn deserialize_user_from_json(json_str: &str) -> Result<User, String> {
    // NOTE: This is a very basic, non-robust parser for demonstration purposes.
    let get_val = |key: &str| -> Option<String> {
        json_str.split(&format!(r#""{}":"#, key)).nth(1)
            .and_then(|v| v.split(',').next())
            .map(|s| s.trim().trim_matches(|p| p == '"' || p == '}' ).to_string())
    };

    let id = Uuid::from_str(&get_val("id").ok_or("id missing")?)?;
    let email = get_val("email").ok_or("email missing")?;
    let password_hash = get_val("password_hash").ok_or("password_hash missing")?;
    let role_str = get_val("role").ok_or("role missing")?;
    let role = match role_str.as_str() {
        "ADMIN" => UserRole::ADMIN,
        "USER" => UserRole::USER,
        _ => return Err("Invalid role".to_string()),
    };
    let is_active = bool::from_str(&get_val("is_active").ok_or("is_active missing")?).map_err(|e| e.to_string())?;
    let created_at = u64::from_str(&get_val("created_at").ok_or("created_at missing")?).map_err(|e| e.to_string())?;

    Ok(User { id, email, password_hash, role, is_active, created_at })
}

// XML
fn serialize_post_to_xml(post: &Post) -> String {
    format!(
        "<post><id>{}</id><user_id>{}</user_id><title>{}</title><content>{}</content><status>{:?}</status></post>",
        post.id, post.user_id, post.title, post.content, post.status
    )
}

fn deserialize_post_from_xml(xml_str: &str) -> Result<Post, String> {
    // NOTE: A very basic, non-robust parser.
    let get_tag_val = |tag: &str| -> Option<String> {
        xml_str.split(&format!("<{}>", tag)).nth(1)
            .and_then(|v| v.split(&format!("</{}>", tag)).next())
            .map(|s| s.to_string())
    };

    let id = Uuid::from_str(&get_tag_val("id").ok_or("id tag missing")?)?;
    let user_id = Uuid::from_str(&get_tag_val("user_id").ok_or("user_id tag missing")?)?;
    let title = get_tag_val("title").ok_or("title tag missing")?;
    let content = get_tag_val("content").ok_or("content tag missing")?;
    let status_str = get_tag_val("status").ok_or("status tag missing")?;
    let status = match status_str.as_str() {
        "DRAFT" => PostStatus::DRAFT,
        "PUBLISHED" => PostStatus::PUBLISHED,
        _ => return Err("Invalid status".to_string()),
    };

    Ok(Post { id, user_id, title, content, status })
}

fn main() {
    println!("--- Variation 1: Procedural/Functional Style ---");

    // --- User Validation and Serialization ---
    let user_email = "test@example.com";
    let user_pass = "hashed_password_123";
    match validate_user_data(user_email, user_pass) {
        Ok(_) => {
            println!("User data is valid.");
            let user = User {
                id: Uuid::new(),
                email: user_email.to_string(),
                password_hash: user_pass.to_string(),
                role: UserRole::USER,
                is_active: true,
                created_at: SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs(),
            };
            
            let json_user = serialize_user_to_json(&user);
            println!("Serialized User (JSON): {}", json_user);

            match deserialize_user_from_json(&json_user) {
                Ok(deserialized_user) => println!("Deserialized User (JSON): {:?}", deserialized_user),
                Err(e) => println!("JSON Deserialization Error: {}", e),
            }
        },
        Err(errors) => println!("User validation failed: {:?}", errors),
    }

    println!("\n--- Post Validation and Serialization ---");
    let post_title = "My First Post";
    let post_content = "Hello, Rust!";
    match validate_post_data(post_title, post_content) {
        Ok(_) => {
            println!("Post data is valid.");
            let post = Post {
                id: Uuid::new(),
                user_id: Uuid::new(),
                title: post_title.to_string(),
                content: post_content.to_string(),
                status: PostStatus::DRAFT,
            };

            let xml_post = serialize_post_to_xml(&post);
            println!("Serialized Post (XML): {}", xml_post);

            match deserialize_post_from_xml(&xml_post) {
                Ok(deserialized_post) => println!("Deserialized Post (XML): {:?}", deserialized_post),
                Err(e) => println!("XML Deserialization Error: {}", e),
            }
        },
        Err(errors) => println!("Post validation failed: {:?}", errors),
    }
}