<?php
// Variation 4: Active Record (Lightweight Implementation)
// This pattern puts data access logic directly into the model classes.
// Models are "smart" and know how to persist themselves. It's convenient but can
// violate the Single Responsibility Principle.
// Naming convention: PascalCase for classes, camelCase for methods.

// --- Helper & Core Classes ---

/**
 * Generates a version 4 UUID.
 */
function generate_uuid_v4() {
    return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x',
        mt_rand(0, 0xffff), mt_rand(0, 0xffff),
        mt_rand(0, 0xffff),
        mt_rand(0, 0x0fff) | 0x4000,
        mt_rand(0, 0x3fff) | 0x8000,
        mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)
    );
}

class MigrationV4 {
    public static function run(PDO $pdo): void {
        $pdo->exec("CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, is_active INTEGER NOT NULL, created_at TEXT NOT NULL);");
        $pdo->exec("CREATE TABLE IF NOT EXISTS posts (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, title TEXT NOT NULL, content TEXT, status TEXT NOT NULL, FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE);");
        $pdo->exec("CREATE TABLE IF NOT EXISTS roles (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE);");
        $pdo->exec("CREATE TABLE IF NOT EXISTS user_roles (user_id TEXT NOT NULL, role_id INTEGER NOT NULL, PRIMARY KEY (user_id, role_id), FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE);");
        $pdo->exec("INSERT OR IGNORE INTO roles (id, name) VALUES (1, 'ADMIN'), (2, 'USER');");
    }
}

// --- Base Active Record Class ---

abstract class ActiveRecord {
    protected static ?PDO $pdo = null;
    protected static string $tableName;
    protected static string $primaryKey = 'id';
    protected array $attributes = [];
    private bool $isNewRecord = true;

    public function __construct(array $attributes = []) {
        $this->attributes = $attributes;
        if (!empty($attributes[static::$primaryKey])) {
            $this->isNewRecord = false;
        }
    }

    public static function getDb(): PDO {
        if (self::$pdo === null) {
            self::$pdo = new PDO('sqlite::memory:');
            self::$pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
            self::$pdo->exec('PRAGMA foreign_keys = ON;');
        }
        return self::$pdo;
    }

    public function __set($name, $value) { $this->attributes[$name] = $value; }
    public function __get($name) { return $this->attributes[$name] ?? null; }

    public static function find(string $id): ?static {
        $stmt = self::getDb()->prepare("SELECT * FROM " . static::$tableName . " WHERE " . static::$primaryKey . " = ?");
        $stmt->execute([$id]);
        $data = $stmt->fetch(PDO::FETCH_ASSOC);
        return $data ? new static($data) : null;
    }

    public static function where(array $conditions): array {
        $sql = "SELECT * FROM " . static::$tableName . " WHERE ";
        $params = [];
        $clauses = [];
        foreach ($conditions as $key => $value) {
            $clauses[] = "$key = ?";
            $params[] = $value;
        }
        $sql .= implode(' AND ', $clauses);
        $stmt = self::getDb()->prepare($sql);
        $stmt->execute($params);
        $results = [];
        foreach ($stmt->fetchAll(PDO::FETCH_ASSOC) as $row) {
            $results[] = new static($row);
        }
        return $results;
    }

    public function save(): bool {
        return $this->isNewRecord ? $this->insert() : $this->update();
    }

    protected function insert(): bool {
        if (empty($this->attributes[static::$primaryKey])) {
            $this->attributes[static::$primaryKey] = generate_uuid_v4();
        }
        $columns = array_keys($this->attributes);
        $placeholders = array_fill(0, count($columns), '?');
        $sql = "INSERT INTO " . static::$tableName . " (" . implode(', ', $columns) . ") VALUES (" . implode(', ', $placeholders) . ")";
        $stmt = self::getDb()->prepare($sql);
        $success = $stmt->execute(array_values($this->attributes));
        if ($success) {
            $this->isNewRecord = false;
        }
        return $success;
    }

    protected function update(): bool {
        $pk = static::$primaryKey;
        $id = $this->attributes[$pk];
        $fields = $this->attributes;
        unset($fields[$pk]);
        $setClauses = [];
        foreach ($fields as $key => $value) {
            $setClauses[] = "$key = ?";
        }
        $sql = "UPDATE " . static::$tableName . " SET " . implode(', ', $setClauses) . " WHERE $pk = ?";
        $params = array_values($fields);
        $params[] = $id;
        $stmt = self::getDb()->prepare($sql);
        return $stmt->execute($params);
    }
    
    public function delete(): bool {
        $stmt = self::getDb()->prepare("DELETE FROM " . static::$tableName . " WHERE " . static::$primaryKey . " = ?");
        return $stmt->execute([$this->attributes[static::$primaryKey]]);
    }

    public static function beginTransaction() { self::getDb()->beginTransaction(); }
    public static function commit() { self::getDb()->commit(); }
    public static function rollBack() { self::getDb()->rollBack(); }
}

// --- Model Implementations ---

class UserV4 extends ActiveRecord {
    protected static string $tableName = 'users';

    public function setPassword(string $password) {
        $this->password_hash = password_hash($password, PASSWORD_DEFAULT);
    }

    // One-to-Many relationship
    public function posts(): array {
        return PostV4::where(['user_id' => $this->id]);
    }

    // Many-to-Many relationship
    public function roles(): array {
        $sql = "SELECT r.* FROM roles r JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = ?";
        $stmt = self::getDb()->prepare($sql);
        $stmt->execute([$this->id]);
        $roles = [];
        foreach ($stmt->fetchAll(PDO::FETCH_ASSOC) as $row) {
            $roles[] = new RoleV4($row);
        }
        return $roles;
    }

    public function assignRole(RoleV4 $role): void {
        $stmt = self::getDb()->prepare("INSERT OR IGNORE INTO user_roles (user_id, role_id) VALUES (?, ?)");
        $stmt->execute([$this->id, $role->id]);
    }
}

class PostV4 extends ActiveRecord {
    protected static string $tableName = 'posts';
}

class RoleV4 extends ActiveRecord {
    protected static string $tableName = 'roles';
    protected static string $primaryKey = 'id';
}

// --- Main Execution ---
if (php_sapi_name() == 'cli' && realpath($argv[0]) == realpath(__FILE__)) {

    echo "--- Variation 4: Active Record Pattern ---\n";

    $db = ActiveRecord::getDb();
    MigrationV4::run($db);

    // 1. CRUD Operations
    echo "\n1. CRUD Operations:\n";
    $user = new UserV4();
    $user->email = 'alice@example.com';
    $user->setPassword('password123');
    $user->is_active = 1;
    $user->created_at = date('Y-m-d H:i:s');
    $user->save();
    echo "Created user with ID: {$user->id}\n";
    
    $foundUser = UserV4::find($user->id);
    echo "Found user with email: {$foundUser->email}\n";
    $foundUser->delete();
    echo "User deleted. Found again? " . (is_null(UserV4::find($user->id)) ? "No" : "Yes") . "\n";
    $user->save(); // Re-save for next steps

    // 2. One-to-Many Relationship (User -> Posts)
    echo "\n2. One-to-Many (User -> Posts):\n";
    $post = new PostV4(['user_id' => $user->id, 'title' => 'Active Record is Fun', 'content' => '...', 'status' => 'PUBLISHED']);
    $post->save();
    $userPosts = $user->posts();
    echo "User {$user->id} has " . count($userPosts) . " post(s). Title: {$userPosts[0]->title}\n";

    // 3. Many-to-Many Relationship (Users <-> Roles)
    echo "\n3. Many-to-Many (Users <-> Roles):\n";
    $adminRole = RoleV4::find(1);
    $user->assignRole($adminRole);
    $userRoles = $user->roles();
    $roleNames = array_map(fn($r) => $r->name, $userRoles);
    echo "User {$user->id} has roles: " . implode(', ', $roleNames) . "\n";

    // 4. Query Building with Filters
    echo "\n4. Query Building with Filters:\n";
    $activeUsers = UserV4::where(['is_active' => 1]);
    echo "Found " . count($activeUsers) . " active user(s).\n";

    // 5. Transactions and Rollbacks
    echo "\n5. Transactions and Rollbacks:\n";
    try {
        UserV4::beginTransaction();
        echo "Creating Bob and his post in a transaction...\n";
        $bob = new UserV4(['email' => 'bob@example.com', 'is_active' => 1, 'created_at' => date('Y-m-d H:i:s')]);
        $bob->setPassword('pass');
        $bob->save();

        if ($bob->email === 'bob@example.com') {
            // throw new Exception("Simulated failure!");
        }

        $bobPost = new PostV4(['user_id' => $bob->id, 'title' => 'Bob\'s Post', 'content' => '...']);
        $bobPost->save();

        UserV4::commit();
        echo "Transaction committed successfully.\n";
    } catch (Exception $e) {
        UserV4::rollBack();
        echo "Transaction rolled back: " . $e->getMessage() . "\n";
    }
    $bobCheck = UserV4::where(['email' => 'bob@example.com']);
    echo "Bob exists after transaction: " . (empty($bobCheck) ? "No" : "Yes") . "\n";
}
?>