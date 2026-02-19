import sqlite3
import uuid
import datetime
import hashlib
import enum
import os
from contextlib import contextmanager

# --- Domain Model Enums ---
class RoleType(enum.Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Database Manager ---
class DatabaseManager:
    _instance = None

    def __init__(self, db_path):
        if DatabaseManager._instance is not None:
            raise Exception("This class is a singleton!")
        else:
            self.db_path = db_path
            self.connection = None
            DatabaseManager._instance = self

    def getConnection(self):
        if self.connection is None:
            self.connection = sqlite3.connect(self.db_path)
            self.connection.row_factory = sqlite3.Row
        return self.connection

    def closeConnection(self):
        if self.connection:
            self.connection.close()
            self.connection = None

    def runMigrations(self):
        conn = self.getConnection()
        cursor = conn.cursor()
        print("Running migrations...")
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
        # Seed roles
        RoleDAO(conn).getOrCreate(RoleType.ADMIN)
        RoleDAO(conn).getOrCreate(RoleType.USER)
        conn.commit()
        print("Migrations complete.")

    @contextmanager
    def transaction(self):
        conn = self.getConnection()
        try:
            yield conn
            conn.commit()
        except Exception as e:
            print(f"Transaction failed: {e}. Rolling back.")
            conn.rollback()
            raise

# --- Data Access Objects (DAO) ---
class UserDAO:
    def __init__(self, connection):
        self.conn = connection

    def create(self, email, password):
        user_id = str(uuid.uuid4())
        pwd_hash = hashlib.sha256(password.encode()).hexdigest()
        created = datetime.datetime.utcnow().isoformat()
        sql = "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)"
        self.conn.cursor().execute(sql, (user_id, email, pwd_hash, 1, created))
        return user_id

    def findById(self, user_id):
        cursor = self.conn.cursor()
        cursor.execute("SELECT * FROM users WHERE id = ?", (user_id,))
        return cursor.fetchone()

    def assignRole(self, userId, roleId):
        sql = "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)"
        self.conn.cursor().execute(sql, (userId, roleId))

    def find(self, filters=None):
        query = "SELECT DISTINCT u.* FROM users u"
        params = []
        if filters:
            joins = []
            wheres = []
            if 'roleName' in filters:
                joins.append("JOIN user_roles ur ON u.id = ur.user_id JOIN roles r ON ur.role_id = r.id")
                wheres.append("r.name = ?")
                params.append(filters['roleName'].value)
            if 'isActive' in filters:
                wheres.append("u.is_active = ?")
                params.append(1 if filters['isActive'] else 0)
            
            if joins: query += " " + " ".join(joins)
            if wheres: query += " WHERE " + " AND ".join(wheres)

        cursor = self.conn.cursor()
        cursor.execute(query, params)
        return cursor.fetchall()

class PostDAO:
    def __init__(self, connection):
        self.conn = connection

    def create(self, userId, title, content, status):
        post_id = str(uuid.uuid4())
        sql = "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)"
        self.conn.cursor().execute(sql, (post_id, userId, title, content, status.value))
        return post_id

    def findByUser(self, userId):
        cursor = self.conn.cursor()
        cursor.execute("SELECT * FROM posts WHERE user_id = ?", (userId,))
        return cursor.fetchall()

class RoleDAO:
    def __init__(self, connection):
        self.conn = connection

    def getOrCreate(self, roleType):
        cursor = self.conn.cursor()
        cursor.execute("SELECT id FROM roles WHERE name = ?", (roleType.value,))
        role = cursor.fetchone()
        if role:
            return role['id']
        else:
            cursor.execute("INSERT INTO roles (name) VALUES (?)", (roleType.value,))
            return cursor.lastrowid

# --- Main Application Logic ---
def main():
    DB_FILE = "dao_app.db"
    if os.path.exists(DB_FILE):
        os.remove(DB_FILE)

    dbManager = DatabaseManager(DB_FILE)
    dbManager.runMigrations()
    
    conn = dbManager.getConnection()
    userDao = UserDAO(conn)
    postDao = PostDAO(conn)
    roleDao = RoleDAO(conn)

    print("\n1. Creating users and assigning roles...")
    admin_role_id = roleDao.getOrCreate(RoleType.ADMIN)
    user_role_id = roleDao.getOrCreate(RoleType.USER)
    
    user1_id = userDao.create("charlie@example.com", "pass1")
    user2_id = userDao.create("diana@example.com", "pass2")
    
    userDao.assignRole(user1_id, admin_role_id)
    userDao.assignRole(user2_id, user_role_id)
    conn.commit()
    print(f"Users created: {user1_id}, {user2_id}")

    print("\n2. Creating posts (One-to-Many)...")
    postDao.create(user1_id, "Admin Post", "Content by admin.", PostStatus.PUBLISHED)
    postDao.create(user1_id, "Admin Draft", "...", PostStatus.DRAFT)
    conn.commit()
    
    user1_posts = postDao.findByUser(user1_id)
    print(f"User {user1_id} has {len(user1_posts)} posts.")

    print("\n3. Query building with filters...")
    admins = userDao.find(filters={'roleName': RoleType.ADMIN, 'isActive': True})
    print(f"Found {len(admins)} active admin(s). Email: {admins[0]['email']}")

    print("\n4. Transaction demo...")
    try:
        with dbManager.transaction():
            print("  - Inside transaction: creating a post for Diana.")
            postDao.create(user2_id, "Transactional Post", "This might not be saved.", PostStatus.DRAFT)
            print("  - Simulating failure...")
            raise RuntimeError("Something went wrong!")
    except RuntimeError as e:
        print(f"  - Caught expected error: {e}")

    diana_posts = postDao.findByUser(user2_id)
    print(f"After failed transaction, Diana has {len(diana_posts)} posts (should be 0).")

    dbManager.closeConnection()
    os.remove(DB_FILE)

if __name__ == "__main__":
    main()