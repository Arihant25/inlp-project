<?php

// VARIATION 3: Functional / Route Closure Approach
// This variation places all logic directly within route closures. It's excellent for
// rapid prototyping, small APIs, or microservices where the overhead of controllers
// or action classes is unnecessary. All code is contained in a single file for simplicity.

// --- FILE: composer.json ---
// {
//     "require": {
//         "slim/slim": "4.*",
//         "slim/psr7": "^1.6",
//         "illuminate/database": "^10.0",
//         "ramsey/uuid": "^4.7"
//     }
// }

// To run:
// 1. composer install
// 2. php -S localhost:8080 index.php

require __DIR__ . '/vendor/autoload.php';

use Slim\Factory\AppFactory;
use Illuminate\Database\Capsule\Manager as Capsule;
use Illuminate\Database\Schema\Blueprint;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Ramsey\Uuid\Uuid;

// --- Eloquent Model Definitions ---
// In this approach, models can be defined directly in the main file.

abstract class BaseModel extends Illuminate\Database\Eloquent\Model
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

class User extends BaseModel
{
    protected $table = 'users';
    protected $fillable = ['email', 'password_hash', 'is_active'];
    protected $casts = ['is_active' => 'boolean'];
    protected $hidden = ['password_hash'];

    public function posts() { return $this->hasMany(Post::class); }
    public function roles() { return $this->belongsToMany(Role::class, 'user_roles'); }
}

enum PostStatus: string
{
    case DRAFT = 'draft';
    case PUBLISHED = 'published';
}

class Post extends BaseModel
{
    protected $table = 'posts';
    protected $fillable = ['user_id', 'title', 'content', 'status'];
    protected $casts = ['status' => PostStatus::class];

    public function user() { return $this->belongsTo(User::class); }
}

class Role extends Illuminate\Database\Eloquent\Model
{
    public $timestamps = false;
    protected $fillable = ['name'];

    public function users() { return $this->belongsToMany(User::class, 'user_roles'); }
}

// --- Database Setup (Eloquent ORM) ---
$capsule = new Capsule;
$capsule->addConnection([
    'driver'    => 'sqlite',
    'database'  => ':memory:',
]);
$capsule->setAsGlobal();
$capsule->bootEloquent();
$schema = $capsule->schema();

// --- Database Migrations & Seeding ---
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
    $table->string('status')->default('draft');
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

$adminRole = Role::create(['name' => 'ADMIN']);
$userRole = Role::create(['name' => 'USER']);
$user1 = User::create([
    'email' => 'admin@example.com',
    'password_hash' => password_hash('admin123', PASSWORD_DEFAULT),
    'is_active' => true,
]);
$user1->roles()->attach([$adminRole->id, $userRole->id]);

// --- App Initialization ---
$app = AppFactory::create();
$app->addBodyParsingMiddleware();
$app->addRoutingMiddleware();
$app->addErrorMiddleware(true, true, true);

// --- Route Definitions ---

$app->get('/', function (Request $request, Response $response) {
    $response->getBody()->write("Variation 3: Functional / Route Closure Approach");
    return $response;
});

// --- User CRUD ---
$app->get('/users', function (Request $request, Response $response) {
    $query_params = $request->getQueryParams();
    $query = User::query()->with('roles');

    // Query building with filters
    if (isset($query_params['is_active'])) {
        $query->where('is_active', filter_var($query_params['is_active'], FILTER_VALIDATE_BOOLEAN));
    }

    $users = $query->get();
    $response->getBody()->write(json_encode($users));
    return $response->withHeader('Content-Type', 'application/json');
});

$app->post('/users', function (Request $request, Response $response) {
    $data = $request->getParsedBody();
    $data['password_hash'] = password_hash($data['password'], PASSWORD_DEFAULT);
    $user = User::create($data);
    $response->getBody()->write(json_encode($user));
    return $response->withStatus(201)->withHeader('Content-Type', 'application/json');
});

$app->get('/users/{id}', function (Request $request, Response $response, array $args) {
    $user = User::with(['posts', 'roles'])->find($args['id']);
    if (!$user) {
        $response->getBody()->write(json_encode(['error' => 'User not found']));
        return $response->withStatus(404)->withHeader('Content-Type', 'application/json');
    }
    $response->getBody()->write(json_encode($user));
    return $response->withHeader('Content-Type', 'application/json');
});

$app->put('/users/{id}', function (Request $request, Response $response, array $args) {
    $user = User::find($args['id']);
    if (!$user) {
        return $response->withStatus(404);
    }
    $user->update($request->getParsedBody());
    $response->getBody()->write(json_encode($user));
    return $response->withHeader('Content-Type', 'application/json');
});

$app->delete('/users/{id}', function (Request $request, Response $response, array $args) {
    $user = User::find($args['id']);
    if ($user) {
        $user->delete();
    }
    return $response->withStatus(204);
});

// --- Relationships ---
$app->post('/users/{id}/roles', function (Request $request, Response $response, array $args) {
    $user = User::find($args['id']);
    $role_id = $request->getParsedBody()['role_id'];
    if (!$user || !Role::find($role_id)) {
        return $response->withStatus(404);
    }
    $user->roles()->syncWithoutDetaching([$role_id]);
    $response->getBody()->write(json_encode($user->load('roles')));
    return $response->withHeader('Content-Type', 'application/json');
});

$app->get('/posts', function (Request $request, Response $response) {
    $posts = Post::with('user')->get();
    $response->getBody()->write(json_encode($posts));
    return $response->withHeader('Content-Type', 'application/json');
});

$app->post('/posts', function (Request $request, Response $response) {
    $data = $request->getParsedBody();
    $post = Post::create($data);
    $response->getBody()->write(json_encode($post));
    return $response->withStatus(201)->withHeader('Content-Type', 'application/json');
});

// --- Transaction and Rollback Demo ---
$app->post('/transaction-demo', function (Request $request, Response $response) use ($capsule) {
    $data = $request->getParsedBody();
    $should_fail = $data['fail'] ?? false;

    try {
        $capsule->getConnection()->transaction(function () use ($data, $should_fail) {
            $new_user = User::create([
                'email' => $data['email'],
                'password_hash' => password_hash('password', PASSWORD_DEFAULT),
            ]);

            $user_role = Role::where('name', 'USER')->firstOrFail();
            $new_user->roles()->attach($user_role->id);

            if ($should_fail) {
                throw new Exception("Simulating an error to test rollback.");
            }
        });
    } catch (Exception $e) {
        $response->getBody()->write(json_encode(['status' => 'Transaction rolled back', 'error' => $e->getMessage()]));
        return $response->withStatus(400)->withHeader('Content-Type', 'application/json');
    }

    $response->getBody()->write(json_encode(['status' => 'Transaction successful']));
    return $response->withStatus(201)->withHeader('Content-Type', 'application/json');
});

$app->run();