use std::collections::HashMap;
use std::rc::Rc;
use std::cell::RefCell;
use std::time::{SystemTime, UNIX_EPOCH};

// --- Mock Database Infrastructure (Standard Library Only) ---
// This section simulates a generic SQL database connection and its components.

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
        Ok(1)
    }

    pub fn query_one(&mut self, query: &str) -> Result<MockRow, DbError> {
        self.executed_queries.push(query.to_string());
        println!("[QUERY_ONE]: {}", query);
        let mut columns = HashMap::new();
        columns.insert("id".to_string(), MockDbValue::Uuid(1));
        columns.insert("email".to_string(), MockDbValue::Text("test@example.com".to_string()));
        Ok(MockRow { columns })
    }
    
    pub fn query_all(&mut self, query: &str) -> Result<Vec<MockRow>, DbError> {
        self.executed_queries.push(query.to_string());
        println!("[QUERY_ALL]: {}", query);
        Ok(vec![])
    }

    pub fn begin_transaction(&mut self) -> Result<(), DbError> {
        self.is_in_transaction = true;
        self.execute("BEGIN;")?;
        Ok(())
    }

    pub fn commit(&mut self) -> Result<(), DbError> {
        self.execute("COMMIT;")?;
        self.is_in_transaction = false;
        Ok(())
    }

    pub fn rollback(&mut self) -> Result<(), DbError> {
        self.execute("ROLLBACK;")?;
        self.is_in_transaction = false;
        Ok(())
    }
}

// --- Domain Model ---

#[derive(Debug)]
pub enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug)]
pub struct User {
    pub id: u128,
    pub email: String,
}

#[derive(Debug)]
pub struct Post {
    pub id: u128,
    pub user_id: u128,
    pub title: String,
}

// --- Repository Pattern ---

// Generic Repository Trait
pub trait Repository<T> {
    fn find_by_id(&self, id: u128) -> Result<T, DbError>;
    fn save(&self, entity: &T) -> Result<(), DbError>;
}

// User-specific Repository Trait
pub trait IUserRepository {
    fn create(&self, email: &str, password_hash: &str) -> Result<(), DbError>;
    fn find_by_email(&self, email: &str) -> Result<User, DbError>;
    fn assign_role(&self, user_id: u128, role_id: i32) -> Result<(), DbError>;
}

// Post-specific Repository Trait
pub trait IPostRepository {
    fn create(&self, user_id: u128, title: &str, content: &str) -> Result<(), DbError>;
    fn find_by_user(&self, user_id: u128) -> Result<Vec<Post>, DbError>;
    fn find_with_filters(&self, status: Option<PostStatus>, title_contains: Option<&str>) -> Result<Vec<Post>, DbError>;
}

// SQL Implementation of Repositories
pub struct SqlUserRepository {
    conn: Rc<RefCell<DbConnection>>,
}

impl IUserRepository for SqlUserRepository {
    fn create(&self, email: &str, password_hash: &str) -> Result<(), DbError> {
        let query = format!("INSERT INTO users (id, email, password_hash) VALUES ('{}', '{}', '{}');", 1, email, password_hash);
        self.conn.borrow_mut().execute(&query).map(|_| ())
    }

    fn find_by_email(&self, email: &str) -> Result<User, DbError> {
        let query = format!("SELECT id, email FROM users WHERE email = '{}';", email);
        self.conn.borrow_mut().query_one(&query)?;
        Ok(User { id: 1, email: email.to_string() })
    }

    fn assign_role(&self, user_id: u128, role_id: i32) -> Result<(), DbError> {
        let query = format!("INSERT INTO user_roles (user_id, role_id) VALUES ('{}', {});", user_id, role_id);
        self.conn.borrow_mut().execute(&query).map(|_| ())
    }
}

pub struct SqlPostRepository {
    conn: Rc<RefCell<DbConnection>>,
}

impl IPostRepository for SqlPostRepository {
    fn create(&self, user_id: u128, title: &str, content: &str) -> Result<(), DbError> {
        let query = format!("INSERT INTO posts (id, user_id, title, content) VALUES ('{}', '{}', '{}', '{}');", 2, user_id, title, content);
        self.conn.borrow_mut().execute(&query).map(|_| ())
    }

    fn find_by_user(&self, user_id: u128) -> Result<Vec<Post>, DbError> {
        let query = format!("SELECT * FROM posts WHERE user_id = '{}';", user_id);
        self.conn.borrow_mut().query_all(&query).map(|_| vec![])
    }

    fn find_with_filters(&self, status: Option<PostStatus>, title_contains: Option<&str>) -> Result<Vec<Post>, DbError> {
        let mut query = "SELECT * FROM posts WHERE 1=1".to_string();
        if let Some(s) = status {
            query.push_str(&format!(" AND status = '{:?}'", s));
        }
        if let Some(t) = title_contains {
            query.push_str(&format!(" AND title LIKE '%{}%'", t));
        }
        self.conn.borrow_mut().query_all(&query).map(|_| vec![])
    }
}

// --- Migration Runner ---
pub struct MigrationRunner {
    conn: Rc<RefCell<DbConnection>>,
}

impl MigrationRunner {
    pub fn new(conn: Rc<RefCell<DbConnection>>) -> Self {
        MigrationRunner { conn }
    }
    pub fn run(&self) -> Result<(), DbError> {
        println!("Running migrations...");
        let mut conn = self.conn.borrow_mut();
        conn.execute("CREATE TABLE IF NOT EXISTS roles (...)")?;
        conn.execute("CREATE TABLE IF NOT EXISTS users (...)")?;
        conn.execute("CREATE TABLE IF NOT EXISTS user_roles (...)")?;
        conn.execute("CREATE TABLE IF NOT EXISTS posts (...)")?;
        println!("Migrations completed.");
        Ok(())
    }
}

// --- Main Application Logic ---
fn main() {
    println!("--- Variation 2: Repository Pattern ---");
    let db_connection = Rc::new(RefCell::new(DbConnection::connect().expect("DB connection failed")));

    // 1. Migrations
    let migrator = MigrationRunner::new(Rc::clone(&db_connection));
    migrator.run().expect("Migrations failed");

    // Instantiate repositories
    let user_repo = SqlUserRepository { conn: Rc::clone(&db_connection) };
    let post_repo = SqlPostRepository { conn: Rc::clone(&db_connection) };

    // 2. CRUD
    println!("\n--- Performing CRUD ---");
    user_repo.create("repo.user@example.com", "hash2").expect("Create user failed");
    let user = user_repo.find_by_email("repo.user@example.com").expect("Find user failed");
    post_repo.create(user.id, "Post by Repo", "Content...").expect("Create post failed");

    // 3. Relationships
    println!("\n--- Handling Relationships ---");
    user_repo.assign_role(user.id, 2).expect("Assign role failed"); // Role 2 = USER
    post_repo.find_by_user(user.id).expect("Find posts failed");

    // 4. Query Building
    println!("\n--- Building Dynamic Queries ---");
    post_repo.find_with_filters(Some(PostStatus::DRAFT), Some("Repo")).expect("Filter failed");

    // 5. Transactions
    println!("\n--- Demonstrating Transactions ---");
    let tx_result = || -> Result<(), DbError> {
        let mut conn = db_connection.borrow_mut();
        conn.begin_transaction()?;
        
        // Use repos within the transaction. We need to pass a mutable ref to the connection
        // This shows a weakness of this simple repo pattern with transactions.
        // A Unit of Work pattern (Var 3) handles this more elegantly.
        let query1 = "INSERT INTO users (id, email, password_hash) VALUES ('100', 'tx@fail.com', 'hash_tx');";
        conn.execute(query1)?;
        
        println!("Simulating an error, should rollback...");
        // Pretend this next op fails
        // let query2 = "INVALID SQL";
        // conn.execute(query2)?;
        
        conn.rollback()?;
        Ok(())
    }();

    if let Err(e) = tx_result {
        eprintln!("Transaction failed: {:?}", e);
    }
    
    println!("\nFinal executed queries log:");
    for (i, query) in db_connection.borrow().executed_queries.iter().enumerate() {
        println!("{}: {}", i + 1, query);
    }
}