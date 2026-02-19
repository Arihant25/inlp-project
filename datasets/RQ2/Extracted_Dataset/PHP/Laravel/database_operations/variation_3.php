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


// --- FILE: /app/Repositories/Contracts/UserRepositoryInterface.php ---
namespace App\Repositories\Contracts;

use App\Models\User;
use Illuminate\Support\Collection;

interface UserRepositoryInterface
{
    public function create(array $attributes): User;
    public function findById(string $id): ?User;
    public function update(string $id, array $attributes): bool;
    public function delete(string $id): bool;
    public function findWithFilters(array $filters): Collection;
    public function assignRoleAndCreatePost(string $userId, string $roleName, array $postData): bool;
}

// --- FILE: /app/Repositories/Eloquent/EloquentUserRepository.php ---
namespace App\Repositories\Eloquent;

use App\Repositories\Contracts\UserRepositoryInterface;
use App\Models\User;
use App\Models\Role;
use App\Enums\PostStatus;
use Illuminate\Support\Collection;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Throwable;

/**
 * The "Repository Pattern" Developer.
 * This class abstracts all data layer logic away from the rest of the application.
 */
class EloquentUserRepository implements UserRepositoryInterface
{
    public function create(array $attributes): User
    {
        return User::create([
            'email' => $attributes['email'],
            'password_hash' => Hash::make($attributes['password']),
            'is_active' => $attributes['is_active'] ?? true,
        ]);
    }

    public function findById(string $id): ?User
    {
        return User::find($id);
    }

    public function update(string $id, array $attributes): bool
    {
        $user = $this->findById($id);
        if (!$user) {
            return false;
        }
        return $user->update($attributes);
    }

    public function delete(string $id): bool
    {
        $user = $this->findById($id);
        if (!$user) {
            return false;
        }
        return $user->delete();
    }

    public function findWithFilters(array $filters): Collection
    {
        $query = User::query();

        if (isset($filters['is_active'])) {
            $query->where('is_active', $filters['is_active']);
        }

        if (isset($filters['role'])) {
            $query->whereHas('roles', function ($q) use ($filters) {
                $q->where('name', $filters['role']);
            });
        }

        return $query->with('posts', 'roles')->get();
    }

    /**
     * @throws Throwable
     */
    public function assignRoleAndCreatePost(string $userId, string $roleName, array $postData): bool
    {
        return DB::transaction(function () use ($userId, $roleName, $postData) {
            $user = User::findOrFail($userId);
            $role = Role::where('name', $roleName)->firstOrFail();

            $user->roles()->syncWithoutDetaching([$role->id]);

            $user->posts()->create([
                'title' => $postData['title'],
                'content' => $postData['content'],
                'status' => PostStatus::DRAFT,
            ]);

            return true;
        });
    }
}

// --- FILE: /app/Http/Controllers/UserController.php ---
namespace App\Http\Controllers;

use App\Repositories\Contracts\UserRepositoryInterface;
use Illuminate\Http\Request;

class UserController
{
    private UserRepositoryInterface $userRepo;

    public function __construct(UserRepositoryInterface $userRepository)
    {
        $this->userRepo = $userRepository;
    }

    public function index(Request $request)
    {
        // Query building with filters via the repository
        $filters = [
            'is_active' => $request->query('active', true),
            'role' => $request->query('role'),
        ];
        $users = $this->userRepo->findWithFilters(array_filter($filters));
        return response()->json($users);
    }

    public function store(Request $request)
    {
        $user = $this->userRepo->create($request->all());
        return response()->json($user, 201);
    }
    
    public function promoteAndPost(Request $request, string $userId)
    {
        // Transactional operation
        $success = $this->userRepo->assignRoleAndCreatePost(
            $userId,
            'ADMIN',
            $request->only(['title', 'content'])
        );
        return response()->json(['success' => $success]);
    }
}
?>