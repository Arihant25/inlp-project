<?php

/**
 * Variation 1: Functional / Procedural Style
 *
 * This developer prefers a straightforward, functional approach.
 * - All routes and logic are in a single file.
 * - Route handlers are anonymous functions (closures).
 * - A static class is used as a simple in-memory data store.
 * - Minimal abstraction, focusing on direct implementation.
 *
 * To run this:
 * 1. composer require slim/slim slim/psr7 ramsey/uuid
 * 2. Place this code in public/index.php
 * 3. Run `php -S localhost:8080 -t public`
 */

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Slim\Factory\AppFactory;
use Ramsey\Uuid\Uuid;

require __DIR__ . '/../vendor/autoload.php';

// --- Mock Data Store ---
class MockUserData
{
    public static array $users = [];

    public static function seed(): void
    {
        if (empty(self::$users)) {
            $passwordHash = password_hash('password123', PASSWORD_DEFAULT);
            $now = new DateTimeImmutable();
            self::$users = [
                'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d' => [
                    'id' => 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
                    'email' => 'admin@example.com',
                    'password_hash' => $passwordHash,
                    'role' => 'ADMIN',
                    'is_active' => true,
                    'created_at' => $now->format(DateTime::ATOM)
                ],
                'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e' => [
                    'id' => 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
                    'email' => 'user1@example.com',
                    'password_hash' => $passwordHash,
                    'role' => 'USER',
                    'is_active' => true,
                    'created_at' => $now->modify('-1 day')->format(DateTime::ATOM)
                ],
                'c3d4e5f6-a7b8-4c9d-0e1f-2a3b4c5d6e7f' => [
                    'id' => 'c3d4e5f6-a7b8-4c9d-0e1f-2a3b4c5d6e7f',
                    'email' => 'user2@example.com',
                    'password_hash' => $passwordHash,
                    'role' => 'USER',
                    'is_active' => false,
                    'created_at' => $now->modify('-2 days')->format(DateTime::ATOM)
                ],
            ];
        }
    }
}

MockUserData::seed();

$app = AppFactory::create();
$app->addBodyParsingMiddleware();
$app->addRoutingMiddleware();
$app->addErrorMiddleware(true, true, true);

// --- API Endpoints ---

// POST /users: Create a new user
$app->post('/users', function (Request $request, Response $response) {
    $data = $request->getParsedBody();

    if (empty($data['email']) || empty($data['password']) || empty($data['role'])) {
        $response->getBody()->write(json_encode(['error' => 'Email, password, and role are required']));
        return $response->withStatus(400)->withHeader('Content-Type', 'application/json');
    }

    foreach (MockUserData::$users as $user) {
        if ($user['email'] === $data['email']) {
            $response->getBody()->write(json_encode(['error' => 'Email already exists']));
            return $response->withStatus(409)->withHeader('Content-Type', 'application/json');
        }
    }

    $newUser = [
        'id' => Uuid::uuid4()->toString(),
        'email' => filter_var($data['email'], FILTER_SANITIZE_EMAIL),
        'password_hash' => password_hash($data['password'], PASSWORD_DEFAULT),
        'role' => in_array($data['role'], ['ADMIN', 'USER']) ? $data['role'] : 'USER',
        'is_active' => $data['is_active'] ?? true,
        'created_at' => (new DateTimeImmutable())->format(DateTime::ATOM),
    ];

    MockUserData::$users[$newUser['id']] = $newUser;

    // Do not expose password hash in response
    $responseData = $newUser;
    unset($responseData['password_hash']);

    $response->getBody()->write(json_encode($responseData));
    return $response->withStatus(201)->withHeader('Content-Type', 'application/json');
});

// GET /users: List users with pagination and filtering
$app->get('/users', function (Request $request, Response $response) {
    $params = $request->getQueryParams();

    // Filtering
    $filteredUsers = array_filter(MockUserData::$users, function ($user) use ($params) {
        if (isset($params['email']) && stripos($user['email'], $params['email']) === false) {
            return false;
        }
        if (isset($params['role']) && $user['role'] !== $params['role']) {
            return false;
        }
        if (isset($params['is_active']) && (bool)$user['is_active'] !== ($params['is_active'] === 'true')) {
            return false;
        }
        return true;
    });

    // Pagination
    $page = isset($params['page']) ? (int)$params['page'] : 1;
    $limit = isset($params['limit']) ? (int)$params['limit'] : 10;
    $offset = ($page - 1) * $limit;
    $total = count($filteredUsers);
    $paginatedUsers = array_slice(array_values($filteredUsers), $offset, $limit);

    // Sanitize output
    $result = array_map(function ($user) {
        unset($user['password_hash']);
        return $user;
    }, $paginatedUsers);

    $data = [
        'page' => $page,
        'limit' => $limit,
        'total' => $total,
        'data' => $result
    ];

    $response->getBody()->write(json_encode($data));
    return $response->withHeader('Content-Type', 'application/json');
});

// GET /users/{id}: Get a single user
$app->get('/users/{id}', function (Request $request, Response $response, array $args) {
    $id = $args['id'];
    if (!isset(MockUserData::$users[$id])) {
        $response->getBody()->write(json_encode(['error' => 'User not found']));
        return $response->withStatus(404)->withHeader('Content-Type', 'application/json');
    }

    $user = MockUserData::$users[$id];
    unset($user['password_hash']);

    $response->getBody()->write(json_encode($user));
    return $response->withHeader('Content-Type', 'application/json');
});

// PUT /users/{id}: Update a user
$app->put('/users/{id}', function (Request $request, Response $response, array $args) {
    $id = $args['id'];
    if (!isset(MockUserData::$users[$id])) {
        $response->getBody()->write(json_encode(['error' => 'User not found']));
        return $response->withStatus(404)->withHeader('Content-Type', 'application/json');
    }

    $data = $request->getParsedBody();
    $user = &MockUserData::$users[$id];

    if (isset($data['email'])) $user['email'] = filter_var($data['email'], FILTER_SANITIZE_EMAIL);
    if (isset($data['role'])) $user['role'] = in_array($data['role'], ['ADMIN', 'USER']) ? $data['role'] : $user['role'];
    if (isset($data['is_active'])) $user['is_active'] = (bool)$data['is_active'];
    if (!empty($data['password'])) $user['password_hash'] = password_hash($data['password'], PASSWORD_DEFAULT);

    $responseData = $user;
    unset($responseData['password_hash']);

    $response->getBody()->write(json_encode($responseData));
    return $response->withHeader('Content-Type', 'application/json');
});

// DELETE /users/{id}: Delete a user
$app->delete('/users/{id}', function (Request $request, Response $response, array $args) {
    $id = $args['id'];
    if (!isset(MockUserData::$users[$id])) {
        $response->getBody()->write(json_encode(['error' => 'User not found']));
        return $response->withStatus(404)->withHeader('Content-Type', 'application/json');
    }

    unset(MockUserData::$users[$id]);

    return $response->withStatus(204); // No Content
});

$app->run();