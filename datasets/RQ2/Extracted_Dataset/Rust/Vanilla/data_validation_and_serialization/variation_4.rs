use std::time::{SystemTime, UNIX_EPOCH};
use std::str::FromStr;
use std::fmt::{self, Debug};

// --- Core Abstractions (Traits) ---
trait Validatable {
    fn validate(&self) -> Result<(), Vec<String>>;
}

trait JsonSerializable {
    fn to_json(&self) -> String;
}

trait XmlSerializable {
    fn to_xml(&self) -> String;
}

trait FromJson: Sized {
    fn from_json(s: &str) -> Result<Self, String>;
}

trait FromXml: Sized {
    fn from_xml(s: &str) -> Result<Self, String>;
}

// --- Shared Components ---
#[derive(Debug, PartialEq, Eq, Clone, Copy, Default)]
struct Uuid([u8; 16]);

impl Uuid {
    fn new() -> Self {
        let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let bytes = now.to_le_bytes();
        let mut uuid_bytes = [0u8; 16];
        uuid_bytes[..bytes.len()].copy_from_slice(&bytes);
        Uuid(uuid_bytes)
    }
}

impl fmt::Display for Uuid {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0.iter().map(|b| format!("{:02x}", b)).collect::<String>())
    }
}

impl FromStr for Uuid {
    type Err = String;
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        if s.len() != 32 { return Err("Invalid UUID string length".to_string()); }
        let mut bytes = [0u8; 16];
        for i in 0..16 {
            bytes[i] = u8::from_str_radix(&s[i*2..i*2+2], 16).map_err(|e| e.to_string())?;
        }
        Ok(Uuid(bytes))
    }
}

#[derive(Debug, Clone)]
enum UserRole { ADMIN, USER }
#[derive(Debug, Clone)]
enum PostStatus { DRAFT, PUBLISHED }

// --- Entity 1: User ---
#[derive(Debug, Clone)]
struct User {
    id: Uuid,
    email: String,
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: u64,
}

impl Validatable for User {
    fn validate(&self) -> Result<(), Vec<String>> {
        let mut errors = Vec::new();
        if self.email.trim().is_empty() {
            errors.push("email: required".to_string());
        } else if !self.email.contains('@') || !self.email.contains('.') {
            errors.push("email: invalid format".to_string());
        }
        if self.password_hash.is_empty() {
            errors.push("password_hash: required".to_string());
        }
        if errors.is_empty() { Ok(()) } else { Err(errors) }
    }
}

impl JsonSerializable for User {
    fn to_json(&self) -> String {
        format!(
            r#"{{"id":"{}","email":"{}","password_hash":"{}","role":"{:?}","is_active":{},"created_at":{}}}"#,
            self.id, self.email, self.password_hash, self.role, self.is_active, self.created_at
        )
    }
}

// --- Entity 2: Post ---
#[derive(Debug, Clone)]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

impl Validatable for Post {
    fn validate(&self) -> Result<(), Vec<String>> {
        let mut errors = Vec::new();
        if self.title.trim().len() < 3 {
            errors.push("title: must be at least 3 characters".to_string());
        }
        if self.content.trim().is_empty() {
            errors.push("content: required".to_string());
        }
        if errors.is_empty() { Ok(()) } else { Err(errors) }
    }
}

impl XmlSerializable for Post {
    fn to_xml(&self) -> String {
        format!(
            "<post><id>{}</id><user_id>{}</user_id><title>{}</title><content>{}</content><status>{:?}</status></post>",
            self.id, self.user_id, self.title, self.content, self.status
        )
    }
}

// --- Generic Processor Function ---
fn process_valid_entity<T: Validatable + Debug>(entity: &T) {
    println!("\nProcessing entity: {:?}", entity);
    match entity.validate() {
        Ok(_) => println!("Validation successful!"),
        Err(e) => println!("Validation failed: {:?}", e),
    }
}

fn main() {
    println!("--- Variation 4: Trait-based/Generic Style ---");

    let user = User {
        id: Uuid::new(),
        email: "generic.user@example.com".to_string(),
        password_hash: "some_strong_hash".to_string(),
        role: UserRole::USER,
        is_active: false,
        created_at: SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs(),
    };

    let post = Post {
        id: Uuid::new(),
        user_id: user.id,
        title: "Traits in Rust".to_string(),
        content: "They are powerful for abstraction.".to_string(),
        status: PostStatus::DRAFT,
    };
    
    let invalid_post = Post {
        id: Uuid::new(),
        user_id: user.id,
        title: "A".to_string(),
        content: "".to_string(),
        status: PostStatus::DRAFT,
    };

    // Demonstrate generic function
    process_valid_entity(&user);
    process_valid_entity(&post);
    process_valid_entity(&invalid_post);

    // Demonstrate serialization traits
    println!("\n--- Serialization Demo ---");
    println!("User (JSON): {}", user.to_json());
    println!("Post (XML): {}", post.to_xml());
}