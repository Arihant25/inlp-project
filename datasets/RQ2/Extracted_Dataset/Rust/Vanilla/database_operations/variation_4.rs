use std::collections::HashMap;
use std::sync::{Arc, Mutex};

// --- Mock Database Infrastructure (Standard Library Only) ---
#[derive(Debug)]
pub enum DatabaseError {
    Fatal(String),
    RecordNotFound,
}

// A thread-safe, in-memory "database" to simulate state.
#[derive(Default)]
pub struct MockDatabase {
    pub execution_log: Vec<String>,
}

impl MockDatabase {
    pub fn new() -> Arc<Mutex<Self>> {
        Arc::new(Mutex::new(Self::default()))
    }
}

pub type DbConnection = Arc<Mutex<MockDatabase>>;

fn execute_sql(conn: &DbConnection, sql: String) -> Result<(), DatabaseError> {
    let mut db = conn.lock().unwrap();
    println!("[SQL]: {}", sql);
    db.execution_log.push(sql);
    Ok(())
}

// --- Domain Model ---
pub type EntityId = u128;

pub struct User {
    pub id: EntityId,
    pub email: String,
}

pub struct Post {
    pub id: EntityId,
    pub author_id: EntityId,
    pub title: String,
}

pub enum PostStatusFilter {
    Draft,
    Published,
}

// --- CQRS Pattern: Commands (Writes) ---

// Command definitions
pub struct CreateUserCommand {
    pub user_id: EntityId,
    pub email: String,
    pub password_hash: String,
}

pub struct CreatePostForUserCommand {
    pub post_id: EntityId,
    pub author_id: EntityId,
    pub title: String,
    pub content: String,
}

pub struct AssignRoleCommand {
    pub user_id: EntityId,
    pub role_id: i32,
}

// Command Handler trait
pub trait ICommandHandler<C> {
    fn handle(&self, command: C) -> Result<(), DatabaseError>;
}

// Command Handler implementations
pub struct CreateUserCommandHandler {
    db: DbConnection,
}
impl ICommandHandler<CreateUserCommand> for CreateUserCommandHandler {
    fn handle(&self, cmd: CreateUserCommand) -> Result<(), DatabaseError> {
        let sql = format!(
            "INSERT INTO users (id, email, password_hash) VALUES ('{}', '{}', '{}');",
            cmd.user_id, cmd.email, cmd.password_hash
        );
        execute_sql(&self.db, sql)
    }
}

pub struct CreatePostForUserCommandHandler {
    db: DbConnection,
}
impl ICommandHandler<CreatePostForUserCommand> for CreatePostForUserCommandHandler {
    fn handle(&self, cmd: CreatePostForUserCommand) -> Result<(), DatabaseError> {
        let sql = format!(
            "INSERT INTO posts (id, user_id, title, content) VALUES ('{}', '{}', '{}', '{}');",
            cmd.post_id, cmd.author_id, cmd.title, cmd.content
        );
        execute_sql(&self.db, sql)
    }
}

// Transactional Command Handler
pub struct UserRegistrationHandler {
    db: DbConnection,
}
impl UserRegistrationHandler {
    pub fn handle_registration(&self, user_id: EntityId, email: &str) -> Result<(), DatabaseError> {
        execute_sql(&self.db, "BEGIN;".to_string())?;
        
        let create_user_sql = format!("INSERT INTO users (id, email) VALUES ('{}', '{}');", user_id, email);
        if let Err(e) = execute_sql(&self.db, create_user_sql) {
            execute_sql(&self.db, "ROLLBACK;".to_string())?;
            return Err(e);
        }

        let assign_role_sql = format!("INSERT INTO user_roles (user_id, role_id) VALUES ('{}', 1);", user_id);
        if let Err(e) = execute_sql(&self.db, assign_role_sql) {
            execute_sql(&self.db, "ROLLBACK;".to_string())?;
            return Err(e);
        }

        execute_sql(&self.db, "COMMIT;".to_string())
    }
}

// --- CQRS Pattern: Queries (Reads) ---

// Query definitions
pub struct FindUserByIdQuery {
    pub user_id: EntityId,
}

pub struct FindPostsQuery {
    pub author_id: Option<EntityId>,
    pub status: Option<PostStatusFilter>,
}

// Query Handler trait
pub trait IQueryHandler<Q, R> {
    fn handle(&self, query: Q) -> Result<R, DatabaseError>;
}

// Query Handler implementations
pub struct FindUserByIdQueryHandler {
    db: DbConnection,
}
impl IQueryHandler<FindUserByIdQuery, User> for FindUserByIdQueryHandler {
    fn handle(&self, query: FindUserByIdQuery) -> Result<User, DatabaseError> {
        let sql = format!("SELECT id, email FROM users WHERE id = '{}' LIMIT 1;", query.user_id);
        execute_sql(&self.db, sql)?;
        // Simulate returning a found user
        Ok(User { id: query.user_id, email: "cqrs.user@example.com".to_string() })
    }
}

pub struct FindPostsQueryHandler {
    db: DbConnection,
}
impl IQueryHandler<FindPostsQuery, Vec<Post>> for FindPostsQueryHandler {
    fn handle(&self, query: FindPostsQuery) -> Result<Vec<Post>, DatabaseError> {
        let mut sql = "SELECT id, author_id, title FROM posts WHERE 1=1".to_string();
        if let Some(author_id) = query.author_id {
            sql.push_str(&format!(" AND author_id = '{}'", author_id));
        }
        if let Some(status) = query.status {
            let status_str = match status {
                PostStatusFilter::Draft => "DRAFT",
                PostStatusFilter::Published => "PUBLISHED",
            };
            sql.push_str(&format!(" AND status = '{}'", status_str));
        }
        sql.push(';');
        execute_sql(&self.db, sql)?;
        Ok(vec![])
    }
}

// --- Migration Runner ---
fn run_migrations(db: &DbConnection) -> Result<(), DatabaseError> {
    println!("Running migrations...");
    execute_sql(db, "CREATE TABLE IF NOT EXISTS roles (...)".to_string())?;
    execute_sql(db, "CREATE TABLE IF NOT EXISTS users (...)".to_string())?;
    execute_sql(db, "CREATE TABLE IF NOT EXISTS user_roles (...)".to_string())?;
    execute_sql(db, "CREATE TABLE IF NOT EXISTS posts (...)".to_string())?;
    println!("Migrations completed.");
    Ok(())
}

// --- Main Application Logic ---
fn main() {
    println!("--- Variation 4: CQRS-like Style ---");
    let database_connection = MockDatabase::new();

    // 1. Migrations
    run_migrations(&database_connection).expect("Migrations failed");

    // 2. Setup Handlers
    let create_user_handler = CreateUserCommandHandler { db: Arc::clone(&database_connection) };
    let find_user_handler = FindUserByIdQueryHandler { db: Arc::clone(&database_connection) };
    let find_posts_handler = FindPostsQueryHandler { db: Arc::clone(&database_connection) };
    let registration_handler = UserRegistrationHandler { db: Arc::clone(&database_connection) };

    // 3. Execute Commands (Writes)
    println!("\n--- Executing Commands (Writes) ---");
    let create_user_cmd = CreateUserCommand {
        user_id: 101,
        email: "another.user@example.com".to_string(),
        password_hash: "hash4".to_string(),
    };
    create_user_handler.handle(create_user_cmd).expect("Command failed");

    // 4. Execute Queries (Reads)
    println!("\n--- Executing Queries (Reads) ---");
    let find_user_query = FindUserByIdQuery { user_id: 101 };
    let user = find_user_handler.handle(find_user_query).expect("Query failed");
    println!("Found user: {}", user.email);

    // 5. Execute a query with filters (Query Building)
    println!("\n--- Executing a Filtered Query ---");
    let find_posts_query = FindPostsQuery {
        author_id: Some(user.id),
        status: Some(PostStatusFilter::Draft),
    };
    find_posts_handler.handle(find_posts_query).expect("Filtered query failed");

    // 6. Execute a transactional command
    println!("\n--- Executing a Transactional Command ---");
    registration_handler.handle_registration(202, "tx.user@example.com").expect("Transaction failed");

    println!("\nFinal executed queries log:");
    for (i, query) in database_connection.lock().unwrap().execution_log.iter().enumerate() {
        println!("{}: {}", i + 1, query);
    }
}