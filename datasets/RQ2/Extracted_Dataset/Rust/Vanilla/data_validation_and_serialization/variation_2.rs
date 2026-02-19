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

#[derive(Debug, Clone)]
enum UserRole { ADMIN, USER }
#[derive(Debug, Clone)]
enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug)]
struct ValidationError {
    field: String,
    message: String,
}

impl fmt::Display for ValidationError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Field '{}': {}", self.field, self.message)
    }
}

// --- Entity 1: User (OOP Style) ---
#[derive(Debug, Clone)]
struct User {
    id: Uuid,
    email: String,
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: u64,
}

impl User {
    pub fn new(email: String, password_hash: String, role: UserRole) -> Self {
        User {
            id: Uuid::new(),
            email,
            password_hash,
            role,
            is_active: true,
            created_at: SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs(),
        }
    }

    pub fn validate(&self) -> Result<(), Vec<ValidationError>> {
        let mut errors = Vec::new();
        if self.email.trim().is_empty() {
            errors.push(ValidationError { field: "email".to_string(), message: "is required".to_string() });
        } else {
            // Custom email validator
            let is_valid = self.email.contains('@') && self.email.split('@').count() == 2 && self.email.split('@').last().unwrap_or("").contains('.');
            if !is_valid {
                errors.push(ValidationError { field: "email".to_string(), message: "is not a valid format".to_string() });
            }
        }
        if self.password_hash.is_empty() {
            errors.push(ValidationError { field: "password_hash".to_string(), message: "is required".to_string() });
        }

        if errors.is_empty() { Ok(()) } else { Err(errors) }
    }

    pub fn to_json_string(&self) -> String {
        format!(
            r#"{{"id":"{}","email":"{}","password_hash":"{}","role":"{:?}","is_active":{},"created_at":{}}}"#,
            self.id, self.email, self.password_hash, self.role, self.is_active, self.created_at
        )
    }

    pub fn from_json_string(json_str: &str) -> Result<Self, String> {
        let get_val = |key: &str| -> Option<String> {
            json_str.split(&format!(r#""{}":"#, key)).nth(1)
                .and_then(|v| v.split(|c| c == ',' || c == '}').next())
                .map(|s| s.trim().trim_matches('"').to_string())
        };

        let id = Uuid::from_str(&get_val("id").ok_or("id missing")?)?;
        let email = get_val("email").ok_or("email missing")?;
        let password_hash = get_val("password_hash").ok_or("password_hash missing")?;
        let role = match get_val("role").ok_or("role missing")?.as_str() {
            "ADMIN" => UserRole::ADMIN,
            "USER" => UserRole::USER,
            _ => return Err("Invalid role".to_string()),
        };
        let is_active = bool::from_str(&get_val("is_active").ok_or("is_active missing")?).map_err(|e| e.to_string())?;
        let created_at = u64::from_str(&get_val("created_at").ok_or("created_at missing")?).map_err(|e| e.to_string())?;

        Ok(User { id, email, password_hash, role, is_active, created_at })
    }
}

// --- Entity 2: Post (OOP Style) ---
#[derive(Debug, Clone)]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

impl Post {
    pub fn to_xml_string(&self) -> String {
        format!(
            "<post><id>{}</id><user_id>{}</user_id><title>{}</title><content>{}</content><status>{:?}</status></post>",
            self.id, self.user_id, self.title, self.content, self.status
        )
    }

    pub fn from_xml_string(xml_str: &str) -> Result<Self, String> {
        let get_tag_val = |tag: &str| -> Option<String> {
            xml_str.split(&format!("<{}>", tag)).nth(1)
                .and_then(|v| v.split(&format!("</{}>", tag)).next())
                .map(|s| s.to_string())
        };

        let id = Uuid::from_str(&get_tag_val("id").ok_or("id tag missing")?)?;
        let user_id = Uuid::from_str(&get_tag_val("user_id").ok_or("user_id tag missing")?)?;
        let title = get_tag_val("title").ok_or("title tag missing")?;
        let content = get_tag_val("content").ok_or("content tag missing")?;
        let status = match get_tag_val("status").ok_or("status tag missing")?.as_str() {
            "DRAFT" => PostStatus::DRAFT,
            "PUBLISHED" => PostStatus::PUBLISHED,
            _ => return Err("Invalid status".to_string()),
        };

        Ok(Post { id, user_id, title, content, status })
    }
}

fn main() {
    println!("--- Variation 2: OOP/Struct-based Style ---");
    
    // --- User Example ---
    let user = User::new("test@example.com".to_string(), "hash123".to_string(), UserRole::USER);
    match user.validate() {
        Ok(_) => {
            println!("User validation successful.");
            let json_data = user.to_json_string();
            println!("User JSON: {}", json_data);
            match User::from_json_string(&json_data) {
                Ok(parsed_user) => println!("Parsed User: {:?}", parsed_user),
                Err(e) => println!("Error parsing user from JSON: {}", e),
            }
        },
        Err(errors) => {
            println!("User validation failed:");
            for error in errors {
                println!("- {}", error);
            }
        }
    }

    // --- Post Example ---
    let post = Post {
        id: Uuid::new(),
        user_id: user.id,
        title: "A Valid Title".to_string(),
        content: "Some valid content.".to_string(),
        status: PostStatus::PUBLISHED,
    };
    let xml_data = post.to_xml_string();
    println!("\nPost XML: {}", xml_data);
    match Post::from_xml_string(&xml_data) {
        Ok(parsed_post) => println!("Parsed Post: {:?}", parsed_post),
        Err(e) => println!("Error parsing post from XML: {}", e),
    }
}