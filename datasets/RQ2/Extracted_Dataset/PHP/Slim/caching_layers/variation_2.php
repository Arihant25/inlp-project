<?php

// Variation 2: The "Middleware-Focused" Developer
// Style: Leverages Slim's middleware for caching concerns.
// Caching: A middleware intercepts GET requests, checks a cache, and serves the response directly
// if available. It also caches the response on a miss. Invalidation is done in write endpoints.

require __DIR__ . '/vendor/autoload.php';

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Psr\Http\Server\RequestHandlerInterface as RequestHandler;
use Slim\Factory\AppFactory;
use Slim\Psr7\Response as SlimResponse;

// --- Domain Model (simplified for this example) ---
class UserDTO {
    public function __construct(public string $id, public string $email, public string $role) {}
}

// --- Mock Data Source ---
class DataSource {
    private static array $users = [];

    public static function initialize() {
        $userId1 = '1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d';
        self::$users[$userId1] = ['id' => $userId1, 'email' => 'admin@example.com', 'role' => 'ADMIN'];
    }

    public static function findUserById(string $id): ?array {
        usleep(50000); // Simulate 50ms DB latency
        return self::$users[$id] ?? null;
    }
    
    public static function updateUserEmail(string $id, string $email): bool {
        if (isset(self::$users[$id])) {
            self::$users[$id]['email'] = $email;
            return true;
        }
        return false;
    }
}
DataSource::initialize();

// --- Caching Layer (Simple static LRU implementation) ---
class AppCache {
    private static int $capacity = 10;
    private static array $cache = []; // key => value
    private static array $usage = []; // key => timestamp

    public static function get(string $key): mixed {
        if (isset(self::$cache[$key])) {
            self::$usage[$key] = time();
            return self::$cache[$key];
        }
        return null;
    }

    public static function set(string $key, mixed $value): void {
        if (count(self::$cache) >= self::$capacity && !isset(self::$cache[$key])) {
            asort(self::$usage);
            $lruKey = key(self::$usage);
            unset(self::$cache[$lruKey]);
            unset(self::$usage[$lruKey]);
        }
        self::$cache[$key] = $value;
        self::$usage[$key] = time();
    }

    public static function delete(string $key): void {
        unset(self::$cache[$key]);
        unset(self::$usage[$key]);
    }
}

// --- Caching Middleware ---
class CacheMiddleware {
    public function __invoke(Request $request, RequestHandler $handler): Response {
        // Only cache GET requests
        if ($request->getMethod() !== 'GET') {
            return $handler->handle($request);
        }

        $cacheKey = $this->generateCacheKey($request);

        // 1. Check cache for a stored response
        $cachedResponseData = AppCache::get($cacheKey);
        if ($cachedResponseData) {
            $response = new SlimResponse();
            $response->getBody()->write($cachedResponseData['body']);
            foreach ($cachedResponseData['headers'] as $name => $value) {
                $response = $response->withHeader($name, $value);
            }
            // Add a header to indicate cache hit
            return $response->withHeader('X-Cache-Status', 'HIT');
        }

        // 2. Cache miss: proceed to the route handler
        $response = $handler->handle($request);

        // 3. Cache the new response if it's a success
        if ($response->getStatusCode() === 200) {
            $headers = $response->getHeaders();
            // Slim's response body is a stream, so we need to read it
            $body = (string) $response->getBody();
            AppCache::set($cacheKey, ['body' => $body, 'headers' => $headers]);
            
            // Rewind the stream so Slim can send it to the client
            $response->getBody()->rewind();
        }
        
        return $response->withHeader('X-Cache-Status', 'MISS');
    }

    private function generateCacheKey(Request $request): string {
        return 'response:' . str_replace('/', '_', $request->getUri()->getPath());
    }
}

// --- App Setup & Routing ---
$app = AppFactory::create();
$app->addBodyParsingMiddleware();

// GET route: The handler is simple, middleware does the caching
$app->get('/users/{id}', function (Request $request, Response $response, array $args) {
    $user = DataSource::findUserById($args['id']);
    if ($user) {
        $dto = new UserDTO($user['id'], $user['email'], $user['role']);
        $response->getBody()->write(json_encode($dto));
        return $response->withHeader('Content-Type', 'application/json');
    }
    return $response->withStatus(404);
})->add(new CacheMiddleware()); // Apply middleware to this specific route

// PUT route: This handler invalidates the cache
$app->put('/users/{id}', function (Request $request, Response $response, array $args) {
    $data = (array)$request->getParsedBody();
    $success = DataSource::updateUserEmail($args['id'], $data['email'] ?? '');
    
    if ($success) {
        // Invalidation Strategy: Delete the cached GET response
        $cacheKey = 'response:_users_' . $args['id'];
        AppCache::delete($cacheKey);
        
        $response->getBody()->write(json_encode(['status' => 'updated, cache invalidated']));
        return $response->withHeader('Content-Type', 'application/json');
    }
    
    return $response->withStatus(404);
});

// To run this:
// 1. composer require slim/slim slim/psr7
// 2. php -S localhost:8080 index.php
// Example requests:
// GET http://localhost:8080/users/1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d (Response header X-Cache-Status: MISS)
// GET http://localhost:8080/users/1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d (Response header X-Cache-Status: HIT)
// PUT http://localhost:8080/users/1a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d with JSON body {"email": "new@example.com"}
// GET again to see X-Cache-Status: MISS

// $app->run(); // Commented out for self-contained execution.