use std::time::{SystemTime, UNIX_EPOCH};
use std::str::FromStr;
use std::fmt;

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

#[derive(Debug, Clone, PartialEq)]
enum UserRole { ADMIN, USER }
#[derive(Debug, Clone, PartialEq)]
enum PostStatus { DRAFT, PUBLISHED }

// --- Entity 1: User (with Builder) ---
#[derive(Debug, Clone)]
pub struct User {
    id: Uuid,
    email: String,
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: u64,
}

#[derive(Default)]
pub struct UserBuilder {
    id: Option<Uuid>,
    email: Option<String>,
    password_hash: Option<String>,
    role: Option<UserRole>,
    is_active: Option<bool>,
    created_at: Option<u64>,
}

impl UserBuilder {
    pub fn new() -> Self {
        UserBuilder::default()
    }

    pub fn email(mut self, email: &str) -> Self {
        self.email = Some(email.to_string());
        self
    }
    
    pub fn password_hash(mut self, hash: &str) -> Self {
        self.password_hash = Some(hash.to_string());
        self
    }

    pub fn role(mut self, role: UserRole) -> Self {
        self.role = Some(role);
        self
    }

    pub fn build(self) -> Result<User, Vec<String>> {
        let mut errors = Vec::new();
        
        let email = match self.email {
            Some(e) if !e.trim().is_empty() => {
                if e.contains('@') && e.matches('@').count() == 1 && e.chars().filter(|c| *c == '.').count() >= 1 {
                    Ok(e)
                } else {
                    errors.push("Email format is invalid.".to_string());
                    Err(())
                }
            },
            _ => {
                errors.push("Email is a required field.".to_string());
                Err(())
            }
        };

        let password_hash = match self.password_hash {
            Some(p) if !p.is_empty() => Ok(p),
            _ => {
                errors.push("Password hash is a required field.".to_string());
                Err(())
            }
        };

        if !errors.is_empty() {
            return Err(errors);
        }

        Ok(User {
            id: self.id.unwrap_or_else(Uuid::new),
            email: email.unwrap(),
            password_hash: password_hash.unwrap(),
            role: self.role.unwrap_or(UserRole::USER),
            is_active: self.is_active.unwrap_or(true),
            created_at: self.created_at.unwrap_or_else(|| SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs()),
        })
    }
}

// --- Entity 2: Post (with Builder) ---
#[derive(Debug, Clone)]
pub struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Serialization / Deserialization (kept separate from builders) ---
impl Post {
    pub fn to_xml(&self) -> String {
        format!(
            "<post><id>{}</id><user_id>{}</user_id><title>{}</title><content>{}</content><status>{:?}</status></post>",
            self.id, self.user_id, self.title, self.content, self.status
        )
    }
}

impl User {
    pub fn to_json(&self) -> String {
        format!(
            r#"{{"id":"{}","email":"{}","password_hash":"{}","role":"{:?}","is_active":{},"created_at":{}}}"#,
            self.id, self.email, self.password_hash, self.role, self.is_active, self.created_at
        )
    }
}

fn main() {
    println!("--- Variation 3: Builder Pattern ---");

    // --- User Creation with Builder ---
    println!("Attempting to build a valid user...");
    let user_builder_result = UserBuilder::new()
        .email("builder@example.com")
        .password_hash("secure_hash_from_builder")
        .role(UserRole::ADMIN)
        .build();

    match user_builder_result {
        Ok(user) => {
            println!("User built successfully: {:?}", user);
            println!("User as JSON: {}", user.to_json());
        },
        Err(errors) => {
            println!("Failed to build user:");
            for err in errors {
                println!("- {}", err);
            }
        }
    }

    println!("\nAttempting to build an invalid user...");
    let invalid_user_result = UserBuilder::new()
        .email("invalid-email")
        .build();
    
    if let Err(errors) = invalid_user_result {
        println!("Correctly failed to build user with errors: {:?}", errors);
    }

    // --- Post (demonstrating serialization) ---
    // For brevity, Post builder is omitted, but would follow the same pattern.
    let a_user = UserBuilder::new().email("poster@example.com").password_hash("pass").build().unwrap();
    let post = Post {
        id: Uuid::new(),
        user_id: a_user.id,
        title: "Built with Rust".to_string(),
        content: "This post was created to demonstrate XML serialization.".to_string(),
        status: PostStatus::PUBLISHED,
    };
    println!("\nPost object: {:?}", post);
    println!("Post as XML: {}", post.to_xml());
}