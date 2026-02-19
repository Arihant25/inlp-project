<?php

/**
 * Variation 3: Classic Controller Style
 *
 * This developer prefers a traditional MVC-style controller.
 * - A single UserController class groups all user-related actions.
 * - A UserService encapsulates business logic, separating it from the controller.
 * - A UserRepository handles data persistence.
 * - Routes are grouped and mapped to controller methods.
 * - This demonstrates a layered architecture (Controller -> Service -> Repository).
 *
 * To run this:
 * 1. composer require slim/slim slim/psr7 php-di/php-di ramsey/uuid
 * 2. Place this code in public/index.php
 * 3. Run `php -S localhost:8080 -t public`
 */

use DI\Container;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Ramsey\Uuid\Uuid;
use Slim\Factory\AppFactory;
use Slim\Routing\RouteCollectorProxy;

require __DIR__ . '/../vendor/autoload.php';

// --- Domain Entity ---
class UserEntity
{
    public string $id;
    public string $email;
    public string $password_hash;
    public string $role;
    public bool $is_active;
    public string $created_at;

    public function toResponseArray(): array
    {
        return [
            'id' => $this->id,
            'email' => $this->email,
            'role' => $this->role,
            'is_active' => $this->is_active,
            'created_at' => $this->created_at,
        ];
    }
}

// --- Repository Layer ---
interface IUserRepository
{
    public function find(string $id): ?UserEntity;
    public function findBy(array $criteria, int $page, int $limit): array;
    public function countBy(array $criteria): int;
    public function save(UserEntity $user): void;
    public function delete(UserEntity $user): void;
    public function findByEmail(string $email): ?UserEntity;
}

class InMemoryUserRepository implements IUserRepository
{
    private static array $db = [];

    public function __construct()
    {
        if (empty(self::$db)) {
            $this->seed();
        }
    }

    private function seed()
    {
        $passwordHash = password_hash('password123', PASSWORD_DEFAULT);
        $now = new DateTimeImmutable();
        $users = [
            ['a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d', 'admin@example.com', $passwordHash, 'ADMIN', true, $now],
            ['b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e', 'user1@example.com', $passwordHash, 'USER', true, $now->modify('-1 day')],
            ['c3d4e5f6-a7b8-4c9d-0e1f-2a3b4c5d6e7f', 'user2@example.com', $passwordHash, 'USER', false, $now->modify('-2 days')],
        ];
        foreach ($users as $userData) {
            $user = new UserEntity();
            $user->id = $userData[0];
            $user->email = $userData[1];
            $user->password_hash = $userData[2];
            $user->role = $userData[3];
            $user->is_active = $userData[4];
            $user->created_at = $userData[5]->format(DateTime::ATOM);
            $this->save($user);
        }
    }

    public function find(string $id): ?UserEntity { return self::$db[$id] ?? null; }
    public function findByEmail(string $email): ?UserEntity {
        foreach (self::$db as $user) {
            if ($user->email === $email) return $user;
        }
        return null;
    }
    public function save(UserEntity $user): void { self::$db[$user->id] = $user; }
    public function delete(UserEntity $user): void { unset(self::$db[$user->id]); }
    
    public function findBy(array $criteria, int $page, int $limit): array {
        $filtered = array_filter(self::$db, function (UserEntity $user) use ($criteria) {
            if (isset($criteria['email']) && stripos($user->email, $criteria['email']) === false) return false;
            if (isset($criteria['role']) && $user->role !== $criteria['role']) return false;
            if (isset($criteria['is_active']) && $user->is_active !== ($criteria['is_active'] === 'true')) return false;
            return true;
        });
        return array_slice(array_values($filtered), ($page - 1) * $limit, $limit);
    }
    public function countBy(array $criteria): int {
        return count(array_filter(self::$db, function (UserEntity $user) use ($criteria) {
            if (isset($criteria['email']) && stripos($user->email, $criteria['email']) === false) return false;
            if (isset($criteria['role']) && $user->role !== $criteria['role']) return false;
            if (isset($criteria['is_active']) && $user->is_active !== ($criteria['is_active'] === 'true')) return false;
            return true;
        }));
    }
}

// --- Service Layer ---
class UserService
{
    private IUserRepository $userRepo;
    public function __construct(IUserRepository $userRepo) { $this->userRepo = $userRepo; }

    public function createUser(array $data): UserEntity
    {
        if ($this->userRepo->findByEmail($data['email'])) {
            throw new \Exception("Email already in use", 409);
        }
        $user = new UserEntity();
        $user->id = Uuid::uuid4()->toString();
        $user->email = $data['email'];
        $user->password_hash = password_hash($data['password'], PASSWORD_DEFAULT);
        $user->role = $data['role'] ?? 'USER';
        $user->is_active = $data['is_active'] ?? true;
        $user->created_at = (new DateTimeImmutable())->format(DateTime::ATOM);
        $this->userRepo->save($user);
        return $user;
    }

    public function updateUser(string $id, array $data): UserEntity
    {
        $user = $this->userRepo->find($id);
        if (!$user) throw new \Exception("User not found", 404);
        
        if (isset($data['email'])) $user->email = $data['email'];
        if (isset($data['role'])) $user->role = $data['role'];
        if (isset($data['is_active'])) $user->is_active = (bool)$data['is_active'];
        if (!empty($data['password'])) $user->password_hash = password_hash($data['password'], PASSWORD_DEFAULT);
        
        $this->userRepo->save($user);
        return $user;
    }

    public function deleteUser(string $id): void
    {
        $user = $this->userRepo->find($id);
        if (!$user) throw new \Exception("User not found", 404);
        $this->userRepo->delete($user);
    }

    public function getUserById(string $id): UserEntity
    {
        $user = $this->userRepo->find($id);
        if (!$user) throw new \Exception("User not found", 404);
        return $user;
    }

    public function searchUsers(array $params): array
    {
        $page = (int)($params['page'] ?? 1);
        $limit = (int)($params['limit'] ?? 10);
        $criteria = array_intersect_key($params, array_flip(['email', 'role', 'is_active']));
        
        $users = $this->userRepo->findBy($criteria, $page, $limit);
        $total = $this->userRepo->countBy($criteria);
        
        return ['users' => $users, 'total' => $total, 'page' => $page, 'limit' => $limit];
    }
}

// --- Controller Layer ---
class UserController
{
    private UserService $userService;
    public function __construct(UserService $userService) { $this->userService = $userService; }

    private function jsonResponse(Response $response, $data, int $status = 200): Response
    {
        $response->getBody()->write(json_encode($data));
        return $response->withHeader('Content-Type', 'application/json')->withStatus($status);
    }

    public function create(Request $request, Response $response): Response
    {
        $data = $request->getParsedBody();
        if (empty($data['email']) || empty($data['password'])) {
            return $this->jsonResponse($response, ['error' => 'Email and password are required'], 400);
        }
        try {
            $user = $this->userService->createUser($data);
            return $this->jsonResponse($response, $user->toResponseArray(), 201);
        } catch (\Exception $e) {
            return $this->jsonResponse($response, ['error' => $e->getMessage()], $e->getCode() ?: 400);
        }
    }

    public function list(Request $request, Response $response): Response
    {
        $result = $this->userService->searchUsers($request->getQueryParams());
        $data = [
            'page' => $result['page'],
            'limit' => $result['limit'],
            'total' => $result['total'],
            'data' => array_map(fn(UserEntity $u) => $u->toResponseArray(), $result['users'])
        ];
        return $this->jsonResponse($response, $data);
    }

    public function get(Request $request, Response $response, array $args): Response
    {
        try {
            $user = $this->userService->getUserById($args['id']);
            return $this->jsonResponse($response, $user->toResponseArray());
        } catch (\Exception $e) {
            return $this->jsonResponse($response, ['error' => $e->getMessage()], $e->getCode() ?: 400);
        }
    }

    public function update(Request $request, Response $response, array $args): Response
    {
        try {
            $user = $this->userService->updateUser($args['id'], $request->getParsedBody());
            return $this->jsonResponse($response, $user->toResponseArray());
        } catch (\Exception $e) {
            return $this->jsonResponse($response, ['error' => $e->getMessage()], $e->getCode() ?: 400);
        }
    }

    public function delete(Request $request, Response $response, array $args): Response
    {
        try {
            $this->userService->deleteUser($args['id']);
            return $response->withStatus(204);
        } catch (\Exception $e) {
            return $this->jsonResponse($response, ['error' => $e->getMessage()], $e->getCode() ?: 400);
        }
    }
}

// --- DI and App Setup ---
$container = new Container();
$container->set(IUserRepository::class, \DI\create(InMemoryUserRepository::class)->scope(\DI\Scope::SINGLETON));
$container->set(UserService::class, \DI\autowire(UserService::class));
$container->set(UserController::class, \DI\autowire(UserController::class));

AppFactory::setContainer($container);
$app = AppFactory::create();
$app->addBodyParsingMiddleware();
$app->addRoutingMiddleware();
$app->addErrorMiddleware(true, true, true);

// --- Routes ---
$app->group('/users', function (RouteCollectorProxy $group) {
    $group->post('', UserController::class . ':create');
    $group->get('', UserController::class . ':list');
    $group->get('/{id}', UserController::class . ':get');
    $group->put('/{id}', UserController::class . ':update');
    $group->delete('/{id}', UserController::class . ':delete');
});

$app->run();