<?php
// Variation 3: Repository Pattern
// This pattern mediates between the domain and data mapping layers.
// It works with domain objects (Entities) and provides a collection-like interface.
// Naming convention: PascalCase for classes, camelCase for methods.

// --- Helper & Core Classes ---

/**
 * Generates a version 4 UUID.
 */
function generate_uuid_v3() {
    return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x',
        mt_rand(0, 0xffff), mt_rand(0, 0xffff),
        mt_rand(0, 0xffff),
        mt_rand(0, 0x0fff) | 0x4000,
        mt_rand(0, 0x3fff) | 0x8000,
        mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)
    );
}

class DatabaseConnectionV3 {
    private static ?PDO $pdo = null;
    public static function get(): PDO {
        if (self::$pdo === null) {
            self::$pdo = new PDO('sqlite::memory:');
            self::$pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
            self::$pdo->exec('PRAGMA foreign_keys = ON;');
        }
        return self::$pdo;
    }
}

class SchemaManagerV3 {
    public static function run(PDO $pdo): void {
        $pdo->exec("CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, is_active INTEGER NOT NULL, created_at TEXT NOT NULL);");
        $pdo->exec("CREATE TABLE IF NOT EXISTS posts (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, title TEXT NOT NULL, content TEXT, status TEXT NOT NULL, FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE);");
        $pdo->exec("CREATE TABLE IF NOT EXISTS roles (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE);");
        $pdo->exec("CREATE TABLE IF NOT EXISTS user_roles (user_id TEXT NOT NULL, role_id INTEGER NOT NULL, PRIMARY KEY (user_id, role_id), FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE);");
        $pdo->exec("INSERT OR IGNORE INTO roles (id, name) VALUES (1, 'ADMIN'), (2, 'USER');");
    }
}

// --- Domain Entities (POPOs) ---

class UserV3 {
    public string $id;
    public string $email;
    public string $passwordHash;
    public bool $isActive;
    public string $createdAt;
    private array $roles = [];

    public function setRoles(array $roles): void { $this->roles = $roles; }
    public function getRoles(): array { return $this->roles; }
}

class PostV3 {
    public string $id;
    public string $userId;
    public string $title;
    public string $content;
    public string $status; // DRAFT, PUBLISHED
}

class RoleV3 {
    public int $id;
    public string $name;
}

// --- Repositories ---

abstract class BaseRepository {
    protected PDO $pdo;
    public function __construct() {
        $this->pdo = DatabaseConnectionV3::get();
    }
}

class UserRepositoryV3 extends BaseRepository {
    public function findById(string $id): ?UserV3 {
        $stmt = $this->pdo->prepare("SELECT * FROM users WHERE id = ?");
        $stmt->execute([$id]);
        $data = $stmt->fetch(PDO::FETCH_ASSOC);
        return $data ? $this->hydrate($data) : null;
    }

    public function findBy(array $criteria): array {
        $sql = "SELECT * FROM users WHERE 1=1";
        $params = [];
        if (isset($criteria['isActive'])) {
            $sql .= " AND is_active = :is_active";
            $params[':is_active'] = (int)$criteria['isActive'];
        }
        if (!empty($criteria['email'])) {
            $sql .= " AND email = :email";
            $params[':email'] = $criteria['email'];
        }
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute($params);
        $users = [];
        foreach ($stmt->fetchAll(PDO::FETCH_ASSOC) as $row) {
            $users[] = $this->hydrate($row);
        }
        return $users;
    }

    public function save(UserV3 $user): void {
        if (empty($user->id)) { // Create
            $user->id = generate_uuid_v3();
            $sql = "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)";
            $stmt = $this->pdo->prepare($sql);
            $stmt->execute([$user->id, $user->email, $user->passwordHash, (int)$user->isActive, $user->createdAt]);
        } else { // Update
            $sql = "UPDATE users SET email = ?, password_hash = ?, is_active = ? WHERE id = ?";
            $stmt = $this->pdo->prepare($sql);
            $stmt->execute([$user->email, $user->passwordHash, (int)$user->isActive, $user->id]);
        }
    }

    public function assignRole(UserV3 $user, RoleV3 $role): void {
        $stmt = $this->pdo->prepare("INSERT OR IGNORE INTO user_roles (user_id, role_id) VALUES (?, ?)");
        $stmt->execute([$user->id, $role->id]);
    }

    public function findUserRoles(UserV3 $user): array {
        $sql = "SELECT r.id, r.name FROM roles r JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = ?";
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute([$user->id]);
        $roles = [];
        foreach ($stmt->fetchAll(PDO::FETCH_ASSOC) as $data) {
            $role = new RoleV3();
            $role->id = $data['id'];
            $role->name = $data['name'];
            $roles[] = $role;
        }
        return $roles;
    }

    private function hydrate(array $data): UserV3 {
        $user = new UserV3();
        $user->id = $data['id'];
        $user->email = $data['email'];
        $user->passwordHash = $data['password_hash'];
        $user->isActive = (bool)$data['is_active'];
        $user->createdAt = $data['created_at'];
        return $user;
    }
}

class PostRepositoryV3 extends BaseRepository {
    public function findByUserId(string $userId): array {
        $stmt = $this->pdo->prepare("SELECT * FROM posts WHERE user_id = ?");
        $stmt->execute([$userId]);
        $posts = [];
        foreach ($stmt->fetchAll(PDO::FETCH_ASSOC) as $row) {
            $posts[] = $this->hydrate($row);
        }
        return $posts;
    }

    public function save(PostV3 $post): void {
        if (empty($post->id)) {
            $post->id = generate_uuid_v3();
            $sql = "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)";
            $stmt = $this->pdo->prepare($sql);
            $stmt->execute([$post->id, $post->userId, $post->title, $post->content, $post->status]);
        } // Update logic omitted for brevity
    }

    private function hydrate(array $data): PostV3 {
        $post = new PostV3();
        $post->id = $data['id'];
        $post->userId = $data['user_id'];
        $post->title = $data['title'];
        $post->content = $data['content'];
        $post->status = $data['status'];
        return $post;
    }
}

// --- Main Execution ---
if (php_sapi_name() == 'cli' && realpath($argv[0]) == realpath(__FILE__)) {

    echo "--- Variation 3: Repository Pattern ---\n";

    $db = DatabaseConnectionV3::get();
    SchemaManagerV3::run($db);

    $userRepo = new UserRepositoryV3();
    $postRepo = new PostRepositoryV3();

    // 1. CRUD Operations
    echo "\n1. CRUD Operations:\n";
    $user = new UserV3();
    $user->email = 'alice@example.com';
    $user->passwordHash = password_hash('password123', PASSWORD_DEFAULT);
    $user->isActive = true;
    $user->createdAt = date('Y-m-d H:i:s');
    $userRepo->save($user);
    echo "Created user with ID: {$user->id}\n";
    
    $foundUser = $userRepo->findById($user->id);
    echo "Found user with email: {$foundUser->email}\n";

    // 2. One-to-Many Relationship (User -> Posts)
    echo "\n2. One-to-Many (User -> Posts):\n";
    $post1 = new PostV3();
    $post1->userId = $user->id;
    $post1->title = 'My Life with Repositories';
    $post1->content = 'It is great.';
    $post1->status = 'PUBLISHED';
    $postRepo->save($post1);
    $userPosts = $postRepo->findByUserId($user->id);
    echo "Found " . count($userPosts) . " post(s) for user {$user->id}. Title: {$userPosts[0]->title}\n";

    // 3. Many-to-Many Relationship (Users <-> Roles)
    echo "\n3. Many-to-Many (Users <-> Roles):\n";
    $adminRole = new RoleV3(); $adminRole->id = 1;
    $userRepo->assignRole($user, $adminRole);
    $user->setRoles($userRepo->findUserRoles($user));
    $roleNames = array_map(fn($r) => $r->name, $user->getRoles());
    echo "User {$user->id} has roles: " . implode(', ', $roleNames) . "\n";

    // 4. Query Building with Filters
    echo "\n4. Query Building with Filters:\n";
    $filteredUsers = $userRepo->findBy(['isActive' => true, 'email' => 'alice@example.com']);
    echo "Found " . count($filteredUsers) . " user(s) matching criteria.\n";

    // 5. Transactions and Rollbacks
    echo "\n5. Transactions and Rollbacks:\n";
    try {
        $db->beginTransaction();
        echo "Creating Bob and his post in a transaction...\n";
        $bob = new UserV3();
        $bob->email = 'bob@example.com';
        $bob->passwordHash = password_hash('pass', PASSWORD_DEFAULT);
        $bob->isActive = true;
        $bob->createdAt = date('Y-m-d H:i:s');
        $userRepo->save($bob);

        // This would cause a rollback if uncommented
        // throw new Exception("Something went wrong!");

        $bobPost = new PostV3();
        $bobPost->userId = $bob->id;
        $bobPost->title = 'Bob\'s Post';
        $bobPost->content = 'Content';
        $bobPost->status = 'DRAFT';
        $postRepo->save($bobPost);

        $db->commit();
        echo "Transaction committed successfully.\n";
    } catch (Exception $e) {
        $db->rollBack();
        echo "Transaction rolled back: " . $e->getMessage() . "\n";
    }
    $bobCheck = $userRepo->findBy(['email' => 'bob@example.com']);
    echo "Bob exists after transaction: " . (empty($bobCheck) ? "No" : "Yes") . "\n";
}
?>