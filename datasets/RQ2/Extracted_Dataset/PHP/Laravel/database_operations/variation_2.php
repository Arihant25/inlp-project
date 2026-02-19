<?php

// --- FILE: /database/migrations/... (Identical to Variation 1) ---

// --- FILE: /app/Enums/PostStatus.php ---
namespace App\Enums;

enum PostStatus: string
{
    case DRAFT = 'DRAFT';
    case PUBLISHED = 'PUBLISHED';
}

// --- FILE: /app/Models/User.php ---
namespace App\Models;

use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsToMany;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Support\Facades\Hash;

class User extends Model
{
    use HasFactory, HasUuids;

    protected $fillable = ['email', 'password_hash', 'is_active'];
    protected $casts = ['is_active' => 'boolean'];

    public function posts(): HasMany
    {
        return $this->hasMany(Post::class);
    }

    public function roles(): BelongsToMany
    {
        return $this->belongsToMany(Role::class);
    }

    // --- Business logic in the model ---

    public static function createNew(string $email, string $password, bool $isActive = true): self
    {
        return static::create([
            'email' => $email,
            'password_hash' => Hash::make($password),
            'is_active' => $isActive,
        ]);
    }

    public function assignRole(string $roleName): void
    {
        $role = Role::where('name', $roleName)->firstOrFail();
        $this->roles()->syncWithoutDetaching([$role->id]);
    }

    public function hasRole(string $roleName): bool
    {
        return $this->roles()->where('name', $roleName)->exists();
    }
}

// --- FILE: /app/Models/Post.php ---
namespace App\Models;

use App\Enums\PostStatus;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Support\Facades\DB;
use Throwable;

class Post extends Model
{
    use HasFactory, HasUuids;

    protected $fillable = ['user_id', 'title', 'content', 'status'];
    protected $casts = ['status' => PostStatus::class];

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    // --- Query Scopes for filtering ---
    public function scopePublished(Builder $query): void
    {
        $query->where('status', PostStatus::PUBLISHED);
    }

    public function scopeDraft(Builder $query): void
    {
        $query->where('status', PostStatus::DRAFT);
    }

    public function scopeForAuthor(Builder $query, User $user): void
    {
        $query->where('user_id', $user->id);
    }

    // --- Transactional logic in the model ---
    /**
     * @throws Throwable
     */
    public static function createForNewAdmin(User $user, array $postData): self
    {
        return DB::transaction(function () use ($user, $postData) {
            $user->assignRole('ADMIN');

            if (!$user->hasRole('ADMIN')) {
                throw new \Exception("Failed to assign ADMIN role.");
            }

            return $user->posts()->create([
                'title' => $postData['title'],
                'content' => $postData['content'],
                'status' => PostStatus::DRAFT,
            ]);
        });
    }
}

// --- FILE: /app/Models/Role.php ---
namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsToMany;

class Role extends Model
{
    use HasFactory;
    protected $fillable = ['name'];

    public function users(): BelongsToMany
    {
        return $this->belongsToMany(User::class);
    }
}

// --- FILE: /app/Http/Controllers/PostController.php ---
namespace App\Http\Controllers;

use App\Models\Post;
use App\Models\User;
use Illuminate\Http\Request;

/**
 * The "Fat Model, Skinny Controller" Developer.
 * Controller is minimal, calling logic directly on Eloquent models.
 */
class PostController
{
    // CREATE operation (transactional)
    public function storeForNewAdmin(Request $request)
    {
        $user = User::findOrFail($request->input('user_id'));
        $postData = $request->only(['title', 'content']);

        $post = Post::createForNewAdmin($user, $postData);

        return response()->json($post, 201);
    }

    // READ operation with filters (using scopes)
    public function index(Request $request)
    {
        $user = User::findOrFail($request->query('user_id'));

        $posts = Post::query()
            ->forAuthor($user)
            ->published()
            ->latest()
            ->get();

        return response()->json($posts);
    }

    // UPDATE operation
    public function update(Request $request, string $postId)
    {
        $post = Post::findOrFail($postId);
        $post->update($request->only(['title', 'content']));
        return response()->json($post);
    }

    // DELETE operation
    public function destroy(string $postId)
    {
        Post::findOrFail($postId)->delete();
        return response()->noContent();
    }
}
?>