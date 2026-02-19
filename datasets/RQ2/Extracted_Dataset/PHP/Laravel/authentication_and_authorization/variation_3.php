<?php

// Variation 3: The API-First Developer
// Style: Lean, concise, and focused on the API contract.
// Auth: Laravel Sanctum, purely for stateless token-based authentication.
// RBAC: Authorization logic is co-located with validation inside Form Request classes.
// Structure: Controllers are very thin, acting as a simple routing layer.

// --- FILE: app/Enums/Role.php ---
namespace App\Enums;

enum Role: string
{
    case ADMIN = 'admin';
    case USER = 'user';
}

// --- FILE: app/Enums/Status.php ---
namespace App\Enums;

enum Status: string
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
use App\Enums\Role;

class User extends Authenticatable
{
    use HasApiTokens, HasFactory, Notifiable, HasUuids;

    protected $fillable = ['email', 'password_hash', 'role', 'is_active'];
    protected $hidden = ['password_hash'];
    protected $casts = [
        'password_hash' => 'hashed',
        'role' => Role::class,
        'is_active' => 'boolean',
        'id' => 'string',
    ];

    public function getAuthPassword() { return $this->password_hash; }
    public function isAdmin(): bool { return $this->role === Role::ADMIN; }
}

// --- FILE: app/Models/Post.php ---
namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use App\Enums\Status;

class Post extends Model
{
    use HasFactory, HasUuids;
    protected $fillable = ['user_id', 'title', 'content', 'status'];
    protected $casts = [
        'status' => Status::class,
        'id' => 'string',
        'user_id' => 'string',
    ];
    public function user(): BelongsTo { return $this->belongsTo(User::class); }
}

// --- FILE: app/Http/Requests/StorePostRequest.php ---
namespace App\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rule;
use App\Enums\Status;
use App\Enums\Role;

class StorePostRequest extends FormRequest
{
    public function authorize(): bool
    {
        // Only users with the ADMIN role can create posts.
        return $this->user()->role === Role::ADMIN;
    }

    public function rules(): array
    {
        return [
            'title' => 'required|string|max:255',
            'content' => 'required|string',
            'status' => ['required', Rule::enum(Status::class)],
        ];
    }
}

// --- FILE: app/Http/Requests/UpdatePostRequest.php ---
namespace App\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rule;
use App\Enums\Status;

class UpdatePostRequest extends FormRequest
{
    public function authorize(): bool
    {
        $post = $this->route('post'); // Assumes route model binding
        
        // An ADMIN can update any post, or a user can update their own post.
        return $this->user()->isAdmin() || $this->user()->id === $post->user_id;
    }

    public function rules(): array
    {
        return [
            'title' => 'sometimes|required|string|max:255',
            'content' => 'sometimes|required|string',
            'status' => ['sometimes', 'required', Rule::enum(Status::class)],
        ];
    }
}

// --- FILE: app/Http/Controllers/API/AuthenticationController.php ---
namespace App\Http\Controllers\API;

use App\Http\Controllers\Controller;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use App\Models\User;
use Illuminate\Http\JsonResponse;

class AuthenticationController extends Controller
{
    public function store(Request $request): JsonResponse
    {
        $credentials = $request->validate([
            'email' => ['required', 'email'],
            'password' => ['required'],
        ]);

        if (!Auth::attempt(['email' => $credentials['email'], 'password' => $credentials['password'], 'is_active' => true])) {
            return response()->json(['message' => 'Invalid login details'], 401);
        }

        $user = User::where('email', $request->email)->firstOrFail();
        $token = $user->createToken('auth_token')->plainTextToken;

        return response()->json(['access_token' => $token, 'token_type' => 'Bearer']);
    }

    public function destroy(Request $request): JsonResponse
    {
        $request->user()->currentAccessToken()->delete();
        return response()->json(['message' => 'Successfully logged out']);
    }
}

// --- FILE: app/Http/Controllers/API/PostController.php ---
namespace App\Http\Controllers\API;

use App\Http\Controllers\Controller;
use App\Models\Post;
use App\Http\Requests\StorePostRequest;
use App\Http\Requests\UpdatePostRequest;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class PostController extends Controller
{
    public function index(): JsonResponse
    {
        return response()->json(Post::paginate(20));
    }

    public function store(StorePostRequest $request): JsonResponse
    {
        $post = $request->user()->posts()->create($request->validated());
        return response()->json($post, 201);
    }

    public function show(Post $post): JsonResponse
    {
        return response()->json($post->load('user'));
    }

    public function update(UpdatePostRequest $request, Post $post): JsonResponse
    {
        $post->update($request->validated());
        return response()->json($post);
    }
}

// --- FILE: routes/api.php ---
use Illuminate\Support\Facades\Route;
use App\Http\Controllers\API\AuthenticationController;
use App\Http\Controllers\API\PostController;

Route::post('/auth/token', [AuthenticationController::class, 'store']);

Route::middleware('auth:sanctum')->group(function () {
    Route::delete('/auth/token', [AuthenticationController::class, 'destroy']);
    Route::apiResource('posts', PostController::class);
});

?>