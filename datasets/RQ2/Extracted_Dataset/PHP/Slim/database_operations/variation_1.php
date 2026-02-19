<?php

// VARIATION 1: Classic OOP Controller Approach
// This variation uses dedicated controller classes to handle application logic,
// promoting a clear separation of concerns between routing, business logic, and data access.
// It's a common and well-understood pattern for building structured web applications.

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

require __DIR__ . '/vendor/autoload.php';

// --- Dependency Injection Container Setup ---
$container = new Container();
AppFactory::setContainer($container);

// --- Database Setup (Eloquent ORM) ---
$container->set('db', function () {
    $capsule = new Capsule;
    $capsule->addConnection([
        'driver'    => 'sqlite',
        'database'  => ':memory:',
        'prefix'    => '',
    ]);
    $capsule->setAsGlobal();
    $capsule->bootEloquent();
    return $capsule;
});

// Initialize the database connection to run migrations
$db = $container->get('db');

// --- Database Migrations ---
// In a real app, this would be handled by a migration tool like Phinx.
// For this self-contained example, we run them on startup.
require_once __DIR__ . '/src/Database/Migrations.php';
App\Database\Migrations::up($db->schema());

// --- Seed Data ---
require_once __DIR__ . '/src/Database/Seeder.php';
App\Database\Seeder::run();

// --- App Initialization ---
$app = AppFactory::create();
$app->addBodyParsingMiddleware();
$app->addRoutingMiddleware();
$app->addErrorMiddleware(true, true, true);

// --- Route Definitions ---
$app->get('/', function (Request $request, Response $response) {
    $response->getBody()->write("Variation 1: Classic OOP Controller Approach");
    return $response;
});

// User Routes
$app->group('/users', function (RouteCollectorProxy $group) {
    $group->get('', [UserController::class, 'getAll']);
    $group->post('', [UserController::class, 'create']);
    $group->get('/{id}', [UserController::class, 'getById']);
    $group->put('/{id}', [UserController::class, 'update']);
    $group->delete('/{id}', [UserController::class, 'delete']);
    $group->post('/{id}/roles', [UserController::class, 'assignRole']);
});

// Post Routes (demonstrating one-to-many)
$app->group('/posts', function (RouteCollectorProxy $group) {
    $group->get('', [PostController::class, 'getAll']);
    $group->post('', [PostController::class, 'create']);
});

// Transaction Demo Route
$app->post('/transaction-demo', [UserController::class, 'transactionDemo']);

$app->run();


// --- FILE: src/Models/BaseModel.php ---
namespace App\Models;

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

// --- FILE: src/Models/User.php ---
namespace App\Models;

use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Database\Eloquent\Relations\BelongsToMany;

class User extends BaseModel
{
    protected $table = 'users';
    protected $fillable = ['email', 'password_hash', 'is_active'];
    protected $casts = ['is_active' => 'boolean', 'created_at' => 'datetime', 'updated_at' => 'datetime'];
    protected $hidden = ['password_hash'];

    public function posts(): HasMany
    {
        return $this->hasMany(Post::class);
    }

    public function roles(): BelongsToMany
    {
        return $this->belongsToMany(Role::class, 'user_roles');
    }
}

// --- FILE: src/Models/Post.php ---
namespace App\Models;

use Illuminate\Database\Eloquent\Relations\BelongsTo;

// Enum for Post Status (PHP 8.1+)
enum PostStatus: string
{
    case DRAFT = 'draft';
    case PUBLISHED = 'published';
}

class Post extends BaseModel
{
    protected $table = 'posts';
    protected $fillable = ['user_id', 'title', 'content', 'status'];
    protected $casts = [
        'status' => PostStatus::class,
        'created_at' => 'datetime',
        'updated_at' => 'datetime'
    ];

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }
}

// --- FILE: src/Models/Role.php ---
namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsToMany;

class Role extends Model
{
    public $timestamps = false;
    protected $fillable = ['name'];

    public function users(): BelongsToMany
    {
        return $this->belongsToMany(User::class, 'user_roles');
    }
}


// --- FILE: src/Controllers/UserController.php ---
namespace App\Controllers;

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use App\Models\User;
use App\Models\Role;
use Illuminate\Database\Capsule\Manager as DB;

class UserController
{
    public function getAll(Request $request, Response $response): Response
    {
        $params = $request->getQueryParams();
        $query = User::query()->with('roles');

        // Query building with filters
        if (isset($params['is_active'])) {
            $query->where('is_active', filter_var($params['is_active'], FILTER_VALIDATE_BOOLEAN));
        }
        if (isset($params['email'])) {
            $query->where('email', 'like', '%' . $params['email'] . '%');
        }

        $users = $query->get();
        $response->getBody()->write(json_encode($users));
        return $response->withHeader('Content-Type', 'application/json');
    }

    public function getById(Request $request, Response $response, array $args): Response
    {
        $user = User::with('posts', 'roles')->find($args['id']);
        if (!$user) {
            $response->getBody()->write(json_encode(['error' => 'User not found']));
            return $response->withStatus(404)->withHeader('Content-Type', 'application/json');
        }
        $response->getBody()->write(json_encode($user));
        return $response->withHeader('Content-Type', 'application/json');
    }

    public function create(Request $request, Response $response): Response
    {
        $data = $request->getParsedBody();
        $data['password_hash'] = password_hash($data['password'], PASSWORD_DEFAULT);
        unset($data['password']);

        $user = User::create($data);
        $response->getBody()->write(json_encode($user));
        return $response->withStatus(201)->withHeader('Content-Type', 'application/json');
    }

    public function update(Request $request, Response $response, array $args): Response
    {
        $user = User::find($args['id']);
        if (!$user) {
            $response->getBody()->write(json_encode(['error' => 'User not found']));
            return $response->withStatus(404)->withHeader('Content-Type', 'application/json');
        }
        $data = $request->getParsedBody();
        $user->update($data);
        $response->getBody()->write(json_encode($user));
        return $response->withHeader('Content-Type', 'application/json');
    }

    public function delete(Request $request, Response $response, array $args): Response
    {
        $user = User::find($args['id']);
        if (!$user) {
            $response->getBody()->write(json_encode(['error' => 'User not found']));
            return $response->withStatus(404)->withHeader('Content-Type', 'application/json');
        }
        $user->delete();
        return $response->withStatus(204);
    }

    public function assignRole(Request $request, Response $response, array $args): Response
    {
        $user = User::find($args['id']);
        $data = $request->getParsedBody();
        $role = Role::find($data['role_id']);

        if (!$user || !$role) {
            $response->getBody()->write(json_encode(['error' => 'User or Role not found']));
            return $response->withStatus(404)->withHeader('Content-Type', 'application/json');
        }

        $user->roles()->syncWithoutDetaching([$role->id]);
        $user->load('roles'); // Reload roles to show in response

        $response->getBody()->write(json_encode($user));
        return $response->withHeader('Content-Type', 'application/json');
    }

    public function transactionDemo(Request $request, Response $response): Response
    {
        $data = $request->getParsedBody();
        $shouldFail = $data['fail'] ?? false;

        try {
            DB::transaction(function () use ($data, $shouldFail) {
                // 1. Create a new user
                $newUser = User::create([
                    'email' => $data['email'],
                    'password_hash' => password_hash('password', PASSWORD_DEFAULT),
                    'is_active' => true,
                ]);

                // 2. Assign the 'USER' role
                $userRole = Role::where('name', 'USER')->firstOrFail();
                $newUser->roles()->attach($userRole->id);

                // 3. Simulate a failure to trigger a rollback
                if ($shouldFail) {
                    throw new \Exception("Simulated failure to trigger rollback.");
                }
            });
        } catch (\Exception $e) {
            $response->getBody()->write(json_encode(['status' => 'Transaction rolled back', 'error' => $e->getMessage()]));
            return $response->withStatus(400)->withHeader('Content-Type', 'application/json');
        }

        $response->getBody()->write(json_encode(['status' => 'Transaction successful']));
        return $response->withStatus(201)->withHeader('Content-Type', 'application/json');
    }
}

// --- FILE: src/Controllers/PostController.php ---
namespace App\Controllers;

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use App\Models\Post;
use App\Models\PostStatus;

class PostController
{
    public function getAll(Request $request, Response $response): Response
    {
        $params = $request->getQueryParams();
        $query = Post::query()->with('user');

        if (isset($params['status'])) {
            $query->where('status', $params['status']);
        }

        $posts = $query->get();
        $response->getBody()->write(json_encode($posts));
        return $response->withHeader('Content-Type', 'application/json');
    }

    public function create(Request $request, Response $response): Response
    {
        $data = $request->getParsedBody();
        $data['status'] = PostStatus::DRAFT; // Default to DRAFT
        $post = Post::create($data);
        $response->getBody()->write(json_encode($post));
        return $response->withStatus(201)->withHeader('Content-Type', 'application/json');
    }
}

// --- FILE: src/Database/Migrations.php ---
namespace App\Database;

use Illuminate\Database\Schema\Blueprint;
use Illuminate\Database\Schema\Builder;

class Migrations
{
    public static function up(Builder $schema): void
    {
        $schema->create('users', function (Blueprint $table) {
            $table->uuid('id')->primary();
            $table->string('email')->unique();
            $table->string('password_hash');
            $table->boolean('is_active')->default(true);
            $table->timestamps();
        });

        $schema->create('posts', function (Blueprint $table) {
            $table->uuid('id')->primary();
            $table->foreignUuid('user_id')->constrained('users')->onDelete('cascade');
            $table->string('title');
            $table->text('content');
            $table->enum('status', ['draft', 'published'])->default('draft');
            $table->timestamps();
        });

        $schema->create('roles', function (Blueprint $table) {
            $table->increments('id');
            $table->string('name')->unique();
        });



        $schema->create('user_roles', function (Blueprint $table) {
            $table->foreignUuid('user_id')->constrained('users')->onDelete('cascade');
            $table->unsignedInteger('role_id')->constrained('roles')->onDelete('cascade');
            $table->primary(['user_id', 'role_id']);
        });
    }
}

// --- FILE: src/Database/Seeder.php ---
namespace App\Database;

use App\Models\User;
use App\Models\Post;
use App\Models\Role;
use App\Models\PostStatus;

class Seeder
{
    public static function run(): void
    {
        $adminRole = Role::create(['name' => 'ADMIN']);
        $userRole = Role::create(['name' => 'USER']);

        $user1 = User::create([
            'email' => 'admin@example.com',
            'password_hash' => password_hash('admin123', PASSWORD_DEFAULT),
            'is_active' => true,
        ]);
        $user1->roles()->attach($adminRole->id);
        $user1->roles()->attach($userRole->id);

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

        Post::create([
            'user_id' => $user2->id,
            'title' => 'User\'s Draft Post',
            'content' => 'This is a draft post by a user.',
            'status' => PostStatus::DRAFT,
        ]);
    }
}