<?php
/**
 * Variation 3: The "Action-Domain-Responder" (ADR) Pattern
 *
 * This implementation separates concerns into three distinct types of objects:
 * - Action: Receives the HTTP request and orchestrates the interaction.
 * - Domain: Contains the business logic (e.g., services, entities).
 * - Responder: Builds the HTTP response.
 * This pattern promotes single-responsibility and testability.
 *
 * To Run:
 * 1. composer require slim/slim slim/psr7 php-di/php-di tuupola/slim-jwt-auth ramsey/uuid
 * 2. Save all classes into their respective directories (e.g., src/Actions, src/Domain).
 * 3. Run this file: php -S localhost:8080 index.php
 *
 * API Endpoints:
 * - POST /v1/login { "email": "user@example.com", "password": "user_password" }
 * - GET /v1/posts (Requires USER or ADMIN role)
 * - GET /v1/users/me (Requires USER or ADMIN role)
 */

use DI\Container;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Slim\Factory\AppFactory;
use Tuupola\Middleware\JwtAuthentication;
use Ramsey\Uuid\Uuid;
use Firebase\JWT\JWT;

// Autoloader
require __DIR__ . '/vendor/autoload.php';

// --- Domain Layer ---
class User {
    public function __construct(
        public readonly string $id,
        public readonly string $email,
        public readonly string $password_hash,
        public readonly string $role,
        public readonly bool $is_active
    ) {}
}

class UserRepository {
    private array $users = [];

    public function __construct() {
        $adminId = Uuid::uuid4()->toString();
        $userId = Uuid::uuid4()->toString();
        $this->users = [
            'admin@example.com' => new User($adminId, 'admin@example.com', password_hash('admin_password', PASSWORD_DEFAULT), 'ADMIN', true),
            'user@example.com' => new User($userId, 'user@example.com', password_hash('user_password', PASSWORD_DEFAULT), 'USER', true),
        ];
    }

    public function findByEmail(string $email): ?User {
        return $this->users[$email] ?? null;
    }
    public function findById(string $id): ?User {
        foreach ($this->users as $user) {
            if ($user->id === $id) return $user;
        }
        return null;
    }
}

class AuthenticationService {
    public function __construct(private UserRepository $userRepo, private string $jwtSecret) {}

    public function generateToken(string $email, string $password): ?string {
        $user = $this->userRepo->findByEmail($email);
        if (!$user || !$user->is_active || !password_verify($password, $user->password_hash)) {
            return null;
        }
        $payload = [
            'iat' => time(),
            'exp' => time() + 3600,
            'sub' => $user->id,
            'scope' => [$user->role]
        ];
        return JWT::encode($payload, $this->jwtSecret, 'HS256');
    }
}

// --- Responder Layer ---
class JsonResponder {
    public function respond(Response $response, $data, int $status = 200): Response {
        $response->getBody()->write(json_encode($data));
        return $response->withHeader('Content-Type', 'application/json')->withStatus($status);
    }

    public function error(Response $response, string $message, int $status = 400): Response {
        return $this->respond($response, ['error' => ['message' => $message]], $status);
    }
}

// --- Action Layer ---
class LoginAction {
    public function __construct(private AuthenticationService $authService, private JsonResponder $responder) {}

    public function __invoke(Request $request, Response $response): Response {
        $body = $request->getParsedBody();
        $token = $this->authService->generateToken($body['email'] ?? '', $body['password'] ?? '');

        if ($token === null) {
            return $this->responder->error($response, 'Authentication failed', 401);
        }

        return $this->responder->respond($response, ['token' => $token]);
    }
}

class GetMyProfileAction {
    public function __construct(private UserRepository $userRepo, private JsonResponder $responder) {}

    public function __invoke(Request $request, Response $response): Response {
        $token = $request->getAttribute('token');
        $userId = $token['sub'] ?? null;

        if (!$userId) {
            return $this->responder->error($response, 'Token does not contain user identifier', 400);
        }

        $user = $this->userRepo->findById($userId);
        if (!$user) {
            return $this->responder->error($response, 'User not found', 404);
        }

        $profileData = [
            'id' => $user->id,
            'email' => $user->email,
            'role' => $user->role
        ];

        return $this->responder->respond($response, $profileData);
    }
}

// --- DI Container Setup ---
$container = new Container();
AppFactory::setContainer($container);

$container->set('jwt.secret', 'adr_pattern_is_cool_secret');
$container->set(UserRepository::class, \DI\autowire()->constructor());
$container->set(AuthenticationService::class, \DI\autowire()->constructor(
    \DI\get(UserRepository::class),
    \DI\get('jwt.secret')
));

// --- Application Setup ---
$app = AppFactory::create();

// --- Middleware ---
$jwtAuthMiddleware = new JwtAuthentication([
    "secret" => $container->get('jwt.secret'),
    "attribute" => "token",
    "error" => function ($response, $arguments) {
        $data['message'] = $arguments['message'];
        $response->getBody()->write(json_encode($data));
        return $response->withHeader('Content-Type', 'application/json')->withStatus(401);
    }
]);

// --- Routes ---
$app->post('/v1/login', LoginAction::class);

$app->get('/v1/users/me', GetMyProfileAction::class)->add($jwtAuthMiddleware);

$app->get('/v1/posts', function (Request $request, Response $response) {
    // A simple closure-based route can co-exist with ADR
    $posts = [
        ['id' => Uuid::uuid4()->toString(), 'title' => 'ADR Post 1'],
        ['id' => Uuid::uuid4()->toString(), 'title' => 'ADR Post 2']
    ];
    $response->getBody()->write(json_encode($posts));
    return $response->withHeader('Content-Type', 'application/json');
})->add($jwtAuthMiddleware);

$app->addBodyParsingMiddleware();
$app->addRoutingMiddleware();
$app->addErrorMiddleware(true, true, true);

$app->run();