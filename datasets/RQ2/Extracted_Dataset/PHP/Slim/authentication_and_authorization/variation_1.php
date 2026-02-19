<?php
/**
 * Variation 1: The "Classic" OOP Approach
 *
 * This implementation uses a traditional Model-View-Controller (MVC) like structure,
 * with dedicated Controller classes, a Service layer for business logic, and Middleware classes.
 * It emphasizes separation of concerns and is well-suited for larger, more complex applications.
 *
 * To Run:
 * 1. composer require slim/slim slim/psr7 php-di/php-di tuupola/slim-jwt-auth ramsey/uuid
 * 2. Save all classes into their respective directories (e.g., src/Controllers, src/Services).
 * 3. Run this file: php -S localhost:8080 index.php
 *
 * API Endpoints:
 * - POST /login { "email": "admin@example.com", "password": "admin_password" }
 * - GET /posts (Requires USER or ADMIN role)
 * - POST /admin/posts { "title": "New Post", "content": "..." } (Requires ADMIN role)
 */

use DI\Container;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Psr\Http\Server\RequestHandlerInterface as RequestHandler;
use Slim\Factory\AppFactory;
use Tuupola\Middleware\JwtAuthentication;
use Ramsey\Uuid\Uuid;

// Autoloader
require __DIR__ . '/vendor/autoload.php';

// --- Domain Model Enums ---
enum UserRole: string {
    case ADMIN = 'ADMIN';
    case USER = 'USER';
}
enum PostStatus: string {
    case DRAFT = 'DRAFT';
    case PUBLISHED = 'PUBLISHED';
}

// --- Mock Database ---
class MockDatabase {
    private static array $users = [];
    private static array $posts = [];

    public static function init() {
        $adminId = Uuid::uuid4()->toString();
        $userId = Uuid::uuid4()->toString();

        self::$users = [
            'admin@example.com' => [
                'id' => $adminId,
                'email' => 'admin@example.com',
                'password_hash' => password_hash('admin_password', PASSWORD_DEFAULT),
                'role' => UserRole::ADMIN,
                'is_active' => true,
                'created_at' => new \DateTimeImmutable()
            ],
            'user@example.com' => [
                'id' => $userId,
                'email' => 'user@example.com',
                'password_hash' => password_hash('user_password', PASSWORD_DEFAULT),
                'role' => UserRole::USER,
                'is_active' => true,
                'created_at' => new \DateTimeImmutable()
            ],
            'inactive@example.com' => [
                'id' => Uuid::uuid4()->toString(),
                'email' => 'inactive@example.com',
                'password_hash' => password_hash('inactive_password', PASSWORD_DEFAULT),
                'role' => UserRole::USER,
                'is_active' => false,
                'created_at' => new \DateTimeImmutable()
            ]
        ];

        self::$posts = [
            Uuid::uuid4()->toString() => [
                'id' => Uuid::uuid4()->toString(),
                'user_id' => $adminId,
                'title' => 'Admin Post',
                'content' => 'This is a post by the admin.',
                'status' => PostStatus::PUBLISHED
            ],
            Uuid::uuid4()->toString() => [
                'id' => Uuid::uuid4()->toString(),
                'user_id' => $userId,
                'title' => 'User Post',
                'content' => 'This is a post by a regular user.',
                'status' => PostStatus::DRAFT
            ]
        ];
    }

    public static function findUserByEmail(string $email): ?array {
        return self::$users[$email] ?? null;
    }

    public static function getAllPosts(): array {
        return array_values(self::$posts);
    }

    public static function savePost(array $post): void {
        self::$posts[$post['id']] = $post;
    }
}

// --- Service Layer ---
class AuthService {
    private string $jwtSecret;

    public function __construct(string $jwtSecret) {
        $this->jwtSecret = $jwtSecret;
    }

    public function attemptLogin(string $email, string $password): ?string {
        $user = MockDatabase::findUserByEmail($email);

        if (!$user || !$user['is_active'] || !password_verify($password, $user['password_hash'])) {
            return null;
        }

        $issuedAt = new \DateTimeImmutable();
        $expire = $issuedAt->modify('+6 hours')->getTimestamp();

        $payload = [
            'iat' => $issuedAt->getTimestamp(),
            'exp' => $expire,
            'sub' => $user['id'],
            'email' => $user['email'],
            'role' => $user['role']->value,
        ];

        return \Firebase\JWT\JWT::encode($payload, $this->jwtSecret, 'HS256');
    }
}

// --- Controllers ---
class AuthController {
    private AuthService $authService;

    public function __construct(AuthService $authService) {
        $this->authService = $authService;
    }

    public function login(Request $request, Response $response): Response {
        $data = $request->getParsedBody();
        $email = $data['email'] ?? '';
        $password = $data['password'] ?? '';

        $token = $this->authService->attemptLogin($email, $password);

        if (!$token) {
            $response->getBody()->write(json_encode(['error' => 'Invalid credentials or inactive user']));
            return $response->withStatus(401)->withHeader('Content-Type', 'application/json');
        }

        $response->getBody()->write(json_encode(['token' => $token]));
        return $response->withHeader('Content-Type', 'application/json');
    }
}

class PostController {
    public function listPosts(Request $request, Response $response): Response {
        // In a real app, you might filter posts based on the authenticated user
        // $token = $request->getAttribute('token');
        $posts = MockDatabase::getAllPosts();
        $response->getBody()->write(json_encode($posts));
        return $response->withHeader('Content-Type', 'application/json');
    }

    public function createPost(Request $request, Response $response): Response {
        $token = $request->getAttribute('token');
        $data = $request->getParsedBody();

        $newPost = [
            'id' => Uuid::uuid4()->toString(),
            'user_id' => $token['sub'], // User ID from JWT
            'title' => $data['title'] ?? 'Untitled',
            'content' => $data['content'] ?? '',
            'status' => PostStatus::PUBLISHED
        ];

        MockDatabase::savePost($newPost);

        $response->getBody()->write(json_encode($newPost));
        return $response->withStatus(201)->withHeader('Content-Type', 'application/json');
    }
}

// --- Middleware ---
class RoleMiddleware {
    private UserRole $requiredRole;

    public function __construct(UserRole $requiredRole) {
        $this->requiredRole = $requiredRole;
    }

    public function __invoke(Request $request, RequestHandler $handler): Response {
        $token = $request->getAttribute('token');
        $userRole = $token['role'] ?? null;

        if ($userRole !== $this->requiredRole->value) {
            $response = AppFactory::create()->createResponse();
            $response->getBody()->write(json_encode(['error' => 'Forbidden: Insufficient permissions']));
            return $response->withStatus(403)->withHeader('Content-Type', 'application/json');
        }

        return $handler->handle($request);
    }
}

// --- Dependency Injection Container Setup ---
$container = new Container();
AppFactory::setContainer($container);

$container->set('settings', function() {
    return [
        'jwt_secret' => 'supersecretkeyyoushouldnotcommit'
    ];
});

$container->set(AuthService::class, function($c) {
    return new AuthService($c->get('settings')['jwt_secret']);
});

// --- Application Setup ---
$app = AppFactory::create();
MockDatabase::init();

// --- Middleware Configuration ---
$jwtMiddleware = new JwtAuthentication([
    "secret" => $container->get('settings')['jwt_secret'],
    "path" => ["/posts", "/admin"],
    "ignore" => ["/login"],
    "error" => function ($response, $arguments) {
        $data["status"] = "error";
        $data["message"] = $arguments["message"];
        $response->getBody()->write(json_encode($data, JSON_UNESCAPED_SLASHES));
        return $response->withHeader("Content-Type", "application/json")->withStatus(401);
    }
]);

// --- Routes ---
$app->post('/login', [AuthController::class, 'login']);

$app->group('/posts', function ($group) {
    $group->get('', [PostController::class, 'listPosts']);
})->add($jwtMiddleware);

$app->group('/admin', function ($group) {
    $group->post('/posts', [PostController::class, 'createPost']);
})->add(new RoleMiddleware(UserRole::ADMIN))->add($jwtMiddleware);

$app->addBodyParsingMiddleware();
$app->addRoutingMiddleware();
$app->addErrorMiddleware(true, true, true);

$app->run();