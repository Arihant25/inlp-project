use std::collections::HashMap;
use std::time::{SystemTime, UNIX_EPOCH};

// --- Mock Database Infrastructure (Standard Library Only) ---
// This section simulates a generic SQL database connection and its components.
// In a real application, this would be replaced by a crate like `sqlx` or `postgres`.

#[derive(Debug, Clone)]
pub enum MockDbValue {
    Uuid(u128),
    Text(String),
    Boolean(bool),
    Timestamp(u64),
    Integer(i32),
}

#[derive(Debug)]
pub struct MockRow {
    columns: HashMap<String, MockDbValue>,
}

impl MockRow {
    fn get<T>(&self, column_name: &str) -> T where T: From<MockDbValue> {
        self.columns.get(column_name).cloned().unwrap().into()
    }
}

#[derive(Debug)]
pub enum DbError {
    ConnectionError,
    QueryError(String),
    NotFound,
}

// A mock connection that logs SQL queries instead of executing them.
pub struct DbConnection {
    is_in_transaction: bool,
    pub executed_queries: Vec<String>,
}

impl DbConnection {
    pub fn connect() -> Result<Self, DbError> {
        Ok(DbConnection {
            is_in_transaction: false,
            executed_queries: Vec::new(),
        })
    }

    pub fn execute(&mut self, query: &str) -> Result<u64, DbError> {
        self.executed_queries.push(query.to_string());
        println!("[EXECUTE]: {}", query);
        // Simulate rows affected
        Ok(1)
    }

    pub fn query_one(&mut self, query: &str) -> Result<MockRow, DbError> {
        self.executed_queries.push(query.to_string());
        println!("[QUERY_ONE]: {}", query);
        // In a real scenario, this would execute the query and parse the result.
        // Here, we return a dummy row for demonstration.
        let mut columns = HashMap::new();
        columns.insert("id".to_string(), MockDbValue::Uuid(1));
        columns.insert("email".to_string(), MockDbValue::Text("test@example.com".to_string()));
        Ok(MockRow { columns })
    }
    
    pub fn query_all(&mut self, query: &str) -> Result<Vec<MockRow>, DbError> {
        self.executed_queries.push(query.to_string());
        println!("[QUERY_ALL]: {}", query);
        Ok(vec![]) // Return empty vec for simplicity
    }

    pub fn begin_transaction(&mut self) -> Result<(), DbError> {
        if self.is_in_transaction {
            return Err(DbError::QueryError("Transaction already in progress".to_string()));
        }
        self.is_in_transaction = true;
        self.execute("BEGIN;")?;
        Ok(())
    }

    pub fn commit(&mut self) -> Result<(), DbError> {
        if !self.is_in_transaction {
            return Err(DbError::QueryError("No transaction in progress to commit".to_string()));
        }
        self.execute("COMMIT;")?;
        self.is_in_transaction = false;
        Ok(())
    }

    pub fn rollback(&mut self) -> Result<(), DbError> {
        if !self.is_in_transaction {
            return Err(DbError::QueryError("No transaction in progress to rollback".to_string()));
        }
        self.execute("ROLLBACK;")?;
        self.is_in_transaction = false;
        Ok(())
    }
}

// --- Domain Model ---

#[derive(Debug)]
pub enum UserRole {
    ADMIN,
    USER,
}

#[derive(Debug)]
pub enum PostStatus {
    DRAFT,
    PUBLISHED,
}

#[derive(Debug)]
pub struct User {
    pub id: u128,
    pub email: String,
    pub password_hash: String,
    pub is_active: bool,
    pub created_at: u64,
}

#[derive(Debug)]
pub struct Post {
    pub id: u128,
    pub user_id: u128,
    pub title: String,
    pub content: String,
    pub status: PostStatus,
}

#[derive(Debug)]
pub struct Role {
    pub id: i32,
    pub name: String,
}

// --- Procedural/Functional Database Operations ---

mod db_migrations {
    use super::{DbConnection, DbError};

    pub fn run_migrations(conn: &mut DbConnection) -> Result<(), DbError> {
        println!("Running migrations...");
        // In a real app, we'd check an `migrations` table. Here we just execute.
        conn.execute(
            "CREATE TABLE IF NOT EXISTS roles (
                id SERIAL PRIMARY KEY,
                name VARCHAR(50) UNIQUE NOT NULL
            );",
        )?;
        conn.execute(
            "CREATE TABLE IF NOT EXISTS users (
                id UUID PRIMARY KEY,
                email VARCHAR(255) UNIQUE NOT NULL,
                password_hash VARCHAR(255) NOT NULL,
                is_active BOOLEAN NOT NULL DEFAULT TRUE,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
            );",
        )?;
        conn.execute(
            "CREATE TABLE IF NOT EXISTS user_roles (
                user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                role_id INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
                PRIMARY KEY (user_id, role_id)
            );",
        )?;
        conn.execute(
            "CREATE TABLE IF NOT EXISTS posts (
                id UUID PRIMARY KEY,
                user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                title VARCHAR(255) NOT NULL,
                content TEXT,
                status VARCHAR(50) NOT NULL DEFAULT 'DRAFT'
            );",
        )?;
        println!("Migrations completed.");
        Ok(())
    }
}

mod data_access {
    use super::{DbConnection, DbError, Post, PostStatus, User};

    // --- CRUD for User ---
    pub fn create_user(conn: &mut DbConnection, email: &str, password_hash: &str) -> Result<u64, DbError> {
        let query = format!(
            "INSERT INTO users (id, email, password_hash) VALUES ('{}', '{}', '{}');",
            12345, // mock UUID
            email,
            password_hash
        );
        conn.execute(&query)
    }

    pub fn find_user_by_id(conn: &mut DbConnection, user_id: u128) -> Result<(), DbError> {
        let query = format!("SELECT * FROM users WHERE id = '{}';", user_id);
        conn.query_one(&query).map(|_| ())
    }

    // --- CRUD for Post ---
    pub fn create_post(conn: &mut DbConnection, user_id: u128, title: &str, content: &str) -> Result<u64, DbError> {
        let query = format!(
            "INSERT INTO posts (id, user_id, title, content, status) VALUES ('{}', '{}', '{}', '{}', 'DRAFT');",
            54321, // mock UUID
            user_id,
            title,
            content
        );
        conn.execute(&query)
    }

    // --- Relationships ---
    pub fn assign_role_to_user(conn: &mut DbConnection, user_id: u128, role_id: i32) -> Result<u64, DbError> {
        let query = format!(
            "INSERT INTO user_roles (user_id, role_id) VALUES ('{}', {});",
            user_id,
            role_id
        );
        conn.execute(&query)
    }

    pub fn find_posts_for_user(conn: &mut DbConnection, user_id: u128) -> Result<(), DbError> {
        let query = format!("SELECT * FROM posts WHERE user_id = '{}';", user_id);
        conn.query_all(&query).map(|_| ())
    }

    // --- Query Building ---
    pub struct PostFilters {
        pub status: Option<PostStatus>,
        pub title_contains: Option<String>,
    }

    pub fn find_posts_with_filters(conn: &mut DbConnection, filters: PostFilters) -> Result<(), DbError> {
        let mut query = "SELECT * FROM posts WHERE 1=1".to_string();
        if let Some(status) = filters.status {
            let status_str = match status {
                PostStatus::DRAFT => "DRAFT",
                PostStatus::PUBLISHED => "PUBLISHED",
            };
            query.push_str(&format!(" AND status = '{}'", status_str));
        }
        if let Some(title) = filters.title_contains {
            query.push_str(&format!(" AND title LIKE '%{}%'", title));
        }
        query.push(';');
        conn.query_all(&query).map(|_| ())
    }
}

// --- Main Application Logic ---
fn main() {
    println!("--- Variation 1: Procedural/Functional Style ---");
    let mut conn = DbConnection::connect().expect("Failed to connect to DB");

    // 1. Migrations
    db_migrations::run_migrations(&mut conn).expect("Migration failed");

    // 2. CRUD Operations
    println!("\n--- Performing CRUD ---");
    data_access::create_user(&mut conn, "procedural@example.com", "hash1").expect("Create user failed");
    data_access::find_user_by_id(&mut conn, 12345).expect("Find user failed");
    data_access::create_post(&mut conn, 12345, "My First Post", "Content here.").expect("Create post failed");

    // 3. Relationships
    println!("\n--- Handling Relationships ---");
    data_access::assign_role_to_user(&mut conn, 12345, 1).expect("Assign role failed"); // Assume role 1 is ADMIN
    data_access::find_posts_for_user(&mut conn, 12345).expect("Find posts for user failed");

    // 4. Query Building
    println!("\n--- Building Dynamic Queries ---");
    let filters = data_access::PostFilters {
        status: Some(PostStatus::DRAFT),
        title_contains: Some("First".to_string()),
    };
    data_access::find_posts_with_filters(&mut conn, filters).expect("Filtered query failed");

    // 5. Transactions
    println!("\n--- Demonstrating Transactions ---");
    if let Err(e) = (|| -> Result<(), DbError> {
        conn.begin_transaction()?;
        data_access::create_user(&mut conn, "tx_user@example.com", "hash_tx")?;
        // Simulate an error
        println!("Simulating an error, should rollback...");
        // conn.execute("INVALID SQL")?; // This would cause an error
        conn.rollback()?;
        Ok(())
    })() {
        eprintln!("Transaction failed and was rolled back: {:?}", e);
    }
    
    println!("\nFinal executed queries log:");
    for (i, query) in conn.executed_queries.iter().enumerate() {
        println!("{}: {}", i + 1, query);
    }
}