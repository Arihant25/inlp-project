<?php
/**
 * Variation 4: The "Middleware-Heavy" Approach
 *
 * This implementation emphasizes the composable nature of middleware. Logic is broken down
 * into small, single-purpose middleware classes that are chained together on routes.
 * This makes the authorization logic highly declarative and easy to reason about.
 *
 * To Run:
 * 1. composer require slim/slim slim/psr7 php-di/php-di tuupola/slim-jwt-auth ramsey/uuid
 * 2. Save this file as index.php.
 * 3. Run: php -S localhost:8080 index.php
 *
 * API Endpoints:
 * - POST /auth/login { "email": "admin@example.com", "password": "admin_password" }
 * - GET /dashboard (Requires active user)
 * - GET /admin/reports (Requires active user with ADMIN role)
 */

use DI\Container;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Psr\Http\Server\RequestHandlerInterface as Handler;
use Slim\Factory\AppFactory;
use Tuupola\Middleware\JwtAuthentication;
use Ramsey\Uuid\Uuid;

// Autoloader
require __DIR__ . '/vendor/autoload.php';

// --- Mock User Provider ---
class UserProvider {
    private static array $users = [];

    public static function boot() {
        self::$users = [
            'admin@example.com' => [
                'id' => Uuid::uuid4()->toString(),
                'email' => 'admin@example.com',
                'password_hash' => password_hash('admin_password', PASSWORD_DEFAULT),
                'role' => 'ADMIN',
                'is_active' => true,
            ],
            'user@example.com' => [
                'id' => Uuid::uuid4()->toString(),
                'email' => 'user@example.com',
                'password_hash' => password_hash('user_password', PASSWORD_DEFAULT),
                'role' => 'USER',
                'is_active' => true,
            ],
            'suspended@example.com' => [
                'id' => Uuid::uuid4()->toString(),
                'email' => 'suspended@example.com',
                'password_hash' => password_hash('suspended_password', PASSWORD_DEFAULT),
                'role' => 'USER',
                'is_active' => false,
            ],
        ];
    }

    public static function findByEmail(string $email): ?array {
        return self::$users[$email] ?? null;
    }

    public static function findById(string $id): ?array {
        foreach (self::$users as $user) {
            if ($user['id'] === $id) {
                return $user;
            }
        }
        return null;
    }
}

// --- Single-Purpose Middleware ---

// Attaches the full user object to the request for downstream middleware/handlers
class UserHydrationMiddleware {
    public function __invoke(Request $request, Handler $handler): Response {
        $token = $request->getAttribute('token');
        if (isset($token['sub'])) {
            $user = UserProvider::findById($token['sub']);
            if ($user) {
                $request = $request->withAttribute('user', $user);
            }
        }
        return $handler->handle($request);
    }
}

// Checks if the hydrated user is active
class ActiveUserCheckMiddleware {
    public function __invoke(Request $request, Handler $handler): Response {
        $user = $request->getAttribute('user');
        if (!$user || !$user['is_active']) {
            $response = AppFactory::create()->createResponse(403);
            $response->getBody()->write(json_encode(['error' => 'User account is not active.']));
            return $response->withHeader('Content-Type', 'application/json');
        }
        return $handler->handle($request);
    }
}

// A flexible role guard
class RoleGuardMiddleware {
    private array $allowedRoles;

    public function __construct(string ...$allowedRoles) {
        $this->allowedRoles = $allowedRoles;
    }

    public function __invoke(Request $request, Handler $handler): Response {
        $user = $request->getAttribute('user');
        if (!$user || !in_array($user['role'], $this->allowedRoles)) {
            $response = AppFactory::create()->createResponse(403);
            $response->getBody()->write(json_encode(['error' => 'Insufficient permissions.']));
            return $response->withHeader('Content-Type', 'application/json');
        }
        return $handler->handle($request);
    }
}

// --- DI and App Setup ---
$container = new Container();
AppFactory::setContainer($container);
$app = AppFactory::create();
UserProvider::boot();

$jwtSecret = 'this_is_a_very_secure_secret_for_middleware_heavy_approach';

// --- JWT Middleware Configuration ---
$jwtAuth = new JwtAuthentication([
    "secret" => $jwtSecret,
    "secure" => false, // for local dev
    "error" => function ($response, $args) {
        $response->getBody()->write(json_encode(["error" => $args["message"]]));
        return $response->withHeader("Content-Type", "application/json")->withStatus(401);
    }
]);

// --- Route Handlers (can be simple closures or classes) ---
$app->post('/auth/login', function (Request $request, Response $response) use ($jwtSecret) {
    $body = $request->getParsedBody();
    $user = UserProvider::findByEmail($body['email'] ?? '');

    if ($user && password_verify($body['password'] ?? '', $user['password_hash'])) {
        $payload = [
            'iat' => time(),
            'exp' => time() + 7200, // 2 hours
            'sub' => $user['id'],
        ];
        $token = \Firebase\JWT\JWT::encode($payload, $jwtSecret, 'HS256');
        $response->getBody()->write(json_encode(['token' => $token]));
        return $response->withHeader('Content-Type', 'application/json');
    }

    $response->getBody()->write(json_encode(['error' => 'Invalid credentials']));
    return $response->withStatus(401)->withHeader('Content-Type', 'application/json');
});

// --- Protected Routes with Middleware Chains ---

// This route requires a valid token and an active user account.
$app->get('/dashboard', function (Request $request, Response $response) {
    $user = $request->getAttribute('user'); // Hydrated by middleware
    $response->getBody()->write(json_encode([
        'message' => 'Welcome to your dashboard, ' . $user['email'],
        'user_id' => $user['id'],
        'role' => $user['role']
    ]));
    return $response->withHeader('Content-Type', 'application/json');
})
->add(new ActiveUserCheckMiddleware())
->add(new UserHydrationMiddleware())
->add($jwtAuth);


// This route requires a valid token, an active user, AND the 'ADMIN' role.
$app->get('/admin/reports', function (Request $request, Response $response) {
    $user = $request->getAttribute('user');
    $response->getBody()->write(json_encode([
        'message' => 'Admin-only financial reports',
        'generated_by' => $user['email'],
        'data' => ['revenue' => 100000, 'expenses' => 45000]
    ]));
    return $response->withHeader('Content-Type', 'application/json');
})
->add(new RoleGuardMiddleware('ADMIN'))
->add(new ActiveUserCheckMiddleware())
->add(new UserHydrationMiddleware())
->add($jwtAuth);


$app->addBodyParsingMiddleware();
$app->addRoutingMiddleware();
$app->addErrorMiddleware(true, true, true);

$app->run();