<?php

// Variation 1: The Pragmatist
// Style: Standard, by-the-book Laravel.
// Auth: Laravel Sanctum for API tokens, standard session auth for web.
// RBAC: A simple, custom middleware for role checks.
// Structure: Logic is primarily in controllers, following default Laravel conventions.

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
use Laravel\Sanctum\HasApiTokens;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use App\Enums\UserRole;

class User extends Authenticatable
{
    use HasApiTokens, HasFactory, Notifiable, HasUuids;

    protected $fillable = [
        'email',
        'password_hash',
        'role',
        'is_active',
    ];

    protected $hidden = [
        'password_hash',
    ];

    protected $casts = [
        'password_hash' => 'hashed',
        'role' => UserRole::class,
        'is_active' => 'boolean',
        'id' => 'string',
    ];

    public function getAuthPassword()
    {
        return $this->password_hash;
    }
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

    protected $fillable = [
        'user_id',
        'title',
        'content',
        'status',
    ];

    protected $casts = [
        'status' => PostStatus::class,
        'id' => 'string',
        'user_id' => 'string',
    ];

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }
}

// --- FILE: app/Http/Middleware/EnsureUserHasRole.php ---
namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;
use App\Enums\UserRole;

class EnsureUserHasRole
{
    public function handle(Request $request, Closure $next, string $role): Response
    {
        $userRole = $request->user()->role;

        if ($userRole->value !== $role) {
            return response()->json(['message' => 'Forbidden.'], 403);
        }
        
        // Also allow ADMINs to access any role-protected route
        if ($userRole === UserRole::ADMIN) {
            return $next($request);
        }

        return $next($request);
    }
}

// --- FILE: app/Http/Controllers/Api/AuthController.php ---
namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Illuminate\Support\Facades\Hash;
use App\Models\User;
use Illuminate\Validation\ValidationException;

class AuthController extends Controller
{
    public function login(Request $request)
    {
        $request->validate([
            'email' => 'required|email',
            'password' => 'required',
        ]);

        $user = User::where('email', $request->email)->where('is_active', true)->first();

        if (! $user || ! Hash::check($request->password, $user->password_hash)) {
            throw ValidationException::withMessages([
                'email' => ['The provided credentials do not match our records.'],
            ]);
        }

        $token = $user->createToken('api-token')->plainTextToken;

        return response()->json(['token' => $token]);
    }

    public function logout(Request $request)
    {
        $request->user()->currentAccessToken()->delete();
        return response()->json(['message' => 'Logged out successfully.']);
    }
}

// --- FILE: app/Http/Controllers/Api/PostController.php ---
namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Post;
use Illuminate\Http\Request;

class PostController extends Controller
{
    public function index()
    {
        // Any authenticated user can view posts
        return Post::with('user:id,email')->paginate(15);
    }

    public function store(Request $request)
    {
        // RBAC handled by middleware in routes/api.php
        $validated = $request->validate([
            'title' => 'required|string|max:255',
            'content' => 'required|string',
            'status' => 'required|in:draft,published',
        ]);

        $post = $request->user()->posts()->create($validated);

        return response()->json($post, 201);
    }
}

// --- FILE: routes/api.php ---
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Route;
use App\Http\Controllers\Api\AuthController;
use App\Http\Controllers\Api\PostController;
use App\Http\Middleware\EnsureUserHasRole;

// Mock User creation for testing
Route::get('/setup-mock-data', function() {
    \App\Models\User::query()->delete();
    $admin = \App\Models\User::create([
        'id' => \Illuminate\Support\Str::uuid(),
        'email' => 'admin@example.com',
        'password_hash' => \Illuminate\Support\Facades\Hash::make('password'),
        'role' => \App\Enums\UserRole::ADMIN,
        'is_active' => true,
    ]);
    $user = \App\Models\User::create([
        'id' => \Illuminate\Support\Str::uuid(),
        'email' => 'user@example.com',
        'password_hash' => \Illuminate\Support\Facades\Hash::make('password'),
        'role' => \App\Enums\UserRole::USER,
        'is_active' => true,
    ]);
    return ['admin' => $admin->email, 'user' => $user->email];
});


Route::post('/login', [AuthController::class, 'login']);

Route::middleware('auth:sanctum')->group(function () {
    Route::post('/logout', [AuthController::class, 'logout']);
    Route::get('/user', fn(Request $request) => $request->user());

    // All authenticated users can view posts
    Route::get('/posts', [PostController::class, 'index']);

    // Only ADMINs can create posts
    Route::post('/posts', [PostController::class, 'store'])
         ->middleware(EnsureUserHasRole::class . ':admin');
});

?>