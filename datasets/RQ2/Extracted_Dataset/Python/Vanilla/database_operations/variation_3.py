import sqlite3
import uuid
import datetime
import hashlib
import enum
import os
from abc import ABC, abstractmethod
from typing import List, Dict, Any, Optional

# --- Domain Models ---
class Role(enum.Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Database Connection Management ---
class DbConnection:
    _connection = None

    @classmethod
    def get_connection(cls, db_name: str = ":memory:"):
        if cls._connection is None:
            cls._connection = sqlite3.connect(db_name)
            cls._connection.row_factory = sqlite3.Row
        return cls._connection

# --- Migration Service ---
class MigrationService:
    def __init__(self, conn):
        self.conn = conn

    def apply(self):
        print("Applying migrations...")
        cursor = self.conn.cursor()
        cursor.executescript("""
            CREATE TABLE IF NOT EXISTS users (
                id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL,
                is_active INTEGER NOT NULL, created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS posts (
                id TEXT PRIMARY KEY, user_id TEXT NOT NULL, title TEXT NOT NULL,
                content TEXT, status TEXT NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users(id)
            );
            CREATE TABLE IF NOT EXISTS roles (
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL
            );
            CREATE TABLE IF NOT EXISTS user_roles (
                user_id TEXT NOT NULL, role_id INTEGER NOT NULL,
                PRIMARY KEY(user_id, role_id),
                FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(role_id) REFERENCES roles(id)
            );
        """)
        self.conn.commit()
        print("Migrations applied.")

# --- Repository Layer (Data Access) ---
class BaseRepository(ABC):
    def __init__(self, connection):
        self.conn = connection

    @abstractmethod
    def add(self, entity: Any) -> str:
        pass

    @abstractmethod
    def get(self, id: str) -> Optional[Dict[str, Any]]:
        pass

class UserRepository(BaseRepository):
    def add(self, user_data: Dict[str, Any]) -> str:
        user_id = str(uuid.uuid4())
        sql = "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)"
        self.conn.cursor().execute(sql, (
            user_id, user_data['email'], user_data['password_hash'],
            user_data['is_active'], user_data['created_at']
        ))
        return user_id

    def get(self, id: str) -> Optional[Dict[str, Any]]:
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM users WHERE id = ?", (id,))
        row = cur.fetchone()
        return dict(row) if row else None

    def get_by_email(self, email: str) -> Optional[Dict[str, Any]]:
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM users WHERE email = ?", (email,))
        row = cur.fetchone()
        return dict(row) if row else None
    
    def assign_role(self, user_id: str, role_id: int):
        sql = "INSERT OR IGNORE INTO user_roles (user_id, role_id) VALUES (?, ?)"
        self.conn.cursor().execute(sql, (user_id, role_id))

class PostRepository(BaseRepository):
    def add(self, post_data: Dict[str, Any]) -> str:
        post_id = str(uuid.uuid4())
        sql = "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)"
        self.conn.cursor().execute(sql, (
            post_id, post_data['user_id'], post_data['title'],
            post_data['content'], post_data['status'].value
        ))
        return post_id

    def get(self, id: str) -> Optional[Dict[str, Any]]:
        # Implementation omitted for brevity
        return None

    def find_by_user(self, user_id: str) -> List[Dict[str, Any]]:
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM posts WHERE user_id = ?", (user_id,))
        return [dict(row) for row in cur.fetchall()]

class RoleRepository(BaseRepository):
    def add(self, role_name: Role) -> int:
        cur = self.conn.cursor()
        cur.execute("INSERT INTO roles (name) VALUES (?)", (role_name.value,))
        return cur.lastrowid

    def get(self, id: int) -> Optional[Dict[str, Any]]:
        # Implementation omitted for brevity
        return None

    def get_by_name(self, role_name: Role) -> Optional[Dict[str, Any]]:
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM roles WHERE name = ?", (role_name.value,))
        row = cur.fetchone()
        return dict(row) if row else None

# --- Service Layer (Business Logic) ---
class UserService:
    def __init__(self, user_repo: UserRepository, role_repo: RoleRepository):
        self.user_repo = user_repo
        self.role_repo = role_repo
        self.conn = user_repo.conn

    def register_user(self, email: str, password: str, roles: List[Role]):
        if self.user_repo.get_by_email(email):
            raise ValueError("User with this email already exists.")
        
        password_hash = hashlib.sha256(password.encode()).hexdigest()
        user_data = {
            'email': email,
            'password_hash': password_hash,
            'is_active': True,
            'created_at': datetime.datetime.utcnow().isoformat()
        }
        
        try:
            # Transaction managed at the service level
            self.conn.execute('BEGIN')
            user_id = self.user_repo.add(user_data)
            for role in roles:
                role_obj = self.role_repo.get_by_name(role)
                if not role_obj:
                    raise ValueError(f"Role {role.value} not found.")
                self.user_repo.assign_role(user_id, role_obj['id'])
            self.conn.commit()
            return user_id
        except Exception as e:
            self.conn.rollback()
            raise e

class PostService:
    def __init__(self, post_repo: PostRepository):
        self.post_repo = post_repo

    def create_post(self, user_id: str, title: str, content: str, status: PostStatus):
        post_data = {
            'user_id': user_id,
            'title': title,
            'content': content,
            'status': status
        }
        return self.post_repo.add(post_data)

# --- Main Execution ---
def main():
    DB_FILE = "repository_app.db"
    if os.path.exists(DB_FILE):
        os.remove(DB_FILE)
    
    conn = DbConnection.get_connection(DB_FILE)
    
    MigrationService(conn).apply()

    # Setup Repositories and Services
    user_repo = UserRepository(conn)
    post_repo = PostRepository(conn)
    role_repo = RoleRepository(conn)
    
    # Seed roles
    role_repo.add(Role.ADMIN)
    role_repo.add(Role.USER)
    conn.commit()

    user_service = UserService(user_repo, role_repo)
    post_service = PostService(post_repo)

    print("\n1. Registering users via Service Layer...")
    user1_id = user_service.register_user("eva@example.com", "pass123", [Role.ADMIN, Role.USER])
    user2_id = user_service.register_user("frank@example.com", "pass456", [Role.USER])
    print(f"Eva (Admin) created with ID: {user1_id}")
    print(f"Frank (User) created with ID: {user2_id}")

    print("\n2. Creating posts via Service Layer...")
    post_service.create_post(user1_id, "Eva's Thoughts", "Content...", PostStatus.PUBLISHED)
    post_service.create_post(user2_id, "Frank's Draft", "...", PostStatus.DRAFT)
    conn.commit()

    eva_posts = post_repo.find_by_user(user1_id)
    print(f"Eva has {len(eva_posts)} posts.")

    print("\n3. Transaction rollback demonstration...")
    try:
        print("Attempting to register a user with a non-existent role...")
        # This will fail because 'SUPER_ADMIN' role doesn't exist.
        user_service.register_user("ghost@example.com", "pass", [Role.USER, "SUPER_ADMIN"])
    except Exception as e:
        print(f"Registration failed as expected: {e}")

    ghost_user = user_repo.get_by_email("ghost@example.com")
    print(f"Was the ghost user created? {'Yes' if ghost_user else 'No'}")

    conn.close()
    os.remove(DB_FILE)

if __name__ == "__main__":
    main()