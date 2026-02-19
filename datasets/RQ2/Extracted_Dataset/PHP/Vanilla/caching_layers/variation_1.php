<?php

// Variation 1: The "Classic OOP" Developer
// Style: Heavily object-oriented, clear separation of concerns, traditional class structures.

// --- Domain Model ---

// Using an Enum-like class for PHP < 8.1 compatibility
final class UserRole {
    const ADMIN = 'ADMIN';
    const USER = 'USER';
    private function __construct() {}
}

final class PostStatus {
    const DRAFT = 'DRAFT';
    const PUBLISHED = 'PUBLISHED';
    private function __construct() {}
}

class User {
    public $id;
    public $email;
    public $password_hash;
    public $role;
    public $is_active;
    public $created_at;

    public function __construct($id, $email, $password_hash, $role, $is_active, $created_at) {
        $this->id = $id;
        $this->email = $email;
        $this->password_hash = $password_hash;
        $this->role = $role;
        $this->is_active = $is_active;
        $this->created_at = $created_at;
    }
}

class Post {
    public $id;
    public $user_id;
    public $title;
    public $content;
    public $status;

    public function __construct($id, $user_id, $title, $content, $status) {
        $this->id = $id;
        $this->user_id = $user_id;
        $this->title = $title;
        $this->content = $content;
        $this->status = $status;
    }
}

// --- Caching Infrastructure ---

class LruCacheNode {
    public $key;
    public $value;
    public $expiresAt;
    public $prev = null;
    public $next = null;

    public function __construct($key, $value, $expiresAt) {
        $this->key = $key;
        $this->value = $value;
        $this->expiresAt = $expiresAt;
    }
}

class LruCache {
    private $capacity;
    private $map;
    private $head;
    private $tail;

    public function __construct($capacity = 100) {
        $this->capacity = $capacity;
        $this->map = [];
        // Dummy head and tail nodes
        $this->head = new LruCacheNode(null, null, null);
        $this->tail = new LruCacheNode(null, null, null);
        $this->head->next = $this->tail;
        $this->tail->prev = $this->head;
    }

    public function get($key) {
        if (!isset($this->map[$key])) {
            return null;
        }

        $node = $this->map[$key];

        // Time-based expiration check
        if ($node->expiresAt !== null && time() > $node->expiresAt) {
            $this->delete($key);
            return null;
        }

        $this->moveToFront($node);
        return $node->value;
    }

    public function set($key, $value, $ttlSeconds = 3600) {
        $expiresAt = ($ttlSeconds === null) ? null : time() + $ttlSeconds;

        if (isset($this->map[$key])) {
            $node = $this->map[$key];
            $node->value = $value;
            $node->expiresAt = $expiresAt;
            $this->moveToFront($node);
            return;
        }

        if (count($this->map) >= $this->capacity) {
            $this->evict();
        }

        $newNode = new LruCacheNode($key, $value, $expiresAt);
        $this->map[$key] = $newNode;
        $this->addToFront($newNode);
    }

    public function delete($key) {
        if (isset($this->map[$key])) {
            $node = $this->map[$key];
            $this->removeNode($node);
            unset($this->map[$key]);
        }
    }

    private function moveToFront(LruCacheNode $node) {
        $this->removeNode($node);
        $this->addToFront($node);
    }

    private function addToFront(LruCacheNode $node) {
        $node->next = $this->head->next;
        $node->prev = $this->head;
        $this->head->next->prev = $node;
        $this->head->next = $node;
    }

    private function removeNode(LruCacheNode $node) {
        $prevNode = $node->prev;
        $nextNode = $node->next;
        $prevNode->next = $nextNode;
        $nextNode->prev = $prevNode;
    }

    private function evict() {
        $lruNode = $this->tail->prev;
        if ($lruNode !== $this->head) {
            $this->delete($lruNode->key);
        }
    }
}

// --- Data Layer & Repositories ---

class MockDatabase {
    private static $instance = null;
    private $users = [];
    private $posts = [];

    private function __construct() {
        // Seed data
        $userId1 = '11111111-1111-1111-1111-111111111111';
        $this->users[$userId1] = new User($userId1, 'admin@example.com', 'hash1', UserRole::ADMIN, true, time());
        $postId1 = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
        $this->posts[$postId1] = new Post($postId1, $userId1, 'First Post', 'Content of the first post.', PostStatus::PUBLISHED);
    }

    public static function getInstance() {
        if (self::$instance == null) {
            self::$instance = new MockDatabase();
        }
        return self::$instance;
    }

    public function findUserById($id) {
        echo "--- DATABASE HIT: Fetching user {$id} ---\n";
        usleep(50000); // Simulate latency
        return isset($this->users[$id]) ? clone $this->users[$id] : null;
    }

    public function saveUser(User $user) {
        echo "--- DATABASE HIT: Saving user {$user->id} ---\n";
        usleep(50000);
        $this->users[$user->id] = $user;
    }
    
    public function findPostById($id) {
        echo "--- DATABASE HIT: Fetching post {$id} ---\n";
        usleep(50000);
        return isset($this->posts[$id]) ? clone $this->posts[$id] : null;
    }
}

class UserRepository {
    private $db;
    private $cache;

    public function __construct(MockDatabase $db, LruCache $cache) {
        $this->db = $db;
        $this->cache = $cache;
    }

    // Cache-Aside Pattern Implementation
    public function getUserById($id) {
        $cacheKey = "user:{$id}";
        $user = $this->cache->get($cacheKey);

        if ($user === null) {
            echo "CACHE MISS for user {$id}\n";
            $user = $this->db->findUserById($id);
            if ($user !== null) {
                $this->cache->set($cacheKey, $user, 60); // Cache for 60 seconds
            }
        } else {
            echo "CACHE HIT for user {$id}\n";
        }

        return $user;
    }

    // Cache Invalidation Strategy
    public function updateUser(User $user) {
        $this->db->saveUser($user);
        $cacheKey = "user:{$user->id}";
        $this->cache->delete($cacheKey);
        echo "INVALIDATED CACHE for user {$user->id}\n";
    }
}

// --- Main Execution ---

$cache = new LruCache(10);
$db = MockDatabase::getInstance();
$userRepo = new UserRepository($db, $cache);

$userId = '11111111-1111-1111-1111-111111111111';

echo "1. First request for user:\n";
$user1 = $userRepo->getUserById($userId);
print_r($user1);

echo "\n2. Second request for user (should be cached):\n";
$user2 = $userRepo->getUserById($userId);
print_r($user2);

echo "\n3. Updating user's active status:\n";
$user1->is_active = false;
$userRepo->updateUser($user1);

echo "\n4. Third request for user (should be a miss, then re-cached):\n";
$user3 = $userRepo->getUserById($userId);
print_r($user3);

?>