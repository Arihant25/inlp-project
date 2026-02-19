import sqlite3
import uuid
import datetime
import hashlib
import enum
from dataclasses import dataclass, field
from typing import List, Optional, Dict, Any
from contextlib import contextmanager
import os

# --- models.py ---
@dataclass
class Role:
    id: int
    name: str

@dataclass
class User:
    id: str
    email: str
    password_hash: str
    is_active: bool
    created_at: datetime.datetime
    roles: List[Role] = field(default_factory=list)

@dataclass
class Post:
    id: str
    user_id: str
    title: str
    content: str
    status: str # e.g., 'DRAFT', 'PUBLISHED'

# --- db.py ---
_connection = None

def get_connection(db_path: str = ":memory:"):
    global _connection
    if _connection is None:
        _connection = sqlite3.connect(db_path)
    return _connection

@contextmanager
def transaction(conn):
    try:
        yield conn.cursor()
        conn.commit()
    except Exception as e:
        print(f"Transaction failed: {e}. Rolling back.")
        conn.rollback()
        raise

# --- migrations.py ---
MIGRATION_SCRIPTS = [
    """
    CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL,
        is_active INTEGER NOT NULL, created_at TEXT NOT NULL
    );
    """,
    """
    CREATE TABLE IF NOT EXISTS posts (
        id TEXT PRIMARY KEY, user_id TEXT NOT NULL, title TEXT NOT NULL,
        content TEXT, status TEXT NOT NULL,
        FOREIGN KEY(user_id) REFERENCES users(id)
    );
    """,
    """
    CREATE TABLE IF NOT EXISTS roles (
        id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL
    );
    """,
    """
    CREATE TABLE IF NOT EXISTS user_roles (
        user_id TEXT NOT NULL, role_id INTEGER NOT NULL,
        PRIMARY KEY(user_id, role_id),
        FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(role_id) REFERENCES roles(id)
    );
    """
]

def run_migrations(conn):
    print("Running migrations...")
    cursor = conn.cursor()
    for script in MIGRATION_SCRIPTS:
        cursor.executescript(script)
    
    # Seed roles
    cursor.execute("INSERT OR IGNORE INTO roles (name) VALUES ('ADMIN')")
    cursor.execute("INSERT OR IGNORE INTO roles (name) VALUES ('USER')")
    conn.commit()
    print("Migrations complete.")

# --- queries/role_queries.py ---
def get_role_by_name(conn, name: str) -> Optional[Role]:
    cur = conn.cursor()
    cur.execute("SELECT id, name FROM roles WHERE name = ?", (name,))
    row = cur.fetchone()
    return Role(id=row[0], name=row[1]) if row else None

# --- queries/user_queries.py ---
def _row_to_user(row: sqlite3.Row) -> User:
    return User(
        id=row[0], email=row[1], password_hash=row[2],
        is_active=bool(row[3]),
        created_at=datetime.datetime.fromisoformat(row[4])
    )

def create_user(conn, email: str, password: str) -> User:
    user = User(
        id=str(uuid.uuid4()),
        email=email,
        password_hash=hashlib.sha256(password.encode()).hexdigest(),
        is_active=True,
        created_at=datetime.datetime.utcnow()
    )
    sql = "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)"
    conn.cursor().execute(sql, (
        user.id, user.email, user.password_hash,
        user.is_active, user.created_at.isoformat()
    ))
    return user

def assign_role_to_user(conn, user_id: str, role_id: int):
    sql = "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)"
    conn.cursor().execute(sql, (user_id, role_id))

def find_users(conn, filters: Dict[str, Any]) -> List[User]:
    base_query = "SELECT DISTINCT u.id, u.email, u.password_hash, u.is_active, u.created_at FROM users u"
    params = []
    joins = []
    wheres = []

    if 'role_name' in filters:
        joins.append("JOIN user_roles ur ON u.id = ur.user_id JOIN roles r ON ur.role_id = r.id")
        wheres.append("r.name = ?")
        params.append(filters['role_name'])
    
    if 'is_active' in filters:
        wheres.append("u.is_active = ?")
        params.append(1 if filters['is_active'] else 0)

    query = f"{base_query} {' '.join(joins)} WHERE {' AND '.join(wheres)}" if wheres else base_query
    
    cur = conn.cursor()
    cur.execute(query, params)
    return [_row_to_user(row) for row in cur.fetchall()]

# --- queries/post_queries.py ---
def create_post(conn, user_id: str, title: str, content: str, status: str) -> Post:
    post = Post(
        id=str(uuid.uuid4()), user_id=user_id, title=title,
        content=content, status=status
    )
    sql = "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)"
    conn.cursor().execute(sql, (post.id, post.user_id, post.title, post.content, post.status))
    return post

def get_posts_by_user(conn, user_id: str) -> List[Post]:
    cur = conn.cursor()
    cur.execute("SELECT id, user_id, title, content, status FROM posts WHERE user_id = ?", (user_id,))
    return [Post(*row) for row in cur.fetchall()]

# --- Main Application ---
def main():
    DB_FILE = "dataclass_app.db"
    if os.path.exists(DB_FILE):
        os.remove(DB_FILE)
    
    conn = get_connection(DB_FILE)
    run_migrations(conn)

    print("\n1. Creating users and roles...")
    user_greg = create_user(conn, "greg@example.com", "pass_g")
    user_helen = create_user(conn, "helen@example.com", "pass_h")
    
    admin_role = get_role_by_name(conn, "ADMIN")
    user_role = get_role_by_name(conn, "USER")
    
    assign_role_to_user(conn, user_greg.id, admin_role.id)
    assign_role_to_user(conn, user_helen.id, user_role.id)
    conn.commit()
    print(f"Created user from dataclass: {user_greg.email}")

    print("\n2. Creating posts...")
    post1 = create_post(conn, user_greg.id, "Greg's Post", "Content", "PUBLISHED")
    conn.commit()
    print(f"Created post from dataclass: {post1.title}")

    greg_posts = get_posts_by_user(conn, user_greg.id)
    print(f"Greg has {len(greg_posts)} posts. First post title: '{greg_posts[0].title}'")

    print("\n3. Query building with filters...")
    active_admins = find_users(conn, filters={'is_active': True, 'role_name': 'ADMIN'})
    print(f"Found {len(active_admins)} active admin(s). Email: {active_admins[0].email}")

    print("\n4. Transaction with rollback...")
    try:
        with transaction(conn) as cursor:
            print("  - Creating post for Helen inside transaction.")
            cursor.execute(
                "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)",
                (str(uuid.uuid4()), user_helen.id, "A Temp Post", "...", "DRAFT")
            )
            print("  - Deactivating Helen.")
            cursor.execute("UPDATE users SET is_active = 0 WHERE id = ?", (user_helen.id,))
            raise ValueError("Intentional failure")
    except ValueError:
        print("  - Transaction rolled back successfully.")

    helen_posts = get_posts_by_user(conn, user_helen.id)
    helen_status = conn.execute("SELECT is_active FROM users WHERE id=?", (user_helen.id,)).fetchone()
    print(f"After rollback, Helen has {len(helen_posts)} posts (expected 0).")
    print(f"After rollback, Helen's is_active status is: {bool(helen_status[0])} (expected True).")

    conn.close()
    os.remove(DB_FILE)

if __name__ == "__main__":
    main()