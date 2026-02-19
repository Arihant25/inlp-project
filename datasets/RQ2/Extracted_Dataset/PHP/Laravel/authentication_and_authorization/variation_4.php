<?php

// Variation 4: The "Classic" Laravel Developer
// Style: A more traditional approach, common in earlier Laravel versions or simpler applications.
// Auth: Standard session-based authentication for web routes and Passport's Password Grant for APIs.
// RBAC: Simple, explicit checks directly within controller methods or a basic middleware.
// Structure: Logic is often placed directly in controllers. Models might be in the root `app` directory.

// --- FILE: app/Enums/UserRole.php ---
namespace App\Enums;

// PHP 8.0 compatible "enum" style using a class with constants
class UserRole
{
    const ADMIN = 'admin';
    const USER = 'user';

    public static function values(): array
    {
        return [self::ADMIN, self::USER];
    }
}

// --- FILE: app/User.php ---
namespace App;

use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Foundation\Auth\User as Authenticatable;
use Illuminate\Notifications\Notifiable;
use Laravel\Passport\HasApiTokens;

class User extends Authenticatable
{
    use HasApiTokens, Notifiable, HasUuids;

    protected $table = 'users';
    public $incrementing = false;
    protected $keyType = 'string';

    protected $fillable = ['email', 'password_hash', 'role', 'is_active'];
    protected $hidden = ['password_hash', 'remember_token'];
    protected $casts = ['is_active' => 'boolean'];

    // Override to use password_hash field
    public function getAuthPassword()
    {
        return $this->password_hash;
    }
}

// --- FILE: app/Post.php ---
namespace App;

use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Model;

class Post extends Model
{
    use HasUuids;

    protected $table = 'posts';
    public $incrementing = false;
    protected $keyType = 'string';

    protected $fillable = ['user_id', 'title', 'content', 'status'];

    public function user()
    {
        return $this->belongsTo(User::class);
    }
}

// --- FILE: app/Http/Middleware/CheckRole.php ---
namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;

class CheckRole
{
    public function handle(Request $request, Closure $next, ...$roles)
    {
        if (!Auth::check() || !in_array(Auth::user()->role, $roles)) {
            // For an API request, return JSON, otherwise redirect.
            if ($request->expectsJson()) {
                return response()->json(['error' => 'Unauthorized.'], 403);
            }
            abort(403, 'Unauthorized action.');
        }

        return $next($request);
    }
}

// --- FILE: app/Http/Controllers/Auth/LoginController.php ---
namespace App\Http\Controllers\Auth;

use App\Http\Controllers\Controller;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;

class LoginController extends Controller
{
    // For web session-based login
    public function login(Request $request)
    {
        $credentials = $request->validate([
            'email' => 'required|email',
            'password' => 'required',
        ]);

        if (Auth::attempt(['email' => $credentials['email'], 'password' => $credentials['password'], 'is_active' => true], $request->filled('remember'))) {
            $request->session()->regenerate();
            return redirect()->intended('dashboard');
        }

        return back()->withErrors([
            'email' => 'The provided credentials do not match our records.',
        ]);
    }

    public function logout(Request $request)
    {
        Auth::logout();
        $request->session()->invalidate();
        $request->session()->regenerateToken();
        return redirect('/');
    }
}

// --- FILE: app/Http/Controllers/PostController.php ---
namespace App\Http\Controllers;

use App\Post;
use App\Enums\UserRole;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Illuminate\Support\Facades\Gate;

class PostController extends Controller
{
    public function __construct()
    {
        // Middleware applied to specific methods
        $this->middleware('auth'); // For web sessions
        $this->middleware('auth:api')->only(['apiIndex', 'apiStore']); // For API
    }

    // Example of a web route controller method
    public function index()
    {
        $posts = Post::all();
        return view('posts.index', compact('posts')); // Assumes a view exists
    }

    // Example of an API route controller method
    public function apiIndex()
    {
        return Post::all();
    }

    // Example of an API method with inline authorization
    public function apiStore(Request $request)
    {
        // Inline RBAC check
        if (Auth::user()->role !== UserRole::ADMIN) {
            return response()->json(['message' => 'Forbidden: Only admins can create posts.'], 403);
        }

        $data = $request->validate([
            'title' => 'required|max:255',
            'content' => 'required',
            'status' => 'required|in:draft,published'
        ]);

        $post = new Post($data);
        $post->user_id = Auth::id();
        $post->save();

        return response()->json($post, 201);
    }
}

// --- FILE: routes/web.php ---
use Illuminate\Support\Facades\Route;
use App\Http\Controllers\Auth\LoginController;
use App\Http\Controllers\PostController;

Route::get('login', [LoginController::class, 'showLoginForm'])->name('login');
Route::post('login', [LoginController::class, 'login']);
Route::post('logout', [LoginController::class, 'logout'])->name('logout');

Route::middleware('auth')->group(function () {
    Route::get('/posts', [PostController::class, 'index'])->name('posts.index');
});

// --- FILE: routes/api.php ---
use Illuminate\Support\Facades\Route;
use App\Http\Controllers\PostController;
use App\Http\Middleware\CheckRole;
use App\Enums\UserRole;

// This route would typically be handled by Passport's default routes
// Route::post('/oauth/token', ...);

Route::middleware(['auth:api'])->group(function () {
    Route::get('/posts', [PostController::class, 'apiIndex']);
    
    // RBAC using a simple middleware
    Route::post('/posts', [PostController::class, 'apiStore'])
         ->middleware(CheckRole::class . ':' . UserRole::ADMIN);
});

?>