<?php
/**
 * Variation 2: The "Functional/Procedural" Approach
 *
 * This implementation uses route closures for handling logic directly, which is common in
 * smaller Slim applications or microservices. It reduces boilerplate by avoiding separate
 * controller classes. Middleware is still defined as classes for reusability.
 *
 * To Run:
 * 1. composer require slim/slim slim/psr7 php-di/php-di tuupola/slim-jwt-auth ramsey/uuid
 * 2. Save this file as index.php.
 * 3. Run: php -S localhost:8080 index.php
 *
 * API Endpoints:
 * - POST /api/auth/token { "email": "admin@example.com", "password": "admin_password" }
 * - GET /api/content/posts (Requires any valid token)
 * - DELETE /api/content/posts/{id} (Requires ADMIN role)
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

// --- Mock Data Store (using a class with static methods for simplicity) ---
class DataStore {
    private static array $user_records = [];
    private static array $post_records = [];

    public static function bootstrap(): void {
        $admin_id = Uuid::uuid4()->toString();
        $user_id = Uuid::uuid4()->toString();

        self::$user_records = [
            $admin_id => [
                'id' => $admin_id,
                'email' => 'admin@example.com',
                'password_hash' => password_hash('admin_password', PASSWORD_DEFAULT),
                'role' => 'ADMIN',
                'is_active' => true,
            ],
            $user_id => [
                'id' => $user_id,
                'email' => 'user@example.com',
                'password_hash' => password_hash('user_password', PASSWORD_DEFAULT),
                'role' => 'USER',
                'is_active' => true,
            ],
        ];

        $post_id = Uuid::uuid4()->toString();
        self::$post_records = [
            $post_id => [
                'id' => $post_id,
                'user_id' => $admin_id,
                'title' => 'A Post to Delete',
                'content' => 'This post can be deleted by an admin.',
                'status' => 'PUBLISHED'
            ]
        ];
    }

    public static function findUserByEmail(string $email): ?array {
        foreach (self::$user_records as $user) {
            if ($user['email'] === $email) {
                return $user;
            }
        }
        return null;
    }

    public static function getPosts(): array {
        return array_values(self::$post_records);
    }


    public static function deletePost(string $id): bool {
        if (isset(self::$post_records[$id])) {
            unset(self::$post_records[$id]);
            return true;
        }
        return false;
    }
}

// --- RBAC Middleware ---
class AdminOnlyMiddleware {
    public function __invoke(Request $request, RequestHandler $handler): Response {
        $token = $request->getAttribute('token');
        if (!isset($token['role']) || $token['role'] !== 'ADMIN') {
            $response = AppFactory::create()->createResponse(403);
            $response->getBody()->write(json_encode(['message' => 'Access denied. Admin role required.']));
            return $response->withHeader('Content-Type', 'application/json');
        }
        return $handler->handle($request);
    }
}

// --- DI Container and App Initialization ---
$container = new Container();
AppFactory::setContainer($container);

$container->set('jwt_secret', 'a_different_secret_for_this_variation');

DataStore::bootstrap();
$app = AppFactory::create();

// --- Middleware Setup ---
$jwtAuth = new JwtAuthentication([
    "secret" => $container->get('jwt_secret'),
    "path" => "/api",
    "ignore" => ["/api/auth/token"],
    "error" => function ($response, $args) {
        $response->getBody()->write(json_encode(["error" => $args["message"]]));
        return $response->withHeader("Content-Type", "application/json")->withStatus(401);
    }
]);

// --- Route Definitions (Functional Style) ---

$app->post('/api/auth/token', function (Request $req, Response $res) use ($container) {
    $body = $req->getParsedBody();
    $user = DataStore::findUserByEmail($body['email'] ?? '');

    if ($user && $user['is_active'] && password_verify($body['password'] ?? '', $user['password_hash'])) {
        $now = new DateTimeImmutable();
        $payload = [
            'iat' => $now->getTimestamp(),
            'exp' => $now->modify('+2 hours')->getTimestamp(),
            'sub' => $user['id'],
            'role' => $user['role']
        ];
        $token = \Firebase\JWT\JWT::encode($payload, $container->get('jwt_secret'), 'HS256');
        $res->getBody()->write(json_encode(['access_token' => $token]));
        return $res->withHeader('Content-Type', 'application/json');
    }

    $res->getBody()->write(json_encode(['error' => 'Unauthorized']));
    return $res->withStatus(401)->withHeader('Content-Type', 'application/json');
});

$app->group('/api/content', function ($group) {
    $group->get('/posts', function (Request $req, Response $res) {
        // Any authenticated user can see posts
        $posts = DataStore::getPosts();
        $res->getBody()->write(json_encode($posts));
        return $res->withHeader('Content-Type', 'application/json');
    });

    $group->delete('/posts/{id}', function (Request $req, Response $res, array $args) {
        // This route is protected by the group's AdminOnlyMiddleware
        $success = DataStore::deletePost($args['id']);
        if ($success) {
            return $res->withStatus(204); // No Content
        }
        $res->getBody()->write(json_encode(['message' => 'Post not found']));
        return $res->withStatus(404)->withHeader('Content-Type', 'application/json');
    })->add(new AdminOnlyMiddleware());

})->add($jwtAuth);


$app->addBodyParsingMiddleware();
$app->addRoutingMiddleware();
$app->addErrorMiddleware(true, true, true);

$app->run();