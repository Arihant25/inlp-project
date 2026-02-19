use std::collections::HashMap;
use std::cell::RefCell;
use std::rc::Rc;

// --- Mock Database Infrastructure (Standard Library Only) ---
#[derive(Debug)]
pub enum DbError { QueryError(String), NotFound }
pub struct MockRow;
pub struct DbConnection {
    in_transaction: bool,
    log: Rc<RefCell<Vec<String>>>,
}
impl DbConnection {
    fn new(log: Rc<RefCell<Vec<String>>>) -> Self { DbConnection { in_transaction: false, log } }
    fn execute(&mut self, query: &str) -> Result<(), DbError> {
        self.log.borrow_mut().push(query.to_string());
        println!("[EXECUTE]: {}", query);
        Ok(())
    }
    fn query(&self, query: &str) -> Result<Vec<MockRow>, DbError> {
        self.log.borrow_mut().push(query.to_string());
        println!("[QUERY]: {}", query);
        Ok(vec![])
    }
}

// --- Domain Model ---
pub enum PostStatus { DRAFT, PUBLISHED }
pub struct User { pub user_id: u128, pub email_address: String }
pub struct Post { pub post_id: u128, pub author_id: u128, pub post_title: String }

// --- Data Layer: Repositories ---
// Repositories operate on a DbConnection within a transaction.
pub struct UserRepository<'a> { conn: &'a mut DbConnection }
impl<'a> UserRepository<'a> {
    pub fn new(conn: &'a mut DbConnection) -> Self { Self { conn } }
    pub fn add(&mut self, user: &User) -> Result<(), DbError> {
        self.conn.execute(&format!("INSERT INTO users (id, email) VALUES ('{}', '{}');", user.user_id, user.email_address))
    }
    pub fn get(&self, user_id: u128) -> Result<User, DbError> {
        self.conn.query(&format!("SELECT * FROM users WHERE id = '{}';", user_id))?;
        Ok(User { user_id, email_address: "from_db@test.com".to_string() })
    }
    pub fn assign_role(&mut self, user_id: u128, role_name: &str) -> Result<(), DbError> {
        self.conn.execute(&format!("INSERT INTO user_roles (user_id, role_id) SELECT '{}', id FROM roles WHERE name = '{}';", user_id, role_name))
    }
}

pub struct PostRepository<'a> { conn: &'a mut DbConnection }
impl<'a> PostRepository<'a> {
    pub fn new(conn: &'a mut DbConnection) -> Self { Self { conn } }
    pub fn add(&mut self, post: &Post) -> Result<(), DbError> {
        self.conn.execute(&format!("INSERT INTO posts (id, user_id, title) VALUES ('{}', '{}', '{}');", post.post_id, post.author_id, post.post_title))
    }
    pub fn find_with_filters(&self, user_id: u128, status: PostStatus) -> Result<Vec<Post>, DbError> {
        let status_str = match status { PostStatus::DRAFT => "DRAFT", PostStatus::PUBLISHED => "PUBLISHED" };
        self.conn.query(&format!("SELECT * FROM posts WHERE user_id = '{}' AND status = '{}';", user_id, status_str))?;
        Ok(vec![])
    }
}

// --- Unit of Work Pattern ---
// Manages the connection and transaction lifecycle. Provides repositories.
pub struct UnitOfWork {
    connection: DbConnection,
    committed: bool,
}
impl UnitOfWork {
    pub fn new(log: Rc<RefCell<Vec<String>>>) -> Self {
        let mut uow = UnitOfWork { connection: DbConnection::new(log), committed: false };
        uow.connection.execute("BEGIN;").unwrap();
        uow.connection.in_transaction = true;
        uow
    }

    pub fn users(&mut self) -> UserRepository { UserRepository::new(&mut self.connection) }
    pub fn posts(&mut self) -> PostRepository { PostRepository::new(&mut self.connection) }

    pub fn commit(mut self) -> Result<(), DbError> {
        self.connection.execute("COMMIT;")?;
        self.connection.in_transaction = false;
        self.committed = true;
        Ok(())
    }
}
impl Drop for UnitOfWork {
    fn drop(&mut self) {
        if !self.committed && self.connection.in_transaction {
            println!("UnitOfWork dropped without commit, rolling back.");
            self.connection.execute("ROLLBACK;").unwrap();
        }
    }
}

// --- Service Layer ---
// Orchestrates business logic using the Unit of Work.
pub struct UserService;
impl UserService {
    pub fn register_user_and_create_intro_post(
        &self,
        email: String,
        log: Rc<RefCell<Vec<String>>>,
    ) -> Result<(), DbError> {
        let mut uow = UnitOfWork::new(log);

        let new_user = User { user_id: 111, email_address: email };
        uow.users().add(&new_user)?;
        uow.users().assign_role(new_user.user_id, "USER")?;

        let intro_post = Post {
            post_id: 222,
            author_id: new_user.user_id,
            post_title: "My First Post!".to_string(),
        };
        uow.posts().add(&intro_post)?;

        uow.commit()
    }
}

// --- Migration Logic ---
fn run_db_migrations(log: Rc<RefCell<Vec<String>>>) -> Result<(), DbError> {
    println!("Running migrations...");
    let mut conn = DbConnection::new(log);
    conn.execute("CREATE TABLE IF NOT EXISTS roles (...)")?;
    conn.execute("CREATE TABLE IF NOT EXISTS users (...)")?;
    conn.execute("CREATE TABLE IF NOT EXISTS user_roles (...)")?;
    conn.execute("CREATE TABLE IF NOT EXISTS posts (...)")?;
    println!("Migrations completed.");
    Ok(())
}

// --- Main Application Logic ---
fn main() {
    println!("--- Variation 3: Service Layer with Unit of Work ---");
    let query_log = Rc::new(RefCell::new(Vec::new()));

    // 1. Migrations
    run_db_migrations(Rc::clone(&query_log)).expect("Migrations failed");

    // 2. Use the Service Layer for a transactional operation
    println!("\n--- Performing a complex, transactional operation via Service Layer ---");
    let user_service = UserService;
    user_service
        .register_user_and_create_intro_post("uow.user@example.com".to_string(), Rc::clone(&query_log))
        .expect("Service operation failed");

    // 3. Demonstrate automatic rollback on drop
    println!("\n--- Demonstrating automatic rollback ---");
    {
        let mut uow = UnitOfWork::new(Rc::clone(&query_log));
        let user_to_fail = User { user_id: 999, email_address: "fail@example.com".to_string() };
        uow.users().add(&user_to_fail).unwrap();
        // uow is dropped here without calling .commit()
    }

    // 4. Demonstrate query building via repository
    println!("\n--- Building queries through a repository method ---");
    let mut uow_for_query = UnitOfWork::new(Rc::clone(&query_log));
    uow_for_query.posts().find_with_filters(111, PostStatus::DRAFT).expect("Query failed");
    // We don't need to commit a read-only transaction, so we let it roll back.

    println!("\nFinal executed queries log:");
    for (i, query) in query_log.borrow().iter().enumerate() {
        println!("{}: {}", i + 1, query);
    }
}