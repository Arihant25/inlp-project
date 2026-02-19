<?php

// Variation 1: The "Service-Oriented" Developer
// Style: OOP-heavy, uses Service and Repository patterns with Dependency Injection.
// Caching: A dedicated CacheService encapsulates LRU logic and is injected where needed.

require __DIR__ . '/vendor/autoload.php';

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Slim\Factory\AppFactory;
use Slim\Routing\RouteCollectorProxy;

// --- Domain Model ---

enum UserRole: string {
    case ADMIN = 'ADMIN';
    case USER = 'USER';
}

enum PostStatus: string {
    case DRAFT = 'DRAFT';
    case PUBLISHED = 'PUBLISHED';
}

class User {
    public function __construct(
        public string $id,
        public string $email,
        public string $password_hash,
        public UserRole $role,
        public bool $is_active,
        public int $created_at
    ) {}
}

class Post {
    public function __construct(
        public string $id,
        public string $user_id,
        public string $title,
        public string $content,
        public PostStatus $status
    ) {}
}

// --- Mock Database ---

class MockDatabase {
    private array $users = [];
    private array $posts = [];

    public function __construct() {
        $userId1 = '1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d';
        $userId2 = '2a2b2c2d-2e2f-2a2b-2c2d-2e2f2a2b2c2d';
        $this->users[$userId1] = new User($userId1, 'admin@example.com', 'hash1', UserRole::ADMIN, true, time());
        $this->users[$userId2] = new User($userId2, 'user@example.com', 'hash2', UserRole::USER, true, time() - 86400);

        $postId1 = 'p1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d';
        $this->posts[$postId1] = new Post($postId1, $userId1, 'Admin Post', 'Content by admin.', PostStatus::PUBLISHED);
    }

    public function findUser(string $id): ?User {
        // Simulate DB latency
        usleep(50000); // 50ms
        return $this->users[$id] ?? null;
    }

    public function findPost(string $id): ?Post {
        usleep(50000); // 50ms
        return $this->posts[$id] ?? null;
    }

    public function saveUser(User $user): void {
        $this->users[$user->id] = $user;
    }
    
    public function deleteUser(string $id): void {
        unset($this->users[$id]);
    }
}

// --- Caching Layer ---

class LruCache {
    private int $capacity;
    private array $cache = [];
    private array $keysByTime = [];

    public function __construct(int $capacity = 10) {
        $this->capacity = $capacity;
    }

    public function get(string $key): mixed {
        if (!isset($this->cache[$key])) {
            return null;
        }

        // Update timestamp to mark as recently used
        unset($this->keysByTime[array_search($key, $this->keysByTime)]);
        $this->keysByTime[] = $key;

        $entry = $this->cache[$key];
        if ($entry['ttl'] !== null && $entry['expires_at'] < time()) {
            $this->delete($key);
            return null;
        }

        return $entry['value'];
    }

    public function set(string $key, mixed $value, ?int $ttl = 300): void {
        if (count($this->cache) >= $this->capacity && !isset($this->cache[$key])) {
            // Evict least recently used
            $lruKey = array_shift($this->keysByTime);
            unset($this->cache[$lruKey]);
        }

        $this->cache[$key] = [
            'value' => $value,
            'ttl' => $ttl,
            'expires_at' => $ttl ? time() + $ttl : null,
        ];

        // Remove if exists and add to end (most recently used)
        if (($index = array_search($key, $this->keysByTime)) !== false) {
            unset($this->keysByTime[$index]);
        }
        $this->keysByTime[] = $key;
    }

    public function delete(string $key): void {
        unset($this->cache[$key]);
        if (($index = array_search($key, $this->keysByTime)) !== false) {
            unset($this->keysByTime[$index]);
        }
    }
}

// --- Application Services & Repositories ---

class UserRepository {
    public function __construct(private MockDatabase $db) {}
    public function findById(string $id): ?User { return $this->db->findUser($id); }
    public function save(User $user): void { $this->db->saveUser($user); }
    public function delete(string $id): void { $this->db->deleteUser($id); }
}

class UserService {
    public function __construct(private UserRepository $userRepository, private LruCache $cache) {}

    // Cache-Aside Pattern Implementation
    public function getUserById(string $id): ?User {
        $cacheKey = "user_{$id}";
        
        // 1. Try to get from cache
        $user = $this->cache->get($cacheKey);
        if ($user !== null) {
            return $user;
        }

        // 2. Cache miss: get from source of truth (DB)
        $user = $this->userRepository->findById($id);

        // 3. Store in cache for next time
        if ($user !== null) {
            $this->cache->set($cacheKey, $user, 60); // 60 second TTL
        }

        return $user;
    }

    public function updateUser(string $id, array $data): ?User {
        $user = $this->userRepository->findById($id);
        if ($user) {
            $user->email = $data['email'] ?? $user->email;
            $this->userRepository->save($user);
            
            // Invalidation Strategy: Delete cache on write
            $this->cache->delete("user_{$id}");
            return $user;
        }
        return null;
    }
}

// --- Controller ---

class UserController {
    public function __construct(private UserService $userService) {}

    public function getUser(Request $request, Response $response, array $args): Response {
        $userId = $args['id'];
        $startTime = microtime(true);
        $user = $this->userService->getUserById($userId);
        $duration = microtime(true) - $startTime;

        if ($user) {
            $payload = json_encode([
                'data' => $user,
                'source' => $duration < 0.01 ? 'cache' : 'database',
                'retrieval_time_ms' => round($duration * 1000, 2)
            ]);
            $response->getBody()->write($payload);
            return $response->withHeader('Content-Type', 'application/json');
        }
        return $response->withStatus(404);
    }
    
    public function updateUser(Request $request, Response $response, array $args): Response {
        $userId = $args['id'];
        $data = (array)$request->getParsedBody();
        $user = $this->userService->updateUser($userId, $data);

        if ($user) {
            $payload = json_encode(['status' => 'updated', 'user' => $user]);
            $response->getBody()->write($payload);
            return $response->withHeader('Content-Type', 'application/json');
        }
        return $response->withStatus(404);
    }
}

// --- App Setup & Routing ---

$db = new MockDatabase();
$cache = new LruCache(50);
$userRepository = new UserRepository($db);
$userService = new UserService($userRepository, $cache);
$userController = new UserController($userService);

$app = AppFactory::create();
$app->addBodyParsingMiddleware();

$app->group('/v1', function (RouteCollectorProxy $group) use ($userController) {
    $group->get('/users/{id}', [$userController, 'getUser']);
    $group->put('/users/{id}', [$userController, 'updateUser']);
});

// To run this:
// 1. composer require slim/slim slim/psr7
// 2. php -S localhost:8080 index.php
// Example requests:
// GET http://localhost:8080/v1/users/1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d (first time is slow, second is fast)
// PUT http://localhost:8080/v1/users/1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d with JSON body {"email": "new@example.com"}
// GET again to see it's slow (cache was invalidated)

// $app->run(); // Commented out for self-contained execution without a web server.