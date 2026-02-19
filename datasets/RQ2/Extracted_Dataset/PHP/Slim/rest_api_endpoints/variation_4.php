<?php

/**
 * Variation 4: Modern / CQRS-inspired Style
 *
 * This developer prefers to separate read (Query) and write (Command) operations.
 * - Write operations (Create, Update, Delete) are handled by Command Handlers.
 * - Read operations (Find, Search) are handled by Query Handlers.
 * - A simple, in-memory "Bus" dispatches commands and queries to their handlers.
 * - Route handlers are very thin, responsible only for creating a Command/Query
 *   from the request and dispatching it.
 * - This pattern offers excellent separation of concerns and scalability.
 *
 * To run this:
 * 1. composer require slim/slim slim/psr7 php-di/php-di ramsey/uuid
 * 2. Place this code in public/index.php
 * 3. Run `php -S localhost:8080 -t public`
 */

use DI\Container;
use Psr\Container\ContainerInterface;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Ramsey\Uuid\Uuid;
use Slim\Factory\AppFactory;

require __DIR__ . '/../vendor/autoload.php';

// --- Shared Infrastructure: Simple Command/Query Bus ---
interface Bus { public function dispatch($message); }
class InMemoryBus implements Bus
{
    private ContainerInterface $container;
    private array $handlers;
    public function __construct(ContainerInterface $container, array $handlers)
    {
        $this->container = $container;
        $this->handlers = $handlers;
    }
    public function dispatch($message)
    {
        $messageClass = get_class($message);
        if (!isset($this->handlers[$messageClass])) {
            throw new \RuntimeException("No handler for " . $messageClass);
        }
        $handler = $this->container->get($this->handlers[$messageClass]);
        return $handler($message);
    }
}

// --- User Domain ---
interface UserRepository
{
    public function save(array $user): void;
    public function findById(string $id): ?array;
    public function findByCriteria(array $criteria): array;
    public function delete(string $id): void;
    public function findByEmail(string $email): ?array;
}

class InMemoryUserRepository implements UserRepository
{
    private array $users = [];
    public function __construct()
    {
        $passwordHash = password_hash('password123', PASSWORD_DEFAULT);
        $now = (new DateTimeImmutable())->format(DateTime::ATOM);
        $this->users = [
            'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d' => ['id' => 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d', 'email' => 'admin@example.com', 'password_hash' => $passwordHash, 'role' => 'ADMIN', 'is_active' => true, 'created_at' => $now],
            'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e' => ['id' => 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e', 'email' => 'user1@example.com', 'password_hash' => $passwordHash, 'role' => 'USER', 'is_active' => true, 'created_at' => $now],
            'c3d4e5f6-a7b8-4c9d-0e1f-2a3b4c5d6e7f' => ['id' => 'c3d4e5f6-a7b8-4c9d-0e1f-2a3b4c5d6e7f', 'email' => 'user2@example.com', 'password_hash' => $passwordHash, 'role' => 'USER', 'is_active' => false, 'created_at' => $now],
        ];
    }
    public function save(array $user): void { $this->users[$user['id']] = $user; }
    public function findById(string $id): ?array { return $this->users[$id] ?? null; }
    public function delete(string $id): void { unset($this->users[$id]); }
    public function findByEmail(string $email): ?array {
        foreach ($this->users as $user) { if ($user['email'] === $email) return $user; }
        return null;
    }
    public function findByCriteria(array $criteria): array
    {
        return array_filter($this->users, function ($user) use ($criteria) {
            if (isset($criteria['email']) && stripos($user['email'], $criteria['email']) === false) return false;
            if (isset($criteria['role']) && $user['role'] !== $criteria['role']) return false;
            if (isset($criteria['is_active']) && (bool)$user['is_active'] !== ($criteria['is_active'] === 'true')) return false;
            return true;
        });
    }
}

// --- Application Layer: Commands (Writes) ---
class CreateUserCommand { public function __construct(public string $email, public string $password, public ?string $role, public ?bool $is_active) {} }
class UpdateUserCommand { public function __construct(public string $id, public ?string $email, public ?string $password, public ?string $role, public ?bool $is_active) {} }
class DeleteUserCommand { public function __construct(public string $id) {} }

// --- Application Layer: Command Handlers ---
class CreateUserCommandHandler
{
    public function __construct(private UserRepository $repository) {}
    public function __invoke(CreateUserCommand $command): string
    {
        if ($this->repository->findByEmail($command->email)) {
            throw new \InvalidArgumentException('Email already exists', 409);
        }
        $user = [
            'id' => Uuid::uuid4()->toString(),
            'email' => $command->email,
            'password_hash' => password_hash($command->password, PASSWORD_DEFAULT),
            'role' => $command->role ?? 'USER',
            'is_active' => $command->is_active ?? true,
            'created_at' => (new DateTimeImmutable())->format(DateTime::ATOM),
        ];
        $this->repository->save($user);
        return $user['id'];
    }
}
class UpdateUserCommandHandler
{
    public function __construct(private UserRepository $repository) {}
    public function __invoke(UpdateUserCommand $command): void
    {
        $user = $this->repository->findById($command->id);
        if (!$user) throw new \InvalidArgumentException('User not found', 404);
        if ($command->email) $user['email'] = $command->email;
        if ($command->role) $user['role'] = $command->role;
        if ($command->is_active !== null) $user['is_active'] = $command->is_active;
        if ($command->password) $user['password_hash'] = password_hash($command->password, PASSWORD_DEFAULT);
        $this->repository->save($user);
    }
}
class DeleteUserCommandHandler
{
    public function __construct(private UserRepository $repository) {}
    public function __invoke(DeleteUserCommand $command): void
    {
        if (!$this->repository->findById($command->id)) {
            throw new \InvalidArgumentException('User not found', 404);
        }
        $this->repository->delete($command->id);
    }
}

// --- Application Layer: Queries (Reads) ---
class FindUserByIdQuery { public function __construct(public string $id) {} }
class SearchUsersQuery { public function __construct(public array $filters, public int $page, public int $limit) {} }

// --- Application Layer: Query Handlers ---
class UserFinder
{
    public function __construct(private UserRepository $repository) {}
    public function __invoke(FindUserByIdQuery $query): ?array
    {
        $user = $this->repository->findById($query->id);
        if ($user) unset($user['password_hash']);
        return $user;
    }
}
class UsersSearcher
{
    public function __construct(private UserRepository $repository) {}
    public function __invoke(SearchUsersQuery $query): array
    {
        $allMatching = $this->repository->findByCriteria($query->filters);
        $total = count($allMatching);
        $paginated = array_slice(array_values($allMatching), ($query->page - 1) * $query->limit, $query->limit);
        
        return [
            'page' => $query->page,
            'limit' => $query->limit,
            'total' => $total,
            'data' => array_map(function($user) { unset($user['password_hash']); return $user; }, $paginated)
        ];
    }
}

// --- DI Setup ---
$container = new Container();
$container->set(UserRepository::class, \DI\create(InMemoryUserRepository::class)->scope(\DI\Scope::SINGLETON));
$container->set('CommandBus', function(ContainerInterface $c) {
    return new InMemoryBus($c, [
        CreateUserCommand::class => CreateUserCommandHandler::class,
        UpdateUserCommand::class => UpdateUserCommandHandler::class,
        DeleteUserCommand::class => DeleteUserCommandHandler::class,
    ]);
});
$container->set('QueryBus', function(ContainerInterface $c) {
    return new InMemoryBus($c, [
        FindUserByIdQuery::class => UserFinder::class,
        SearchUsersQuery::class => UsersSearcher::class,
    ]);
});

// --- App Setup ---
AppFactory::setContainer($container);
$app = AppFactory::create();
$app->addBodyParsingMiddleware();
$app->addRoutingMiddleware();
$app->addErrorMiddleware(true, true, true);

// --- Helper for JSON response ---
function json_response(Response $response, $data, int $status = 200): Response {
    $response->getBody()->write(json_encode($data));
    return $response->withHeader('Content-Type', 'application/json')->withStatus($status);
}

// --- Routes ---
$app->post('/users', function (Request $request, Response $response, ContainerInterface $container) {
    $data = $request->getParsedBody();
    if (empty($data['email']) || empty($data['password'])) {
        return json_response($response, ['error' => 'Email and password are required'], 400);
    }
    try {
        $command = new CreateUserCommand($data['email'], $data['password'], $data['role'] ?? null, $data['is_active'] ?? null);
        $userId = $container->get('CommandBus')->dispatch($command);
        $user = $container->get('QueryBus')->dispatch(new FindUserByIdQuery($userId));
        return json_response($response, $user, 201);
    } catch (\InvalidArgumentException $e) {
        return json_response($response, ['error' => $e->getMessage()], $e->getCode() ?: 400);
    }
});

$app->get('/users', function (Request $request, Response $response, ContainerInterface $container) {
    $params = $request->getQueryParams();
    $page = (int)($params['page'] ?? 1);
    $limit = (int)($params['limit'] ?? 10);
    $filters = array_intersect_key($params, array_flip(['email', 'role', 'is_active']));
    $query = new SearchUsersQuery($filters, $page, $limit);
    $result = $container->get('QueryBus')->dispatch($query);
    return json_response($response, $result);
});

$app->get('/users/{id}', function (Request $request, Response $response, ContainerInterface $container, array $args) {
    $query = new FindUserByIdQuery($args['id']);
    $user = $container->get('QueryBus')->dispatch($query);
    return $user ? json_response($response, $user) : json_response($response, ['error' => 'User not found'], 404);
});

$app->put('/users/{id}', function (Request $request, Response $response, ContainerInterface $container, array $args) {
    $data = $request->getParsedBody();
    try {
        $command = new UpdateUserCommand($args['id'], $data['email'] ?? null, $data['password'] ?? null, $data['role'] ?? null, $data['is_active'] ?? null);
        $container->get('CommandBus')->dispatch($command);
        $user = $container->get('QueryBus')->dispatch(new FindUserByIdQuery($args['id']));
        return json_response($response, $user);
    } catch (\InvalidArgumentException $e) {
        return json_response($response, ['error' => $e->getMessage()], $e->getCode() ?: 400);
    }
});

$app->delete('/users/{id}', function (Request $request, Response $response, ContainerInterface $container, array $args) {
    try {
        $container->get('CommandBus')->dispatch(new DeleteUserCommand($args['id']));
        return $response->withStatus(204);
    } catch (\InvalidArgumentException $e) {
        return json_response($response, ['error' => $e->getMessage()], $e->getCode() ?: 400);
    }
});

$app->run();