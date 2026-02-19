import sqlite3
import uuid
import datetime
import hashlib
import enum
import os

# --- Domain Model ---

class RoleEnum(enum.Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatusEnum(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Database Configuration ---

DB_NAME = "procedural_app.db"

# --- Migrations ---

MIGRATION_SCRIPTS = [
    """
    CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY,
        email TEXT UNIQUE NOT NULL,
        password_hash TEXT NOT NULL,
        is_active INTEGER NOT NULL DEFAULT 1,
        created_at TEXT NOT NULL
    );
    """,
    """
    CREATE TABLE IF NOT EXISTS posts (
        id TEXT PRIMARY KEY,
        user_id TEXT NOT NULL,
        title TEXT NOT NULL,
        content TEXT,
        status TEXT NOT NULL,
        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
    );
    """,
    """
    CREATE TABLE IF NOT EXISTS roles (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT UNIQUE NOT NULL
    );
    """,
    """
    CREATE TABLE IF NOT EXISTS user_roles (
        user_id TEXT NOT NULL,
        role_id INTEGER NOT NULL,
        PRIMARY KEY(user_id, role_id),
        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
        FOREIGN KEY(role_id) REFERENCES roles(id) ON DELETE CASCADE
    );
    """
]

# --- Core Database Functions ---

def get_db_connection(db_file):
    """Establishes a connection to the SQLite database."""
    return sqlite3.connect(db_file)

def run_migrations(conn):
    """Applies all migration scripts to the database."""
    cursor = conn.cursor()
    print("Running migrations...")
    for script in MIGRATION_SCRIPTS:
        cursor.executescript(script)
    
    # Seed roles
    cursor.execute("INSERT OR IGNORE INTO roles (name) VALUES (?)", (RoleEnum.ADMIN.value,))
    cursor.execute("INSERT OR IGNORE INTO roles (name) VALUES (?)", (RoleEnum.USER.value,))
    
    conn.commit()
    print("Migrations completed.")

# --- CRUD and Relationship Functions ---

def create_user(conn, email, password):
    """Creates a new user and returns their ID."""
    user_id = str(uuid.uuid4())
    password_hash = hashlib.sha256(password.encode()).hexdigest()
    created_at = datetime.datetime.utcnow().isoformat()
    
    sql = "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)"
    cursor = conn.cursor()
    cursor.execute(sql, (user_id, email, password_hash, 1, created_at))
    conn.commit()
    return user_id

def get_user_by_email(conn, email):
    """Fetches a user by their email."""
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM users WHERE email = ?", (email,))
    return cursor.fetchone()

def assign_role_to_user(conn, user_id, role_name):
    """Assigns a role to a user (many-to-many)."""
    cursor = conn.cursor()
    cursor.execute("SELECT id FROM roles WHERE name = ?", (role_name.value,))
    role = cursor.fetchone()
    if not role:
        raise ValueError(f"Role '{role_name.value}' not found.")
    role_id = role[0]
    
    sql = "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)"
    try:
        cursor.execute(sql, (user_id, role_id))
        conn.commit()
    except sqlite3.IntegrityError:
        print(f"User {user_id} already has role {role_name.value}.")
        conn.rollback()


def create_post_for_user(conn, user_id, title, content, status):
    """Creates a new post for a given user (one-to-many)."""
    post_id = str(uuid.uuid4())
    sql = "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)"
    cursor = conn.cursor()
    cursor.execute(sql, (post_id, user_id, title, content, status.value))
    conn.commit()
    return post_id

def get_posts_by_user(conn, user_id):
    """Retrieves all posts for a specific user."""
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM posts WHERE user_id = ?", (user_id,))
    return cursor.fetchall()

def find_users(conn, is_active=None, role=None):
    """Builds a query to find users based on filters."""
    query = "SELECT u.* FROM users u"
    params = []
    conditions = []

    if role is not None:
        query += """
            JOIN user_roles ur ON u.id = ur.user_id
            JOIN roles r ON ur.role_id = r.id
        """
        conditions.append("r.name = ?")
        params.append(role.value)

    if is_active is not None:
        conditions.append("u.is_active = ?")
        params.append(1 if is_active else 0)

    if conditions:
        query += " WHERE " + " AND ".join(conditions)

    cursor = conn.cursor()
    cursor.execute(query, params)
    return cursor.fetchall()

def perform_transactional_update(conn, user_id, new_post_title):
    """Demonstrates a transaction with a potential rollback."""
    cursor = conn.cursor()
    try:
        print("\n--- Starting Transaction ---")
        # Operation 1: Deactivate user
        cursor.execute("UPDATE users SET is_active = 0 WHERE id = ?", (user_id,))
        print(f"User {user_id} deactivated.")

        # Operation 2: Create a new post for them
        post_id = str(uuid.uuid4())
        cursor.execute(
            "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)",
            (post_id, user_id, new_post_title, "Content during transaction", PostStatusEnum.DRAFT.value)
        )
        print(f"Post '{new_post_title}' created for deactivated user.")

        # Simulate an error condition
        # raise ValueError("Simulating an error to trigger rollback!")

        conn.commit()
        print("--- Transaction Committed ---")
    except Exception as e:
        print(f"--- Transaction Failed: {e} ---")
        print("--- Rolling back changes ---")
        conn.rollback()


# --- Main Execution Block ---

def main():
    """Demonstrates the usage of the procedural functions."""
    if os.path.exists(DB_NAME):
        os.remove(DB_NAME)

    db_conn = get_db_connection(DB_NAME)
    
    run_migrations(db_conn)

    # CRUD Operations
    print("\n1. Creating users and roles...")
    user1_id = create_user(db_conn, "alice@example.com", "password123")
    user2_id = create_user(db_conn, "bob@example.com", "password456")
    assign_role_to_user(db_conn, user1_id, RoleEnum.ADMIN)
    assign_role_to_user(db_conn, user1_id, RoleEnum.USER) # Also a user
    assign_role_to_user(db_conn, user2_id, RoleEnum.USER)
    print(f"Alice created with ID: {user1_id}")
    print(f"Bob created with ID: {user2_id}")

    # One-to-Many Relationship
    print("\n2. Creating posts for users...")
    create_post_for_user(db_conn, user1_id, "Alice's First Post", "Content here.", PostStatusEnum.PUBLISHED)
    create_post_for_user(db_conn, user1_id, "Alice's Draft", "...", PostStatusEnum.DRAFT)
    
    alice_posts = get_posts_by_user(db_conn, user1_id)
    print(f"Alice has {len(alice_posts)} posts.")

    # Query Building with Filters
    print("\n3. Querying for users...")
    active_admins = find_users(db_conn, is_active=True, role=RoleEnum.ADMIN)
    print(f"Found {len(active_admins)} active admin(s): {active_admins}")
    all_users = find_users(db_conn, is_active=True)
    print(f"Found {len(all_users)} active user(s).")

    # Transaction and Rollback
    print("\n4. Demonstrating a transaction...")
    user_before = db_conn.execute("SELECT is_active FROM users WHERE id=?", (user2_id,)).fetchone()
    print(f"Bob's active status before transaction: {user_before[0] == 1}")
    
    perform_transactional_update(db_conn, user2_id, "A new post for Bob")
    
    user_after = db_conn.execute("SELECT is_active FROM users WHERE id=?", (user2_id,)).fetchone()
    print(f"Bob's active status after transaction: {user_after[0] == 1}")
    
    db_conn.close()
    os.remove(DB_NAME)

if __name__ == "__main__":
    main()