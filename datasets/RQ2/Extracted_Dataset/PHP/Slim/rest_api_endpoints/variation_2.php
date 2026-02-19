<?php

/**
 * Variation 2: Action-Domain-Responder (ADR) / Single Action Controller Style
 *
 * This developer prefers a more structured, object-oriented approach.
 * - Each endpoint has its own dedicated "Action" class.
 * - A UserRepository abstracts data access.
 * - Dependency Injection is used to provide the repository to the actions.
 * - Promotes Single Responsibility Principle (SRP).
 *
 * To run this:
 * 1. composer require slim/slim slim/psr7 php-di/php-di ramsey/uuid
 * 2. Place this code in public/index.php
 * 3. Run `php -S localhost:8080 -t public`
 */

use DI\Container;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Psr\Http\Server\RequestHandlerInterface;
use Ramsey\Uuid\Uuid;
use Slim\Factory\AppFactory;

require __DIR__ . '/../vendor/autoload.php';

// --- Domain Model ---
class User
{
    public string $id;
    public string $email;
    public string $password_hash;
    public string $role;
    public bool $is_active;
    public DateTimeImmutable $created_at;

    public function __construct(string $id, string $email, string $password_hash, string $role, bool $is_active, DateTimeImmutable $created_at)
    {
        $this->id = $id;
        $this->email = $email;
        $this->password_hash = $password_hash;
        $this->role = $role;
        $this->is_active = $is_active;
        $this->created_at = $created_at;
    }

    public function toArray(): array
    {
        return [
            'id' => $this->id,
            'email' => $this->email,
            'role' => $this->role,
            'is_active' => $this->is_active,
            'created_at' => $this->created_at->format(DateTime::ATOM),
        ];
    }
}

// --- Domain Repository Interface ---
interface UserRepository
{
    public function findAll(array $criteria, int $page, int $limit): array;
    public function count(array $criteria): int;
    public function findById(string $id): ?User;
    public function findByEmail(string $email): ?User;
    public function save(User $user): void;
    public function delete(string $id): bool;
}

// --- Infrastructure: In-Memory Repository Implementation ---
class InMemoryUserRepository implements UserRepository
{
    private array $users = [];

    public function __construct()
    {
        $passwordHash = password_hash('password123', PASSWORD_DEFAULT);
        $now = new DateTimeImmutable();
        $this->save(new User('a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d', 'admin@example.com', $passwordHash, 'ADMIN', true, $now));
        $this->save(new User('b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e', 'user1@example.com', $passwordHash, 'USER', true, $now->modify('-1 day')));
        $this->save(new User('c3d4e5f6-a7b8-4c9d-0e1f-2a3b4c5d6e7f', 'user2@example.com', $passwordHash, 'USER', false, $now->modify('-2 days')));
    }

    public function findAll(array $criteria, int $page, int $limit): array
    {
        $filtered = array_filter($this->users, function (User $user) use ($criteria) {
            if (isset($criteria['email']) && stripos($user->email, $criteria['email']) === false) return false;
            if (isset($criteria['role']) && $user->role !== $criteria['role']) return false;
            if (isset($criteria['is_active']) && $user->is_active !== ($criteria['is_active'] === 'true')) return false;
            return true;
        });
        $offset = ($page - 1) * $limit;
        return array_slice(array_values($filtered), $offset, $limit);
    }
    
    public function count(array $criteria): int
    {
        $filtered = array_filter($this->users, function (User $user) use ($criteria) {
            if (isset($criteria['email']) && stripos($user->email, $criteria['email']) === false) return false;
            if (isset($criteria['role']) && $user->role !== $criteria['role']) return false;
            if (isset($criteria['is_active']) && $user->is_active !== ($criteria['is_active'] === 'true')) return false;
            return true;
        });
        return count($filtered);
    }

    public function findById(string $id): ?User { return $this->users[$id] ?? null; }
    public function findByEmail(string $email): ?User {
        foreach ($this->users as $user) {
            if ($user->email === $email) return $user;
        }
        return null;
    }
    public function save(User $user): void { $this->users[$user->id] = $user; }
    public function delete(string $id): bool {
        if (isset($this->users[$id])) {
            unset($this->users[$id]);
            return true;
        }
        return false;
    }
}

// --- Base Action for JSON responses ---
abstract class Action
{
    protected function respondWithData(Response $response, $data, int $statusCode = 200): Response
    {
        $response->getBody()->write(json_encode($data));
        return $response->withHeader('Content-Type', 'application/json')->withStatus($statusCode);
    }

    protected function respondWithError(Response $response, string $message, int $statusCode): Response
    {
        return $this->respondWithData($response, ['error' => $message], $statusCode);
    }
}

// --- Actions (Single Action Controllers) ---
class CreateUserAction extends Action
{
    private UserRepository $userRepository;
    public function __construct(UserRepository $userRepository) { $this->userRepository = $userRepository; }

    public function __invoke(Request $request, Response $response): Response
    {
        $data = $request->getParsedBody();
        if (empty($data['email']) || empty($data['password'])) {
            return $this->respondWithError($response, 'Email and password are required', 400);
        }
        if ($this->userRepository->findByEmail($data['email'])) {
            return $this->respondWithError($response, 'Email already exists', 409);
        }

        $user = new User(
            Uuid::uuid4()->toString(),
            $data['email'],
            password_hash($data['password'], PASSWORD_DEFAULT),
            $data['role'] ?? 'USER',
            $data['is_active'] ?? true,
            new DateTimeImmutable()
        );
        $this->userRepository->save($user);
        return $this->respondWithData($response, $user->toArray(), 201);
    }
}

class ListUsersAction extends Action
{
    private UserRepository $userRepository;
    public function __construct(UserRepository $userRepository) { $this->userRepository = $userRepository; }

    public function __invoke(Request $request, Response $response): Response
    {
        $params = $request->getQueryParams();
        $page = (int)($params['page'] ?? 1);
        $limit = (int)($params['limit'] ?? 10);
        $criteria = array_intersect_key($params, array_flip(['email', 'role', 'is_active']));
        
        $users = $this->userRepository->findAll($criteria, $page, $limit);
        $total = $this->userRepository->count($criteria);

        $data = [
            'page' => $page,
            'limit' => $limit,
            'total' => $total,
            'data' => array_map(fn(User $user) => $user->toArray(), $users)
        ];
        return $this->respondWithData($response, $data);
    }
}

class GetUserAction extends Action
{
    private UserRepository $userRepository;
    public function __construct(UserRepository $userRepository) { $this->userRepository = $userRepository; }

    public function __invoke(Request $request, Response $response, array $args): Response
    {
        $user = $this->userRepository->findById($args['id']);
        if (!$user) {
            return $this->respondWithError($response, 'User not found', 404);
        }
        return $this->respondWithData($response, $user->toArray());
    }
}

class UpdateUserAction extends Action
{
    private UserRepository $userRepository;
    public function __construct(UserRepository $userRepository) { $this->userRepository = $userRepository; }

    public function __invoke(Request $request, Response $response, array $args): Response
    {
        $user = $this->userRepository->findById($args['id']);
        if (!$user) {
            return $this->respondWithError($response, 'User not found', 404);
        }
        $data = $request->getParsedBody();
        if (isset($data['email'])) $user->email = $data['email'];
        if (isset($data['role'])) $user->role = $data['role'];
        if (isset($data['is_active'])) $user->is_active = (bool)$data['is_active'];
        if (!empty($data['password'])) $user->password_hash = password_hash($data['password'], PASSWORD_DEFAULT);
        
        $this->userRepository->save($user);
        return $this->respondWithData($response, $user->toArray());
    }
}

class DeleteUserAction extends Action
{
    private UserRepository $userRepository;
    public function __construct(UserRepository $userRepository) { $this->userRepository = $userRepository; }

    public function __invoke(Request $request, Response $response, array $args): Response
    {
        if (!$this->userRepository->delete($args['id'])) {
            return $this->respondWithError($response, 'User not found', 404);
        }
        return $response->withStatus(204);
    }
}

// --- Dependency Injection and App Setup ---
$container = new Container();
$container->set(UserRepository::class, \DI\create(InMemoryUserRepository::class)->scope(\DI\Scope::SINGLETON));

AppFactory::setContainer($container);
$app = AppFactory::create();
$app->addBodyParsingMiddleware();
$app->addRoutingMiddleware();
$app->addErrorMiddleware(true, true, true);

// --- Routes ---
$app->post('/users', CreateUserAction::class);
$app->get('/users', ListUsersAction::class);
$app->get('/users/{id}', GetUserAction::class);
$app->put('/users/{id}', UpdateUserAction::class);
$app->delete('/users/{id}', DeleteUserAction::class);

$app->run();