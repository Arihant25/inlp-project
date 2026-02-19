<?php

// --- FILE: /database/migrations/... (Identical to Variation 1) ---

// --- FILE: /app/Enums/PostStatus.php ---
namespace App\Enums;

enum PostStatus: string
{
    case DRAFT = 'DRAFT';
    case PUBLISHED = 'PUBLISHED';
}

// --- FILE: /app/Models/User.php, Post.php, Role.php (Identical to Variation 1) ---
namespace App\Models;

use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsToMany;
use Illuminate\Database\Eloquent\Relations\HasMany;
use App\Enums\PostStatus;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class User extends Model {
    use HasFactory, HasUuids;
    protected $fillable = ['email', 'password_hash', 'is_active'];
    protected $casts = ['is_active' => 'boolean'];
    public function posts(): HasMany { return $this->hasMany(Post::class); }
    public function roles(): BelongsToMany { return $this->belongsToMany(Role::class); }
}

class Post extends Model {
    use HasFactory, HasUuids;
    protected $fillable = ['user_id', 'title', 'content', 'status'];
    protected $casts = ['status' => PostStatus::class];
    public function user(): BelongsTo { return $this->belongsTo(User::class); }
}

class Role extends Model {
    use HasFactory;
    protected $fillable = ['name'];
    public function users(): BelongsToMany { return $this->belongsToMany(User::class); }
}

// --- FILE: /app/Actions/Users/CreateUserWithPost.php ---
namespace App\Actions\Users;

use App\Models\User;
use App\Models\Role;
use App\Enums\PostStatus;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Throwable;

/**
 * The "Action-Oriented" Developer.
 * This class has a single responsibility: create a user, assign a role, and create a post in a transaction.
 */
class CreateUserWithPost
{
    /**
     * @throws Throwable
     */
    public function execute(array $userData, array $postData, string $roleName = 'USER'): User
    {
        return DB::transaction(function () use ($userData, $postData, $roleName) {
            // 1. Create User (CRUD - Create)
            $user = User::create([
                'email' => $userData['email'],
                'password_hash' => Hash::make($userData['password']),
            ]);

            // 2. Assign Role (Many-to-Many)
            $role = Role::where('name', $roleName)->firstOrFail();
            $user->roles()->attach($role->id);

            // 3. Create Post (One-to-Many)
            $user->posts()->create([
                'title' => $postData['title'],
                'content' => $postData['content'],
                'status' => PostStatus::DRAFT,
            ]);
            
            // If any of the above fail, the transaction is automatically rolled back.

            return $user->load('roles', 'posts');
        });
    }
}

// --- FILE: /app/Http/Controllers/UserController.php ---
namespace App\Http\Controllers;

use App\Actions\Users\CreateUserWithPost;
use App\Models\User;
use Illuminate\Http\Request;

class UserController
{
    // CREATE operation using an Action class
    public function store(Request $request, CreateUserWithPost $createUserAction)
    {
        $user = $createUserAction->execute(
            $request->input('user'),
            $request->input('post'),
            'ADMIN'
        );
        return response()->json($user, 201);
    }

    // READ operation with query builder
    public function index(Request $request)
    {
        $users = User::query()
            ->when($request->query('is_active'), function ($q, $isActive) {
                return $q->where('is_active', filter_var($isActive, FILTER_VALIDATE_BOOLEAN));
            })
            ->with(['roles:id,name', 'posts' => function ($query) {
                $query->select('id', 'user_id', 'title', 'status')->latest()->limit(5);
            }])
            ->select('id', 'email', 'created_at')
            ->get();

        return response()->json($users);
    }

    // UPDATE operation
    public function update(Request $request, string $userId)
    {
        $user = User::findOrFail($userId);
        $user->update($request->only(['email', 'is_active']));
        return response()->json($user);
    }

    // DELETE operation
    public function destroy(string $userId)
    {
        $user = User::findOrFail($userId);
        // Deleting the user will cascade to posts due to the migration constraint.
        // The role_user pivot table records will also be deleted.
        $user->delete();
        return response()->noContent();
    }
}
?>