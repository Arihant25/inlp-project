<?php

// Variation 2: The OOP Purist
// Style: Emphasizes Object-Oriented principles, separating concerns.
// Auth: Laravel Passport for a full OAuth2 server implementation.
// RBAC: Uses Laravel's built-in Gates and Policies for elegant, model-centric authorization.
// Structure: Business logic is extracted from controllers into Service and Action classes.

// --- FILE: app/Enums/UserRole.php ---
namespace App\Enums;

enum UserRole: string
{
    case ADMIN = 'admin';
    case USER = 'user';
}

// --- FILE: app/Enums/PostStatus.php ---
namespace App\Enums;

enum PostStatus: string
{
    case DRAFT = 'draft';
    case PUBLISHED = 'published';
}

// --- FILE: app/Models/User.php ---
namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Foundation\Auth\User as Authenticatable;
use Illuminate\Notifications\Notifiable;
use Laravel\Passport\HasApiTokens;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use App\Enums\UserRole;

class User extends Authenticatable
{
    use HasApiTokens, HasFactory, Notifiable, HasUuids;

    protected $fillable = ['email', 'password_hash', 'role', 'is_active'];
    protected $hidden = ['password_hash'];
    protected $casts = [
        'password_hash' => 'hashed',
        'role' => UserRole::class,
        'is_active' => 'boolean',
        'id' => 'string',
    ];

    public function getAuthPassword() { return $this->password_hash; }

    public function isAdmin(): bool { return $this->role === UserRole::ADMIN; }
}

// --- FILE: app/Models/Post.php ---
namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use App\Enums\PostStatus;

class Post extends Model
{
    use HasFactory, HasUuids;
    protected $fillable = ['user_id', 'title', 'content', 'status'];
    protected $casts = [
        'status' => PostStatus::class,
        'id' => 'string',
        'user_id' => 'string',
    ];
    public function user(): BelongsTo { return $this->belongsTo(User::class); }
}

// --- FILE: app/Services/AuthenticationService.php ---
namespace App\Services;

use App\Models\User;
use Illuminate\Support\Facades\Http;
use Illuminate\Validation\ValidationException;

class AuthenticationService
{
    public function issueToken(array $credentials): array
    {
        // Assumes a Passport "Password Grant Client" has been created.
        // In a real app, these would come from config/env.
        $passportClient = [
            'id' => env('PASSPORT_PASSWORD_GRANT_CLIENT_ID', '2'),
            'secret' => env('PASSPORT_PASSWORD_GRANT_CLIENT_SECRET', 'your-client-secret'),
        ];

        $response = Http::asForm()->post(url('/oauth/token'), [
            'grant_type' => 'password',
            'client_id' => $passportClient['id'],
            'client_secret' => $passportClient['secret'],
            'username' => $credentials['email'],
            'password' => $credentials['password'],
            'scope' => '*',
        ]);

        if ($response->failed()) {
            throw ValidationException::withMessages([
                'email' => ['Authentication failed.'],
            ]);
        }

        return $response->json();
    }
}

// --- FILE: app/Actions/Posts/CreateNewPost.php ---
namespace App\Actions\Posts;

use App\Models\User;
use App\Models\Post;
use Illuminate\Support\Facades\Validator;
use Illuminate\Validation\Rule;
use App\Enums\PostStatus;

class CreateNewPost
{
    public function execute(User $user, array $data): Post
    {
        Validator::make($data, [
            'title' => ['required', 'string', 'max:255'],
            'content' => ['required', 'string'],
            'status' => ['required', Rule::in(array_column(PostStatus::cases(), 'value'))],
        ])->validate();

        $post = new Post($data);
        $post->user()->associate($user);
        $post->save();

        return $post;
    }
}

// --- FILE: app/Policies/PostPolicy.php ---
namespace App\Policies;

use App\Models\Post;
use App\Models\User;
use Illuminate\Auth\Access\HandlesAuthorization;

class PostPolicy
{
    use HandlesAuthorization;

    public function before(User $user, string $ability): ?bool
    {
        // Admins can do anything
        if ($user->isAdmin()) {
            return true;
        }
        return null; // let other methods decide
    }

    public function viewAny(User $user): bool
    {
        return true; // Any authenticated user can view posts
    }



    public function create(User $user): bool
    {
        // Only admins can create posts (covered by `before` but explicit here)
        return $user->isAdmin();
    }

    public function update(User $user, Post $post): bool
    {
        // User can update their own post
        return $user->id === $post->user_id;
    }
}

// --- FILE: app/Providers/AuthServiceProvider.php ---
namespace App\Providers;

use Illuminate\Foundation\Support\Providers\AuthServiceProvider as ServiceProvider;
use App\Models\Post;
use App\Policies\PostPolicy;
use Laravel\Passport\Passport;

class AuthServiceProvider extends ServiceProvider
{
    protected $policies = [
        Post::class => PostPolicy::class,
    ];

    public function boot(): void
    {
        $this->registerPolicies();
        // Passport::routes(); // This would be in the real app's boot method
    }
}

// --- FILE: app/Http/Controllers/Api/V1/AuthController.php ---
namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use Illuminate\Http\Request;
use App\Services\AuthenticationService;

class AuthController extends Controller
{
    public function __construct(private AuthenticationService $authSvc) {}

    public function login(Request $request)
    {
        $credentials = $request->validate([
            'email' => 'required|email',
            'password' => 'required',
        ]);

        $tokenData = $this->authSvc->issueToken($credentials);
        return response()->json($tokenData);
    }
}

// --- FILE: app/Http/Controllers/Api/V1/PostController.php ---
namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Models\Post;
use Illuminate\Http\Request;
use App\Actions\Posts\CreateNewPost;

class PostController extends Controller
{
    public function __construct()
    {
        // Use policy methods for authorization
        $this->authorizeResource(Post::class, 'post');
    }

    public function index()
    {
        return Post::latest()->paginate();
    }

    public function store(Request $request, CreateNewPost $creator)
    {
        $post = $creator->execute($request->user(), $request->all());
        return response()->json($post, 201);
    }
}

// --- FILE: routes/api.php ---
use Illuminate\Support\Facades\Route;
use App\Http\Controllers\Api\V1\AuthController;
use App\Http\Controllers\Api\V1\PostController;

// Assumes Passport is installed and a password grant client exists.
Route::post('/v1/auth/token', [AuthController::class, 'login']);

Route::middleware('auth:api')->prefix('v1')->group(function () {
    Route::apiResource('posts', PostController::class)->only(['index', 'store']);
});

?>