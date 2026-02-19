<?php
// Variation 2: Classic OOP with Data Access Objects (DAO)
// This pattern separates data persistence logic into dedicated DAO classes.
// It promotes separation of concerns, making the code more organized.
// Naming convention: PascalCase for classes, camelCase for methods.

// --- Helper & Core Classes ---

/**
 * Generates a version 4 UUID.
 */
function generate_uuid_v2() {
    return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x',
        mt_rand(0, 0xffff), mt_rand(0, 0xffff),
        mt_rand(0, 0xffff),
        mt_rand(0, 0x0fff) | 0x4000,
        mt_rand(0, 0x3fff) | 0x8000,
        mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)
    );
}

/**
 * Manages the database connection using a Singleton pattern.
 */
class DatabaseV2 {
    private static ?PDO $instance = null;

    private function __construct() {}
    private function __clone() {}

    public static function getInstance(): PDO {
        if (self::$instance === null) {
            try {
                self::$instance = new PDO('sqlite::memory:');
                self::$instance->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
                self::$instance->exec('PRAGMA foreign_keys = ON;');
            } catch (PDOException $e) {
                die("Database connection failed: " . $e->getMessage());
            }
        }
        return self::$instance;
    }
}

/**
 * Handles database schema migrations.
 */
class MigrationManagerV2 {
    private PDO $pdo;

    public function __construct(PDO $pdo) {
        $this->pdo = $pdo;
    }

    public function run(): void {
        $this->pdo->exec("CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, is_active INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL);");
        $this->pdo->exec("CREATE TABLE IF NOT EXISTS posts (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, title TEXT NOT NULL, content TEXT, status TEXT NOT NULL, FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE);");
        $this->pdo->exec("CREATE TABLE IF NOT EXISTS roles (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE);");
        $this->pdo->exec("CREATE TABLE IF NOT EXISTS user_roles (user_id TEXT NOT NULL, role_id INTEGER NOT NULL, PRIMARY KEY (user_id, role_id), FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE);");
        $this->pdo->exec("INSERT OR IGNORE INTO roles (id, name) VALUES (1, 'ADMIN'), (2, 'USER');");
    }
}

// --- Data Access Objects ---

class UserDAO {
    private PDO $pdo;

    public function __construct(PDO $pdo) {
        $this->pdo = $pdo;
    }

    public function create(array $userData): string {
        $uuid = generate_uuid_v2();
        $sql = "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)";
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute([
            $uuid,
            $userData['email'],
            password_hash($userData['password'], PASSWORD_DEFAULT),
            $userData['is_active'] ?? 1,
            date('Y-m-d H:i:s')
        ]);
        return $uuid;
    }

    public function findById(string $id): ?array {
        $stmt = $this->pdo->prepare("SELECT * FROM users WHERE id = ?");
        $stmt->execute([$id]);
        $user = $stmt->fetch(PDO::FETCH_ASSOC);
        return $user ?: null;
    }

    public function findByCriteria(array $criteria): array {
        $sql = "SELECT * FROM users WHERE 1=1";
        $params = [];
        if (isset($criteria['is_active'])) {
            $sql .= " AND is_active = :is_active";
            $params[':is_active'] = (int)$criteria['is_active'];
        }
        if (!empty($criteria['email'])) {
            $sql .= " AND email = :email";
            $params[':email'] = $criteria['email'];
        }
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute($params);
        return $stmt->fetchAll(PDO::FETCH_ASSOC);
    }
    
    public function delete(string $id): bool {
        $stmt = $this->pdo->prepare("DELETE FROM users WHERE id = ?");
        return $stmt->execute([$id]);
    }
}

class PostDAO {
    private PDO $pdo;

    public function __construct(PDO $pdo) {
        $this->pdo = $pdo;
    }

    public function create(array $postData): string {
        $uuid = generate_uuid_v2();
        $sql = "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)";
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute([$uuid, $postData['user_id'], $postData['title'], $postData['content'], $postData['status'] ?? 'DRAFT']);
        return $uuid;
    }

    public function findByUserId(string $userId): array {
        $stmt = $this->pdo->prepare("SELECT * FROM posts WHERE user_id = ?");
        $stmt->execute([$userId]);
        return $stmt->fetchAll(PDO::FETCH_ASSOC);
    }
}

class RoleDAO {
    private PDO $pdo;

    public function __construct(PDO $pdo) {
        $this->pdo = $pdo;
    }

    public function assignRoleToUser(string $userId, int $roleId): void {
        $stmt = $this->pdo->prepare("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)");
        $stmt->execute([$userId, $roleId]);
    }

    public function findRolesByUserId(string $userId): array {
        $sql = "SELECT r.* FROM roles r JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = ?";
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute([$userId]);
        return $stmt->fetchAll(PDO::FETCH_ASSOC);
    }
}

// --- Service Layer for Transactions ---

class UserServiceV2 {
    private PDO $pdo;
    private UserDAO $userDAO;
    private PostDAO $postDAO;
    private RoleDAO $roleDAO;

    public function __construct(PDO $pdo) {
        $this->pdo = $pdo;
        $this->userDAO = new UserDAO($pdo);
        $this->postDAO = new PostDAO($pdo);
        $this->roleDAO = new RoleDAO($pdo);
    }

    public function createUserWithDefaults(string $email, string $password, string $firstPostTitle): ?string {
        try {
            $this->pdo->beginTransaction();
            
            $userId = $this->userDAO->create(['email' => $email, 'password' => $password]);
            $this->postDAO->create(['user_id' => $userId, 'title' => $firstPostTitle, 'content' => '...']);
            $this->roleDAO->assignRoleToUser($userId, 2); // Default 'USER' role

            if ($email === 'fail@example.com') {
                throw new Exception("Simulated failure.");
            }

            $this->pdo->commit();
            return $userId;
        } catch (Exception $e) {
            $this->pdo->rollBack();
            echo "Transaction failed for $email: " . $e->getMessage() . "\n";
            return null;
        }
    }
}

// --- Main Execution ---
if (php_sapi_name() == 'cli' && realpath($argv[0]) == realpath(__FILE__)) {

    echo "--- Variation 2: DAO Pattern ---\n";

    $db = DatabaseV2::getInstance();
    (new MigrationManagerV2($db))->run();

    $userDAO = new UserDAO($db);
    $postDAO = new PostDAO($db);
    $roleDAO = new RoleDAO($db);

    // 1. CRUD Operations
    echo "\n1. CRUD Operations:\n";
    $userData = ['email' => 'alice@example.com', 'password' => 'password123'];
    $userId = $userDAO->create($userData);
    echo "Created user with ID: $userId\n";
    $user = $userDAO->findById($userId);
    echo "Found user: " . print_r($user, true);
    $userDAO->delete($userId);
    echo "User deleted. Found again? " . (is_null($userDAO->findById($userId)) ? "No" : "Yes") . "\n";
    $userId = $userDAO->create($userData); // Re-create for next steps

    // 2. One-to-Many Relationship (User -> Posts)
    echo "\n2. One-to-Many (User -> Posts):\n";
    $postDAO->create(['user_id' => $userId, 'title' => 'Post 1 by Alice', 'content' => '...']);
    $postDAO->create(['user_id' => $userId, 'title' => 'Post 2 by Alice', 'content' => '...']);
    $posts = $postDAO->findByUserId($userId);
    echo "Found " . count($posts) . " posts for Alice:\n" . print_r($posts, true);

    // 3. Many-to-Many Relationship (Users <-> Roles)
    echo "\n3. Many-to-Many (Users <-> Roles):\n";
    $roleDAO->assignRoleToUser($userId, 1); // ADMIN
    $roles = $roleDAO->findRolesByUserId($userId);
    echo "Alice has roles: " . implode(', ', array_column($roles, 'name')) . "\n";

    // 4. Query Building with Filters
    echo "\n4. Query Building with Filters:\n";
    $userDAO->create(['email' => 'bob@example.com', 'password' => 'pass', 'is_active' => 0]);
    $activeUsers = $userDAO->findByCriteria(['is_active' => 1]);
    echo "Found " . count($activeUsers) . " active user(s):\n" . print_r($activeUsers, true);

    // 5. Transactions and Rollbacks
    echo "\n5. Transactions and Rollbacks:\n";
    $userService = new UserServiceV2($db);
    echo "Attempting successful transaction...\n";
    $newUserId = $userService->createUserWithDefaults('charlie@example.com', 'pass', 'My First Post');
    echo "Charlie created with ID: $newUserId\n";

    echo "\nAttempting failing transaction...\n";
    $failedId = $userService->createUserWithDefaults('fail@example.com', 'pass', 'This will fail');
    $foundFailed = $userDAO->findByCriteria(['email' => 'fail@example.com']);
    echo "User 'fail@example.com' exists: " . (empty($foundFailed) ? "No" : "Yes") . "\n";
}
?>