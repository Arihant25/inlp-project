<?php

// Variation 4: The "PSR-6/PSR-16 Compliant" Developer
// Style: Modern, standards-based PHP using a DI container (PHP-DI) and PSR-16 for caching.
// Caching: An LRU cache class implements Psr\SimpleCache\CacheInterface. This interface
// is then injected into controllers, promoting decoupling and testability.

require __DIR__ . '/vendor/autoload.php';

use DI\Container;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Slim\Factory\AppFactory;
use Psr\SimpleCache\CacheInterface;

// --- PSR-16 SimpleCache Interface (for self-containment) ---
if (!interface_exists(CacheInterface::class)) {
    interface CacheInterface {
        public function get(string $key, mixed $default = null): mixed;
        public function set(string $key, mixed $value, null|int|\DateInterval $ttl = null): bool;
        public function delete(string $key): bool;
        public function clear(): bool;
        public function getMultiple(iterable $keys, mixed $default = null): iterable;
        public function setMultiple(iterable $values, null|int|\DateInterval $ttl = null): bool;
        public function deleteMultiple(iterable $keys): bool;
        public function has(string $key): bool;
    }
}

// --- Domain Model ---
class UserEntity {
    public function __construct(public string $id, public string $email, public string $role) {}
}

// --- Mock Repository ---
class UserRepo {
    private array $users = [];
    public function __construct() {
        $id = '1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d';
        $this->users[$id] = new UserEntity($id, 'admin@example.com', 'ADMIN');
    }
    public function find(string $id): ?UserEntity {
        usleep(50000); // Simulate DB latency
        return $this->users[$id] ?? null;
    }
    public function save(UserEntity $user): void {
        $this->users[$user->id] = $user;
    }
}

// --- PSR-16 Compliant LRU Cache Implementation ---
class LruPsr16Cache implements CacheInterface {
    private array $storage = [];
    private array $usageOrder = [];
    private int $capacity;

    public function __construct(int $capacity = 20) {
        $this->capacity = $capacity;
    }

    public function get(string $key, mixed $default = null): mixed {
        if (!$this->has($key)) {
            return $default;
        }
        
        $entry = $this->storage[$key];
        if ($entry['expires'] !== null && $entry['expires'] < time()) {
            $this->delete($key);
            return $default;
        }

        // Mark as recently used
        unset($this->usageOrder[array_search($key, $this->usageOrder)]);
        $this->usageOrder[] = $key;

        return $entry['value'];
    }

    public function set(string $key, mixed $value, null|int|\DateInterval $ttl = null): bool {
        if (count($this->storage) >= $this->capacity && !isset($this->storage[$key])) {
            $lruKey = array_shift($this->usageOrder);
            unset($this->storage[$lruKey]);
        }

        $expires = null;
        if (is_int($ttl)) {
            $expires = time() + $ttl;
        } elseif ($ttl instanceof \DateInterval) {
            $expires = (new \DateTime())->add($ttl)->getTimestamp();
        }

        $this->storage[$key] = ['value' => $value, 'expires' => $expires];
        
        if (($idx = array_search($key, $this->usageOrder)) !== false) {
            unset($this->usageOrder[$idx]);
        }
        $this->usageOrder[] = $key;
        
        return true;
    }

    public function delete(string $key): bool {
        unset($this->storage[$key]);
        if (($idx = array_search($key, $this->usageOrder)) !== false) {
            unset($this->usageOrder[$idx]);
        }
        return true;
    }
    
    public function has(string $key): bool {
        return isset($this->storage[$key]);
    }

    // Other PSR-16 methods (simplified for this example)
    public function clear(): bool { $this->storage = []; $this->usageOrder = []; return true; }
    public function getMultiple(iterable $keys, mixed $default = null): iterable { /* ... */ return []; }
    public function setMultiple(iterable $values, null|int|\DateInterval $ttl = null): bool { /* ... */ return true; }
    public function deleteMultiple(iterable $keys): bool { /* ... */ return true; }
}

// --- Controller using DI ---
class UserAction {
    public function __construct(
        private UserRepo $userRepo,
        private CacheInterface $cache
    ) {}

    public function fetchUser(Request $request, Response $response, array $args): Response {
        $userId = $args['id'];
        $cacheKey = "users.{$userId}";
        $startTime = microtime(true);

        // Cache-Aside Pattern using PSR-16 interface
        $user = $this->cache->get($cacheKey);
        $source = 'cache';

        if ($user === null) {
            $source = 'database';
            $user = $this->userRepo->find($userId);
            if ($user) {
                $this->cache->set($cacheKey, $user, 300); // 5 minute TTL
            }
        }
        
        $duration = round((microtime(true) - $startTime) * 1000, 2);

        if ($user) {
            $payload = json_encode(['user' => $user, 'source' => $source, 'lookup_ms' => $duration]);
            $response->getBody()->write($payload);
            return $response->withHeader('Content-Type', 'application/json');
        }
        return $response->withStatus(404);
    }
    
    public function updateUser(Request $request, Response $response, array $args): Response {
        $userId = $args['id'];
        $user = $this->userRepo->find($userId);
        if (!$user) return $response->withStatus(404);
        
        $body = (array)$request->getParsedBody();
        $user->email = $body['email'];
        $this->userRepo->save($user);
        
        // Cache Invalidation
        $this->cache->delete("users.{$userId}");
        
        $response->getBody()->write(json_encode(['status' => 'user updated, cache cleared']));
        return $response->withHeader('Content-Type', 'application/json');
    }
}

// --- DI Container and App Setup ---
$container = new Container();
$container->set(CacheInterface::class, function() {
    return new LruPsr16Cache(50);
});

AppFactory::setContainer($container);
$app = AppFactory::create();
$app->addBodyParsingMiddleware();

$app->get('/users/{id}', [UserAction::class, 'fetchUser']);
$app->put('/users/{id}', [UserAction::class, 'updateUser']);

// To run this:
// 1. composer require slim/slim slim/psr7 php-di/php-di
// 2. php -S localhost:8080 index.php
// Example requests:
// GET http://localhost:8080/users/1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d (source: database)
// GET http://localhost:8080/users/1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d (source: cache)
// PUT http://localhost:8080/users/1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d with JSON body {"email": "new@example.com"}
// GET again to see source: database

// $app->run(); // Commented out for self-contained execution.