<?php

// Variation 4: The "Pragmatic / Minimalist" Developer
// Style: Single-file script, concise, fewer abstractions, functional approach.

// --- Simple Data Structures ---
class User {
    public $id, $email, $password_hash, $role, $is_active, $created_at;
}
class Post {
    public $id, $user_id, $title, $content, $status;
}

// --- Global "Database" and "Cache" Instances ---
$db_users = [];
$db_posts = [];

function seed_db() {
    global $db_users;
    $uid = '44444444-4444-4444-4444-444444444444';
    $user = new User();
    $user->id = $uid;
    $user->email = 'minimal@example.com';
    $user->password_hash = 'hash4';
    $user->role = 'USER';
    $user->is_active = true;
    $user->created_at = time();
    $db_users[$uid] = $user;
}

// --- LRU Cache Implementation ---
class SimpleLruCache {
    private $capacity;
    private $map = []; // key => node
    private $head = null; // Most recently used
    private $tail = null; // Least recently used

    public function __construct($cap) {
        $this->capacity = $cap;
    }

    public function get($key) {
        if (!isset($this->map[$key])) return null;

        $node = $this->map[$key];
        
        // Check TTL
        if ($node['expires_at'] < time()) {
            $this->del($key);
            return null;
        }

        $this->moveToHead($node);
        return $node['value'];
    }

    public function set($key, $value, $ttl = 60) {
        if (isset($this->map[$key])) {
            $node = $this->map[$key];
            $node['value'] = $value;
            $node['expires_at'] = time() + $ttl;
            $this->moveToHead($node);
            return;
        }

        if (count($this->map) >= $this->capacity) {
            $this->evict();
        }

        $newNode = [
            'key' => $key,
            'value' => $value,
            'expires_at' => time() + $ttl,
            'prev' => null,
            'next' => $this->head
        ];

        if ($this->head) {
            $this->head['prev'] = &$newNode;
        }
        $this->head = &$newNode;
        if (!$this->tail) {
            $this->tail = &$newNode;
        }
        $this->map[$key] = &$newNode;
        // Unset to break reference cycle for garbage collection if needed
        unset($newNode);
    }

    public function del($key) {
        if (!isset($this->map[$key])) return;

        $node = $this->map[$key];
        
        if ($node['prev']) {
            $node['prev']['next'] = $node['next'];
        } else {
            $this->head = $node['next'];
        }

        if ($node['next']) {
            $node['next']['prev'] = $node['prev'];
        } else {
            $this->tail = $node['prev'];
        }

        unset($this->map[$key]);
    }

    private function moveToHead(&$node) {
        if ($node === $this->head) return;

        // Detach
        $node['prev']['next'] = $node['next'];
        if ($node['next']) {
            $node['next']['prev'] = $node['prev'];
        } else {
            $this->tail = $node['prev'];
        }

        // Attach to head
        $node['next'] = $this->head;
        $node['prev'] = null;
        $this->head['prev'] = &$node;
        $this->head = &$node;
    }

    private function evict() {
        if (!$this->tail) return;
        $this->del($this->tail['key']);
    }
}

// --- Application Logic Functions ---

// Cache-Aside
function find_user($id, SimpleLruCache $cache) {
    global $db_users;
    $key = "user:{$id}";
    
    $user = $cache->get($key);
    if ($user) {
        echo "CACHE HIT: Found user {$id}\n";
        return $user;
    }

    echo "CACHE MISS: Looking for user {$id} in DB\n";
    usleep(50000); // Simulate DB latency
    $user = $db_users[$id] ?? null;

    if ($user) {
        echo "DB FOUND: Caching user {$id}\n";
        $cache->set($key, $user, 120); // 2 minute TTL
    }
    return $user;
}

// Cache Invalidation
function save_user(User $user, SimpleLruCache $cache) {
    global $db_users;
    echo "DB WRITE: Saving user {$user->id}\n";
    usleep(50000);
    $db_users[$user->id] = $user;
    
    $key = "user:{$user->id}";
    echo "CACHE INVALIDATE: Deleting key {$key}\n";
    $cache->del($key);
}

// --- Main Execution ---

seed_db();
$lru_cache = new SimpleLruCache(5);
$user_id = '44444444-4444-4444-4444-444444444444';

echo "--- Run 1: Initial fetch ---\n";
$user1 = find_user($user_id, $lru_cache);
print_r($user1);

echo "\n--- Run 2: Fetch from cache ---\n";
$user2 = find_user($user_id, $lru_cache);
print_r($user2);

echo "\n--- Run 3: Update and invalidate ---\n";
$user1->is_active = false;
save_user($user1, $lru_cache);

echo "\n--- Run 4: Fetch after invalidation ---\n";
$user3 = find_user($user_id, $lru_cache);
print_r($user3);

?>