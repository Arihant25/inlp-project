<?php

// VARIATION 4: Service Layer Approach
// This variation introduces a dedicated Service layer to encapsulate business logic,
// keeping controllers thin and focused on HTTP-related tasks. It often uses a Repository
// layer to abstract data persistence. This pattern is highly testable, maintainable,
// and suitable for complex, enterprise-level applications.

// --- FILE: composer.json ---
// {
//     "require": {
//         "slim/slim": "4.*",
//         "slim/psr7": "^1.6",
//         "php-di/php-di": "^7.0",
//         "illuminate/database": "^10.0",
//         "ramsey/uuid": "^4.7"
//     },
//     "autoload": {
//         "psr-4": {
//             "App\\": "src/"
//         }
//     }
// }

// To run:
// 1. composer install
// 2. php -S localhost:8080 index.php

// --- FILE: index.php (Entry Point) ---

use DI\Container;
use Slim\Factory\AppFactory;
use Illuminate\Database\Capsule\Manager as Capsule;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Slim\Routing\RouteCollectorProxy;
use App\Controllers\UserController;
use App\Controllers\PostController;
use App\Services\UserService;
use App\Repositories\UserRepository;
use App\Repositories\PostRepository;
use App\Services\PostService;

require __DIR__ . '/vendor/autoload.php';

// --- Dependency Injection Container Setup ---
$container = new Container();

// Database
$container->set('db', function () {
    $capsule = new Capsule;
    $capsule->addConnection(['driver' => 'sqlite', 'database' => ':memory:']);
    $capsule->setAsGlobal();
    $capsule->bootEloquent();
    return $capsule;
});

// Repositories
$container->set(UserRepository::class, \DI\autowire(UserRepository::class));
$container->set(PostRepository::class, \DI\autowire(PostRepository::class));

// Services
$container->set(UserService::class, \DI\autowire(UserService::class));
$container->set(PostService::class, \DI\autowire(PostService::class));

// Controllers
$container->set(UserController::class, \DI\autowire(UserController::class));
$container->set(PostController::class, \DI\autowire(PostController::class));

AppFactory::setContainer($container);

// --- Migrations and Seeding ---
$db = $container->get('db');
require_once __DIR__ . '/src/Database/Migrations.php';
App\Database\Migrations::up($db->schema());
require_once __DIR__ . '/src/Database/Seeder.php';
App\Database\Seeder::run();

// --- App Initialization ---
$app = AppFactory::create();
$app->addBodyParsingMiddleware();
$app->addRoutingMiddleware();
$app->addErrorMiddleware(true, true, true);

// --- Route Definitions ---
$app->get('/', function (Request $request, Response $response) {
    $response->getBody()->write("Variation 4: Service Layer Approach");
    return $response;
});

$app->group('/users', function (RouteCollectorProxy $group) {
    $group->get('', [UserController::class, 'index']);
    $group->post('', [UserController::class, 'create']);
    $group->get('/{id}', [UserController::class, 'show']);
    $group->put('/{id}', [UserController::class, 'update']);
    $group->delete('/{id}', [UserController::class, 'delete']);
    $group->post('/{id}/roles', [UserController::class, 'assignRole']);
});

$app->group('/posts', function (RouteCollectorProxy $group) {
    $group->get('', [PostController::class, 'index']);
    $group->post('', [PostController::class, 'create']);
});

$app->post('/transaction-demo', [UserController::class, 'transactionDemo']);

$app->run();


// --- FILE: src/Models/BaseModel.php ---
namespace App\Models;
use Illuminate\Database\Eloquent\Model;
use Ramsey\Uuid\Uuid;
abstract class BaseModel extends Model {
    public $incrementing = false; protected $keyType = 'string';
    protected static function boot() {
        parent::boot();
        static::creating(fn($model) => $model->{$model->getKeyName()} = Uuid::uuid4()->toString());
    }
}

// --- FILE: src/Models/User.php ---
namespace App\Models;
class User extends BaseModel {
    protected $table = 'users';
    protected $fillable = ['email', 'password_hash', 'is_active'];
    protected $casts = ['is_active' => 'boolean'];
    protected $hidden = ['password_hash'];
    public function posts() { return $this->hasMany(Post::class); }
    public function roles() { return $this->belongsToMany(Role::class, 'user_roles'); }
}

// --- FILE: src/Models/Post.php ---
namespace App\Models;
enum PostStatus: string { case DRAFT = 'draft'; case PUBLISHED = 'published'; }
class Post extends BaseModel {
    protected $table = 'posts';
    protected $fillable = ['user_id', 'title', 'content', 'status'];
    protected $casts = ['status' => PostStatus::class];
    public function user() { return $this->belongsTo(User::class); }
}

// --- FILE: src/Models/Role.php ---
namespace App\Models;
use Illuminate\Database\Eloquent\Model;
class Role extends Model {
    public $timestamps = false; protected $fillable = ['name'];
    public function users() { return $this->belongsToMany(User::class, 'user_roles'); }
}

// --- FILE: src/Repositories/UserRepository.php ---
namespace App\Repositories;
use App\Models\User;
use Illuminate\Database\Eloquent\Collection;

class UserRepository {
    public function findByFilters(array $filters): Collection {
        $query = User::query()->with('roles');
        if (isset($filters['is_active'])) {
            $query->where('is_active', $filters['is_active']);
        }
        return $query->get();
    }
    public function findById(string $id): ?User {
        return User::with(['posts', 'roles'])->find($id);
    }
    public function create(array $data): User {
        return User::create($data);
    }
    public function update(string $id, array $data): ?User {
        $user = $this->findById($id);
        if ($user) {
            $user->update($data);
        }
        return $user;
    }
    public function delete(string $id): bool {
        $user = $this->findById($id);
        return $user ? $user->delete() : false;
    }
}

// --- FILE: src/Repositories/PostRepository.php ---
namespace App\Repositories;
use App\Models\Post;
use Illuminate\Database\Eloquent\Collection;

class PostRepository {
    public function findAll(): Collection {
        return Post::with('user')->get();
    }
    public function create(array $data): Post {
        return Post::create($data);
    }
}

// --- FILE: src/Services/UserService.php ---
namespace App\Services;
use App\Repositories\UserRepository;
use App\Models\User;
use App\Models\Role;
use Illuminate\Database\Capsule\Manager as DB;

class UserService {
    public function __construct(private UserRepository $userRepository) {}

    public function getUsers(array $filters) {
        return $this->userRepository->findByFilters($filters);
    }
    public function getUserById(string $id) {
        return $this->userRepository->findById($id);
    }
    public function createUser(array $data): User {
        $data['password_hash'] = password_hash($data['password'], PASSWORD_DEFAULT);
        unset($data['password']);
        return $this->userRepository->create($data);
    }
    public function updateUser(string $id, array $data) {
        return $this->userRepository->update($id, $data);
    }
    public function deleteUser(string $id): bool {
        return $this->userRepository->delete($id);
    }
    public function assignRole(string $userId, int $roleId): ?User {
        $user = $this->userRepository->findById($userId);
        if ($user && Role::find($roleId)) {
            $user->roles()->syncWithoutDetaching([$roleId]);
            return $user->load('roles');
        }
        return null;
    }
    public function createUserWithRoleInTransaction(array $userData, bool $shouldFail): User {
        return DB::transaction(function () use ($userData, $shouldFail) {
            $user = $this->createUser($userData);
            $role = Role::where('name', 'USER')->firstOrFail();
            $this->assignRole($user->id, $role->id);
            if ($shouldFail) {
                throw new \Exception("Transaction failed intentionally.");
            }
            return $user;
        });
    }
}

// --- FILE: src/Services/PostService.php ---
namespace App\Services;
use App\Repositories\PostRepository;
use App\Models\Post;

class PostService {
    public function __construct(private PostRepository $postRepository) {}
    public function getPosts() { return $this->postRepository->findAll(); }
    public function createPost(array $data): Post { return $this->postRepository->create($data); }
}

// --- FILE: src/Controllers/UserController.php ---
namespace App\Controllers;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use App\Services\UserService;

class UserController {
    public function __construct(private UserService $userService) {}

    private function jsonResponse(Response $response, $data, int $status = 200): Response {
        $response->getBody()->write(json_encode($data));
        return $response->withHeader('Content-Type', 'application/json')->withStatus($status);
    }

    public function index(Request $request, Response $response): Response {
        $filters = $request->getQueryParams();
        $users = $this->userService->getUsers($filters);
        return $this->jsonResponse($response, $users);
    }
    public function show(Request $request, Response $response, array $args): Response {
        $user = $this->userService->getUserById($args['id']);
        return $user ? $this->jsonResponse($response, $user) : $response->withStatus(404);
    }
    public function create(Request $request, Response $response): Response {
        $user = $this->userService->createUser($request->getParsedBody());
        return $this->jsonResponse($response, $user, 201);
    }
    public function update(Request $request, Response $response, array $args): Response {
        $user = $this->userService->updateUser($args['id'], $request->getParsedBody());
        return $user ? $this->jsonResponse($response, $user) : $response->withStatus(404);
    }
    public function delete(Request $request, Response $response, array $args): Response {
        return $this->userService->deleteUser($args['id']) ? $response->withStatus(204) : $response->withStatus(404);
    }
    public function assignRole(Request $request, Response $response, array $args): Response {
        $roleId = $request->getParsedBody()['role_id'];
        $user = $this->userService->assignRole($args['id'], $roleId);
        return $user ? $this->jsonResponse($response, $user) : $response->withStatus(404);
    }
    public function transactionDemo(Request $request, Response $response): Response {
        $data = $request->getParsedBody();
        try {
            $user = $this->userService->createUserWithRoleInTransaction($data, $data['fail'] ?? false);
            return $this->jsonResponse($response, ['status' => 'success', 'user' => $user], 201);
        } catch (\Exception $e) {
            return $this->jsonResponse($response, ['status' => 'rollback', 'error' => $e->getMessage()], 400);
        }
    }
}

// --- FILE: src/Controllers/PostController.php ---
namespace App\Controllers;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use App\Services\PostService;

class PostController {
    public function __construct(private PostService $postService) {}
    private function jsonResponse(Response $response, $data, int $status = 200): Response {
        $response->getBody()->write(json_encode($data));
        return $response->withHeader('Content-Type', 'application/json')->withStatus($status);
    }
    public function index(Request $request, Response $response): Response {
        return $this->jsonResponse($response, $this->postService->getPosts());
    }
    public function create(Request $request, Response $response): Response {
        return $this->jsonResponse($response, $this->postService->createPost($request->getParsedBody()), 201);
    }
}

// --- FILE: src/Database/Migrations.php ---
namespace App\Database;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Database\Schema\Builder;
class Migrations {
    public static function up(Builder $schema): void {
        $schema->create('users', fn(Blueprint $t) => {$t->uuid('id')->primary();$t->string('email')->unique();$t->string('password_hash');$t->boolean('is_active')->default(true);$t->timestamps();});
        $schema->create('posts', fn(Blueprint $t) => {$t->uuid('id')->primary();$t->foreignUuid('user_id')->constrained('users')->onDelete('cascade');$t->string('title');$t->text('content');$t->string('status')->default('draft');$t->timestamps();});
        $schema->create('roles', fn(Blueprint $t) => {$t->increments('id');$t->string('name')->unique();});
        $schema->create('user_roles', fn(Blueprint $t) => {$t->foreignUuid('user_id')->constrained('users')->onDelete('cascade');$t->unsignedInteger('role_id')->constrained('roles')->onDelete('cascade');$t->primary(['user_id', 'role_id']);});
    }
}

// --- FILE: src/Database/Seeder.php ---
namespace App\Database;
use App\Models\User; use App\Models\Post; use App\Models\Role; use App\Models\PostStatus;
class Seeder {
    public static function run(): void {
        if (Role::count() > 0) return;
        $adminRole = Role::create(['name' => 'ADMIN']); $userRole = Role::create(['name' => 'USER']);
        $user1 = User::create(['email' => 'admin@example.com', 'password_hash' => password_hash('admin123', PASSWORD_DEFAULT), 'is_active' => true]);
        $user1->roles()->attach([$adminRole->id, $userRole->id]);
        Post::create(['user_id' => $user1->id, 'title' => 'First Post', 'content' => 'Content here', 'status' => PostStatus::PUBLISHED]);
    }
}