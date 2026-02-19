<?php

// Variation 2: The "Service-Oriented / Procedural" Developer
// Style: Uses functions and a manager class. Data is represented as associative arrays.

// --- Domain Constants ---
define('ROLE_ADMIN', 'ADMIN');
define('ROLE_USER', 'USER');
define('STATUS_DRAFT', 'DRAFT');
define('STATUS_PUBLISHED', 'PUBLISHED');

// --- Mock Data Store ---
class DataStore {
    private static $users = [];
    private static $posts = [];

    public static function init() {
        $user_id = '22222222-2222-2222-2222-222222222222';
        self::$users[$user_id] = [
            'id' => $user_id,
            'email' => 'procedural@example.com',
            'password_hash' => 'hash2',
            'role' => ROLE_USER,
            'is_active' => true,
            'created_at' => time()
        ];
        $post_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
        self::$posts[$post_id] = [
            'id' => $post_id,
            'user_id' => $user_id,
            'title' => 'A Procedural Post',
            'content' => 'Content here.',
            'status' => STATUS_PUBLISHED
        ];
    }

    public static function get_user_from_db($id) {
        echo "--- DB QUERY: SELECT * FROM users WHERE id = '{$id}' ---\n";
        usleep(50000); // Simulate I/O
        return self::$users[$id] ?? null;
    }

    public static function update_user_in_db($user_data) {
        echo "--- DB QUERY: UPDATE users SET ... WHERE id = '{$user_data['id']}' ---\n";
        usleep(50000);
        self::$users[$user_data['id']] = $user_data;
        return true;
    }
}

// --- Cache Implementation ---
class CacheManager {
    private $capacity;
    private $map = [];
    private $head = null;
    private $tail = null;

    // Using an inner class for the node
    private function create_node($key, $value, $expires_at) {
        return new class($key, $value, $expires_at) {
            public $key, $value, $expires_at, $prev, $next;
            public function __construct($k, $v, $e) {
                $this->key = $k; $this->value = $v; $this->expires_at = $e;
            }
        };
    }

    public function __construct($max_items) {
        $this->capacity = $max_items;
        $this->head = $this->create_node(null, null, null);
        $this->tail = $this->create_node(null, null, null);
        $this->head->next = $this->tail;
        $this->tail->prev = $this->head;
    }

    public function retrieve($key) {
        if (!isset($this->map[$key])) {
            return null;
        }
        $node = $this->map[$key];
        if ($node->expires_at !== null && time() > $node->expires_at) {
            $this->purge($key);
            return null;
        }
        $this->_move_to_front($node);
        return $node->value;
    }

    public function store($key, $value, $ttl = 3600) {
        $expires_at = $ttl ? time() + $ttl : null;
        if (isset($this->map[$key])) {
            $node = $this->map[$key];
            $node->value = $value;
            $node->expires_at = $expires_at;
            $this->_move_to_front($node);
            return;
        }

        if (count($this->map) >= $this->capacity) {
            $this->_evict_lru_item();
        }

        $new_node = $this->create_node($key, $value, $expires_at);
        $this->map[$key] = $new_node;
        $this->_add_to_front($new_node);
    }

    public function purge($key) {
        if (isset($this->map[$key])) {
            $node = $this->map[$key];
            $this->_remove_node($node);
            unset($this->map[$key]);
        }
    }

    private function _move_to_front($node) {
        $this->_remove_node($node);
        $this->_add_to_front($node);
    }

    private function _add_to_front($node) {
        $node->next = $this->head->next;
        $node->prev = $this->head;
        $this->head->next->prev = $node;
        $this->head->next = $node;
    }

    private function _remove_node($node) {
        $node->prev->next = $node->next;
        $node->next->prev = $node->prev;
    }

    private function _evict_lru_item() {
        $lru_node = $this->tail->prev;
        if ($lru_node !== $this->head) {
            $this->purge($lru_node->key);
        }
    }
}

// --- Service Functions ---

// Cache-Aside Logic
function get_user_by_id($id, CacheManager $cache) {
    $cache_key = "user_{$id}";
    $user_data = $cache->retrieve($cache_key);

    if ($user_data) {
        echo "CACHE HIT on key '{$cache_key}'\n";
        return $user_data;
    }

    echo "CACHE MISS on key '{$cache_key}'\n";
    $user_data = DataStore::get_user_from_db($id);

    if ($user_data) {
        $cache->store($cache_key, $user_data, 60); // Cache for 1 minute
    }

    return $user_data;
}

// Cache Invalidation Logic
function save_user_and_invalidate_cache($user_data, CacheManager $cache) {
    DataStore::update_user_in_db($user_data);
    $cache_key = "user_{$user_data['id']}";
    $cache->purge($cache_key);
    echo "CACHE PURGED for key '{$cache_key}'\n";
}


// --- Main Execution ---

DataStore::init();
$cache_manager = new CacheManager(10);
$user_id = '22222222-2222-2222-2222-222222222222';

echo "1. Fetching user for the first time...\n";
$user = get_user_by_id($user_id, $cache_manager);
print_r($user);

echo "\n2. Fetching same user again...\n";
$user = get_user_by_id($user_id, $cache_manager);
print_r($user);

echo "\n3. Updating user data...\n";
$user['email'] = 'procedural.updated@example.com';
save_user_and_invalidate_cache($user, $cache_manager);

echo "\n4. Fetching user after update...\n";
$user = get_user_by_id($user_id, $cache_manager);
print_r($user);

?>