<?php
// Variation 1: Procedural / Functional Style
// This style uses a set of functions to interact with the database.
// It's straightforward but can become hard to maintain in large applications.
// Naming convention: snake_case.

// --- Helper Functions ---

/**
 * Generates a version 4 UUID.
 * @return string
 */
function generate_uuid_v1() {
    return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x',
        mt_rand(0, 0xffff), mt_rand(0, 0xffff),
        mt_rand(0, 0xffff),
        mt_rand(0, 0x0fff) | 0x4000,
        mt_rand(0, 0x3fff) | 0x8000,
        mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)
    );
}

/**
 * Establishes a database connection using PDO.
 * For this example, an in-memory SQLite database is used.
 * @return PDO
 */
function get_db_connection_v1() {
    try {
        $pdo = new PDO('sqlite::memory:');
        $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
        $pdo->exec('PRAGMA foreign_keys = ON;');
        return $pdo;
    } catch (PDOException $e) {
        die("Database connection failed: " . $e->getMessage());
    }
}

// --- Database Migrations ---

/**
 * Runs database migrations to create the necessary tables.
 * Checks for table existence before creation.
 * @param PDO $pdo
 */
function run_migrations_v1(PDO $pdo) {
    $pdo->exec("
        CREATE TABLE IF NOT EXISTS users (
            id TEXT PRIMARY KEY,
            email TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            is_active INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL
        );
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS posts (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            title TEXT NOT NULL,
            content TEXT,
            status TEXT NOT NULL CHECK(status IN ('DRAFT', 'PUBLISHED')),
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        );
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS roles (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE
        );
    ");

    $pdo->exec("
        CREATE TABLE IF NOT EXISTS user_roles (
            user_id TEXT NOT NULL,
            role_id INTEGER NOT NULL,
            PRIMARY KEY (user_id, role_id),
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
        );
    ");

    // Seed roles
    $pdo->exec("INSERT OR IGNORE INTO roles (id, name) VALUES (1, 'ADMIN'), (2, 'USER');");
}

// --- CRUD and Relationship Functions ---

// User Functions
function create_user_v1(PDO $pdo, string $email, string $password): string {
    $uuid = generate_uuid_v1();
    $sql = "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)";
    $stmt = $pdo->prepare($sql);
    $stmt->execute([
        $uuid,
        $email,
        password_hash($password, PASSWORD_DEFAULT),
        1,
        date('Y-m-d H:i:s')
    ]);
    return $uuid;
}

function find_user_by_id_v1(PDO $pdo, string $id): ?array {
    $stmt = $pdo->prepare("SELECT * FROM users WHERE id = ?");
    $stmt->execute([$id]);
    $user = $stmt->fetch(PDO::FETCH_ASSOC);
    return $user ?: null;
}

// Post Functions (One-to-Many)
function create_post_v1(PDO $pdo, string $userId, string $title, string $content, string $status = 'DRAFT'): string {
    $uuid = generate_uuid_v1();
    $sql = "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)";
    $stmt = $pdo->prepare($sql);
    $stmt->execute([$uuid, $userId, $title, $content, $status]);
    return $uuid;
}

function find_posts_by_user_id_v1(PDO $pdo, string $userId): array {
    $stmt = $pdo->prepare("SELECT * FROM posts WHERE user_id = ? ORDER BY title ASC");
    $stmt->execute([$userId]);
    return $stmt->fetchAll(PDO::FETCH_ASSOC);
}

// Role Functions (Many-to-Many)
function assign_role_to_user_v1(PDO $pdo, string $userId, int $roleId): void {
    $sql = "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)";
    $stmt = $pdo->prepare($sql);
    $stmt->execute([$userId, $roleId]);
}

function get_user_roles_v1(PDO $pdo, string $userId): array {
    $sql = "SELECT r.name FROM roles r JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = ?";
    $stmt = $pdo->prepare($sql);
    $stmt->execute([$userId]);
    return $stmt->fetchAll(PDO::FETCH_COLUMN, 0);
}

// --- Query Builder with Filters ---

function find_users_with_filters_v1(PDO $pdo, array $filters): array {
    $sql = "SELECT * FROM users WHERE 1=1";
    $params = [];

    if (!empty($filters['is_active'])) {
        $sql .= " AND is_active = :is_active";
        $params[':is_active'] = (int)$filters['is_active'];
    }
    if (!empty($filters['email_like'])) {
        $sql .= " AND email LIKE :email_like";
        $params[':email_like'] = '%' . $filters['email_like'] . '%';
    }

    $stmt = $pdo->prepare($sql);
    $stmt->execute($params);
    return $stmt->fetchAll(PDO::FETCH_ASSOC);
}

// --- Transaction Example ---

function create_user_with_post_and_role_transaction_v1(PDO $pdo, string $email, string $password, string $postTitle): bool {
    try {
        $pdo->beginTransaction();

        $userId = create_user_v1($pdo, $email, $password);
        create_post_v1($pdo, $userId, $postTitle, 'Content of first post.');
        assign_role_to_user_v1($pdo, $userId, 2); // Assign 'USER' role

        // Simulate an error to test rollback
        if ($email === 'error@example.com') {
            throw new Exception("Simulated error to trigger rollback.");
        }

        $pdo->commit();
        return true;
    } catch (Exception $e) {
        $pdo->rollBack();
        echo "Transaction failed: " . $e->getMessage() . "\n";
        return false;
    }
}


// --- Main Execution ---
if (php_sapi_name() == 'cli' && realpath($argv[0]) == realpath(__FILE__)) {
    
    echo "--- Variation 1: Procedural Style ---\n";

    $db = get_db_connection_v1();
    run_migrations_v1($db);

    // 1. CRUD Operations
    echo "\n1. CRUD Operations:\n";
    $userId = create_user_v1($db, 'alice@example.com', 'password123');
    echo "Created user with ID: $userId\n";
    $user = find_user_by_id_v1($db, $userId);
    echo "Found user: " . print_r($user, true);

    // 2. One-to-Many Relationship (User -> Posts)
    echo "\n2. One-to-Many (User -> Posts):\n";
    $postId1 = create_post_v1($db, $userId, 'Alice\'s First Post', 'Content here.');
    $postId2 = create_post_v1($db, $userId, 'Alice\'s Second Post', 'More content.');
    $posts = find_posts_by_user_id_v1($db, $userId);
    echo "Found " . count($posts) . " posts for user $userId:\n" . print_r($posts, true);

    // 3. Many-to-Many Relationship (Users <-> Roles)
    echo "\n3. Many-to-Many (Users <-> Roles):\n";
    assign_role_to_user_v1($db, $userId, 1); // ADMIN
    assign_role_to_user_v1($db, $userId, 2); // USER
    $roles = get_user_roles_v1($db, $userId);
    echo "User $userId has roles: " . implode(', ', $roles) . "\n";

    // 4. Query Building with Filters
    echo "\n4. Query Building with Filters:\n";
    create_user_v1($db, 'bob@example.com', 'password456');
    $filteredUsers = find_users_with_filters_v1($db, ['is_active' => true, 'email_like' => 'alice']);
    echo "Filtered users (active, email like 'alice'):\n" . print_r($filteredUsers, true);

    // 5. Transactions and Rollbacks
    echo "\n5. Transactions and Rollbacks:\n";
    echo "Attempting successful transaction...\n";
    create_user_with_post_and_role_transaction_v1($db, 'charlie@example.com', 'pass', 'Charlie\'s Post');
    $charlie = find_users_with_filters_v1($db, ['email_like' => 'charlie']);
    echo "Charlie created successfully: " . (count($charlie) > 0 ? 'Yes' : 'No') . "\n";

    echo "\nAttempting failing transaction...\n";
    create_user_with_post_and_role_transaction_v1($db, 'error@example.com', 'pass', 'Error Post');
    $errorUser = find_users_with_filters_v1($db, ['email_like' => 'error']);
    echo "User 'error@example.com' created (should be no): " . (count($errorUser) > 0 ? 'Yes' : 'No') . "\n";
}
?>