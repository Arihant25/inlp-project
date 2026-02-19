<?php

// Variation 3: The "Functional & Concise" Developer
// Style: Minimalist, functional style in a single file. Avoids complex class hierarchies.
// Caching: A simple static class with helper methods implements the LRU cache.
// Logic is placed directly inside route closures.

require __DIR__ . '/vendor/autoload.php';

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Slim\Factory\AppFactory;

// --- Mock Data Store ---
class DB_Store {
    private static $users = [];
    private static $posts = [];

    public static function init() {
        $user_id = '1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d';
        self::$users[$user_id] = [
            'id' => $user_id, 'email' => 'admin@example.com', 'password_hash' => 'hash1',
            'role' => 'ADMIN', 'is_active' => true, 'created_at' => time()
        ];
        $post_id = 'p1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d';
        self::$posts[$post_id] = [
            'id' => $post_id, 'user_id' => $user_id, 'title' => 'My First Post',
            'content' => 'Hello world!', 'status' => 'PUBLISHED'
        ];
    }

    public static function get_user(string $id): ?array {
        usleep(50000); // Simulate I/O latency
        return self::$users[$id] ?? null;
    }
    
    public static function update_user(string $id, array $data): ?array {
        if (isset(self::$users[$id])) {
            self::$users[$id] = array_merge(self::$users[$id], $data);
            return self::$users[$id];
        }
        return null;
    }
}
DB_Store::init();

// --- Simple LRU Cache Implementation ---
class Simple_Cache {
    private static $cache = [];
    private static $usage_queue = [];
    private static $capacity = 10;
    private static $default_ttl = 60; // 60 seconds

    public static function get_from_cache(string $key): mixed {
        if (!isset(self::$cache[$key])) {
            return null;
        }

        $entry = self::$cache[$key];
        if (time() > $entry['expires_at']) {
            self::delete_from_cache($key);
            return null;
        }

        // Mark as recently used
        unset(self::$usage_queue[array_search($key, self::$usage_queue)]);
        self::$usage_queue[] = $key;

        return $entry['data'];
    }

    public static function set_in_cache(string $key, mixed $data, ?int $ttl = null): void {
        if (count(self::$cache) >= self::$capacity && !isset(self::$cache[$key])) {
            $lru_key = array_shift(self::$usage_queue);
            unset(self::$cache[$lru_key]);
        }
        
        self::$cache[$key] = [
            'data' => $data,
            'expires_at' => time() + ($ttl ?? self::$default_ttl)
        ];

        if (($idx = array_search($key, self::$usage_queue)) !== false) {
            unset(self::$usage_queue[$idx]);
        }
        self::$usage_queue[] = $key;
    }

    public static function delete_from_cache(string $key): void {
        unset(self::$cache[$key]);
        if (($idx = array_search($key, self::$usage_queue)) !== false) {
            unset(self::$usage_queue[$idx]);
        }
    }
}

// --- App Setup & Routes ---
$app = AppFactory::create();
$app->addBodyParsingMiddleware();

$app->get('/users/{id}', function (Request $request, Response $response, array $args) {
    $user_id = $args['id'];
    $cache_key = "user:{$user_id}";
    $start_time = microtime(true);

    // Cache-Aside Pattern: implemented directly in the route handler
    $user_data = Simple_Cache::get_from_cache($cache_key);
    $source = 'cache';

    if ($user_data === null) {
        $source = 'database';
        $user_data = DB_Store::get_user($user_id);
        if ($user_data !== null) {
            Simple_Cache::set_in_cache($cache_key, $user_data, 120); // 2 minute TTL
        }
    }
    
    $duration_ms = round((microtime(true) - $start_time) * 1000, 2);

    if ($user_data) {
        $payload = json_encode([
            'data' => $user_data,
            'meta' => ['source' => $source, 'retrieval_time_ms' => $duration_ms]
        ]);
        $response->getBody()->write($payload);
        return $response->withHeader('Content-Type', 'application/json');
    }

    return $response->withStatus(404);
});

$app->put('/users/{id}', function (Request $request, Response $response, array $args) {
    $user_id = $args['id'];
    $cache_key = "user:{$user_id}";
    $body = (array)$request->getParsedBody();
    
    $updated_user = DB_Store::update_user($user_id, ['email' => $body['email']]);
    
    if ($updated_user) {
        // Cache Invalidation Strategy: delete on write
        Simple_Cache::delete_from_cache($cache_key);
        
        $response->getBody()->write(json_encode(['status' => 'ok', 'data' => $updated_user]));
        return $response->withHeader('Content-Type', 'application/json');
    }
    
    return $response->withStatus(404);
});

// To run this:
// 1. composer require slim/slim slim/psr7
// 2. php -S localhost:8080 index.php
// Example requests:
// GET http://localhost:8080/users/1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d (source: database)
// GET http://localhost:8080/users/1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d (source: cache)
// PUT http://localhost:8080/users/1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d with JSON body {"email": "new@example.com"}
// GET again to see source: database

// $app->run(); // Commented out for self-contained execution.