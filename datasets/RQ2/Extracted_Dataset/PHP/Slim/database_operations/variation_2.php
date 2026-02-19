<?php

// VARIATION 2: Action-Domain-Responder (ADR) / Single Action Controller Approach
// This variation structures the application around "Actions", where each action is a
// class responsible for handling a single route. This promotes the Single Responsibility
// Principle and can make the application easier to scale and test.

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
use App\Infrastructure\Database\CapsuleFactory;
use App\Infrastructure\Database\MigrationManager;
use App\Infrastructure\Database\DataSeeder;

require __DIR__ . '/vendor/autoload.php';

// --- Dependency Injection Container Setup ---
$container = new Container();
AppFactory::setContainer($container);

// --- Database Setup (Eloquent ORM) ---
$container->set('db', function () {
    return CapsuleFactory::create();
});

// Initialize DB and run migrations/seeding
$db = $container->get('db');
MigrationManager::up($db->schema());
DataSeeder::run();

// --- App Initialization ---
$app = AppFactory::create();
$app->addBodyParsingMiddleware();
$app->addRoutingMiddleware();
$app->addErrorMiddleware(true, true, true);

// --- Route Definitions ---
$app->get('/', function ($request, $response) {
    $response->getBody()->write("Variation 2: ADR / Single Action Controller Approach");
    return $response;
});

// User Routes mapped to Action classes
$app->get('/users', App\Application\Actions\User\ListUsersAction::class);
$app->post('/users', App\Application\Actions\User\CreateUserAction::class);
$app->get('/users/{id}', App\Application\Actions\User\ViewUserAction::class);
$app->put('/users/{id}', App\Application\Actions\User\UpdateUserAction::class);
$app->delete('/users/{id}', App\Application\Actions\User\DeleteUserAction::class);
$app->post('/users/{id}/roles', App\Application\Actions\User\AssignRoleAction::class);

// Post Routes
$app->get('/posts', App\Application\Actions\Post\ListPostsAction::class);
$app->post('/posts', App\Application\Actions\Post\CreatePostAction::class);

// Transaction Demo Route
$app->post('/transaction-demo', App\Application\Actions\User\TransactionDemoAction::class);

$app->run();


// --- FILE: src/Domain/Models/BaseModel.php ---
namespace App\Domain\Models;

use Illuminate\Database\Eloquent\Model;
use Ramsey\Uuid\Uuid;

abstract class BaseModel extends Model
{
    public $incrementing = false;
    protected $keyType = 'string';

    protected static function boot()
    {
        parent::boot();
        static::creating(function ($model) {
            if (empty($model->{$model->getKeyName()})) {
                $model->{$model->getKeyName()} = Uuid::uuid4()->toString();
            }
        });
    }
}

// --- FILE: src/Domain/Models/User.php ---
namespace App\Domain\Models;

class User extends BaseModel
{
    protected $table = 'users';
    protected $fillable = ['email', 'password_hash', 'is_active'];
    protected $casts = ['is_active' => 'boolean'];
    protected $hidden = ['password_hash'];

    public function posts() { return $this->hasMany(Post::class); }
    public function roles() { return $this->belongsToMany(Role::class, 'user_roles'); }
}

// --- FILE: src/Domain/Models/Post.php ---
namespace App\Domain\Models;

use App\Domain\Enums\PostStatus;

class Post extends BaseModel
{
    protected $table = 'posts';
    protected $fillable = ['user_id', 'title', 'content', 'status'];
    protected $casts = ['status' => PostStatus::class];

    public function user() { return $this->belongsTo(User::class); }
}

// --- FILE: src/Domain/Models/Role.php ---
namespace App\Domain\Models;

use Illuminate\Database\Eloquent\Model;

class Role extends Model
{
    public $timestamps = false;
    protected $fillable = ['name'];

    public function users() { return $this->belongsToMany(User::class, 'user_roles'); }
}

// --- FILE: src/Domain/Enums/PostStatus.php ---
namespace App\Domain\Enums;

enum PostStatus: string
{
    case DRAFT = 'draft';
    case PUBLISHED = 'published';
}

// --- FILE: src/Application/Actions/Action.php (Abstract Base Action) ---
namespace App\Application\Actions;

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;

abstract class Action
{
    abstract public function __invoke(Request $request, Response $response, array $args): Response;

    protected function respondWithData(Response $response, $data, int $statusCode = 200): Response
    {
        $response->getBody()->write(json_encode($data));
        return $response->withHeader('Content-Type', 'application/json')->withStatus($statusCode);
    }

    protected function respondWithError(Response $response, string $message, int $statusCode = 400): Response
    {
        return $this->respondWithData($response, ['error' => $message], $statusCode);
    }
}

// --- FILE: src/Application/Actions/User/ListUsersAction.php ---
namespace App\Application\Actions\User;

use App\Application\Actions\Action;
use App\Domain\Models\User;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;

class ListUsersAction extends Action
{
    public function __invoke(Request $request, Response $response, array $args): Response
    {
        $params = $request->getQueryParams();
        $query = User::query()->with('roles');

        if (isset($params['is_active'])) {
            $query->where('is_active', filter_var($params['is_active'], FILTER_VALIDATE_BOOLEAN));
        }

        $users = $query->get();
        return $this->respondWithData($response, $users);
    }
}

// --- FILE: src/Application/Actions/User/CreateUserAction.php ---
namespace App\Application\Actions\User;

use App\Application\Actions\Action;
use App\Domain\Models\User;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;

class CreateUserAction extends Action
{
    public function __invoke(Request $request, Response $response, array $args): Response
    {
        $data = $request->getParsedBody();
        $data['password_hash'] = password_hash($data['password'], PASSWORD_DEFAULT);
        $user = User::create($data);
        return $this->respondWithData($response, $user, 201);
    }
}

// --- FILE: src/Application/Actions/User/ViewUserAction.php ---
namespace App\Application\Actions\User;

use App\Application\Actions\Action;
use App\Domain\Models\User;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;

class ViewUserAction extends Action
{
    public function __invoke(Request $request, Response $response, array $args): Response
    {
        $user = User::with(['posts', 'roles'])->find($args['id']);
        if (!$user) {
            return $this->respondWithError($response, 'User not found', 404);
        }
        return $this->respondWithData($response, $user);
    }
}

// --- FILE: src/Application/Actions/User/UpdateUserAction.php ---
namespace App\Application\Actions\User;

use App\Application\Actions\Action;
use App\Domain\Models\User;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;

class UpdateUserAction extends Action
{
    public function __invoke(Request $request, Response $response, array $args): Response
    {
        $user = User::find($args['id']);
        if (!$user) {
            return $this->respondWithError($response, 'User not found', 404);
        }
        $user->update($request->getParsedBody());
        return $this->respondWithData($response, $user);
    }
}

// --- FILE: src/Application/Actions/User/DeleteUserAction.php ---
namespace App\Application\Actions\User;

use App\Application\Actions\Action;
use App\Domain\Models\User;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;

class DeleteUserAction extends Action
{
    public function __invoke(Request $request, Response $response, array $args): Response
    {
        $user = User::find($args['id']);
        if ($user) {
            $user->delete();
        }
        return $response->withStatus(204);
    }
}

// --- FILE: src/Application/Actions/User/AssignRoleAction.php ---
namespace App\Application\Actions\User;

use App\Application\Actions\Action;
use App\Domain\Models\User;
use App\Domain\Models\Role;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;

class AssignRoleAction extends Action
{
    public function __invoke(Request $request, Response $response, array $args): Response
    {
        $user = User::find($args['id']);
        $roleId = $request->getParsedBody()['role_id'] ?? null;
        $role = $roleId ? Role::find($roleId) : null;

        if (!$user || !$role) {
            return $this->respondWithError($response, 'User or Role not found', 404);
        }

        $user->roles()->syncWithoutDetaching([$role->id]);
        return $this->respondWithData($response, $user->load('roles'));
    }
}

// --- FILE: src/Application/Actions/Post/ListPostsAction.php ---
namespace App\Application\Actions\Post;

use App\Application\Actions\Action;
use App\Domain\Models\Post;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;

class ListPostsAction extends Action
{
    public function __invoke(Request $request, Response $response, array $args): Response
    {
        $status = $request->getQueryParams()['status'] ?? null;
        $query = Post::query()->with('user');

        if ($status) {
            $query->where('status', $status);
        }

        return $this->respondWithData($response, $query->get());
    }
}

// --- FILE: src/Application/Actions/Post/CreatePostAction.php ---
namespace App\Application\Actions\Post;

use App\Application\Actions\Action;
use App\Domain\Models\Post;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;

class CreatePostAction extends Action
{
    public function __invoke(Request $request, Response $response, array $args): Response
    {
        $data = $request->getParsedBody();
        $post = Post::create($data);
        return $this->respondWithData($response, $post, 201);
    }
}

// --- FILE: src/Application/Actions/User/TransactionDemoAction.php ---
namespace App\Application\Actions\User;

use App\Application\Actions\Action;
use App\Domain\Models\User;
use App\Domain\Models\Role;
use Illuminate\Database\Capsule\Manager as DB;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;

class TransactionDemoAction extends Action
{
    public function __invoke(Request $request, Response $response, array $args): Response
    {
        $data = $request->getParsedBody();
        $shouldFail = $data['fail'] ?? false;

        try {
            DB::transaction(function () use ($data, $shouldFail) {
                $newUser = User::create([
                    'email' => $data['email'],
                    'password_hash' => password_hash('password', PASSWORD_DEFAULT),
                ]);
                $userRole = Role::where('name', 'USER')->firstOrFail();
                $newUser->roles()->attach($userRole->id);
                if ($shouldFail) {
                    throw new \RuntimeException("Intentional failure for rollback test.");
                }
            });
        } catch (\Throwable $e) {
            return $this->respondWithError($response, 'Transaction rolled back: ' . $e->getMessage(), 500);
        }

        return $this->respondWithData($response, ['status' => 'Transaction successful'], 201);
    }
}


// --- FILE: src/Infrastructure/Database/CapsuleFactory.php ---
namespace App\Infrastructure\Database;

use Illuminate\Database\Capsule\Manager as Capsule;

class CapsuleFactory
{
    public static function create(): Capsule
    {
        $capsule = new Capsule;
        $capsule->addConnection([
            'driver'    => 'sqlite',
            'database'  => ':memory:',
        ]);
        $capsule->setAsGlobal();
        $capsule->bootEloquent();
        return $capsule;
    }
}

// --- FILE: src/Infrastructure/Database/MigrationManager.php ---
namespace App\Infrastructure\Database;

use Illuminate\Database\Schema\Blueprint;
use Illuminate\Database\Schema\Builder;

class MigrationManager
{
    public static function up(Builder $schema): void
    {
        if (!$schema->hasTable('users')) {
            $schema->create('users', function (Blueprint $table) {
                $table->uuid('id')->primary();
                $table->string('email')->unique();
                $table->string('password_hash');
                $table->boolean('is_active')->default(true);
                $table->timestamps();
            });
        }
        if (!$schema->hasTable('posts')) {
            $schema->create('posts', function (Blueprint $table) {
                $table->uuid('id')->primary();
                $table->foreignUuid('user_id')->constrained('users')->onDelete('cascade');
                $table->string('title');
                $table->text('content');
                $table->string('status')->default('draft');
                $table->timestamps();
            });
        }
        if (!$schema->hasTable('roles')) {
            $schema->create('roles', function (Blueprint $table) {
                $table->increments('id');
                $table->string('name')->unique();
            });
        }
        if (!$schema->hasTable('user_roles')) {
            $schema->create('user_roles', function (Blueprint $table) {
                $table->foreignUuid('user_id')->constrained('users')->onDelete('cascade');
                $table->unsignedInteger('role_id')->constrained('roles')->onDelete('cascade');
                $table->primary(['user_id', 'role_id']);
            });
        }
    }
}

// --- FILE: src/Infrastructure/Database/DataSeeder.php ---
namespace App\Infrastructure\Database;

use App\Domain\Models\User;
use App\Domain\Models\Post;
use App\Domain\Models\Role;
use App\Domain\Enums\PostStatus;

class DataSeeder
{
    public static function run(): void
    {
        if (Role::count() > 0) return; // Seed only once

        $adminRole = Role::create(['name' => 'ADMIN']);
        $userRole = Role::create(['name' => 'USER']);

        $user1 = User::create([
            'email' => 'admin@example.com',
            'password_hash' => password_hash('admin123', PASSWORD_DEFAULT),
            'is_active' => true,
        ]);
        $user1->roles()->attach([$adminRole->id, $userRole->id]);

        $user2 = User::create([
            'email' => 'user@example.com',
            'password_hash' => password_hash('user123', PASSWORD_DEFAULT),
            'is_active' => false,
        ]);
        $user2->roles()->attach($userRole->id);

        Post::create([
            'user_id' => $user1->id,
            'title' => 'Admin\'s First Post',
            'content' => 'This is a post by the admin.',
            'status' => PostStatus::PUBLISHED,
        ]);
    }
}