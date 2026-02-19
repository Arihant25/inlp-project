<?php

// --- FILE: /database/migrations/2023_10_27_000001_create_users_table.php ---
namespace Database\Migrations;

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('users', function (Blueprint $table) {
            $table->uuid('id')->primary();
            $table->string('email')->unique();
            $table->string('password_hash');
            $table->boolean('is_active')->default(true);
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('users');
    }
};

// --- FILE: /database/migrations/2023_10_27_000002_create_posts_table.php ---
namespace Database\Migrations;

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('posts', function (Blueprint $table) {
            $table->uuid('id')->primary();
            $table->foreignUuid('user_id')->constrained('users')->onDelete('cascade');
            $table->string('title');
            $table->text('content');
            $table->string('status')->default('DRAFT'); // DRAFT, PUBLISHED
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('posts');
    }
};

// --- FILE: /database/migrations/2023_10_27_000003_create_roles_table.php ---
namespace Database\Migrations;

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('roles', function (Blueprint $table) {
            $table->id();
            $table->string('name')->unique(); // e.g., ADMIN, USER
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('roles');
    }
};

// --- FILE: /database/migrations/2023_10_27_000004_create_role_user_table.php ---
namespace Database\Migrations;

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('role_user', function (Blueprint $table) {
            $table->foreignUuid('user_id')->constrained('users')->onDelete('cascade');
            $table->foreignId('role_id')->constrained('roles')->onDelete('cascade');
            $table->primary(['user_id', 'role_id']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('role_user');
    }
};


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
}

// --- FILE: /app/Models/Post.php ---
namespace App\Models;

use App\Enums\PostStatus;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class Post extends Model
{
    use HasFactory, HasUuids;

    protected $fillable = ['user_id', 'title', 'content', 'status'];
    protected $casts = ['status' => PostStatus::class];

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
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

// --- FILE: /app/Services/UserService.php ---
namespace App\Services;

use App\Models\User;
use App\Models\Role;
use App\Models\Post;
use App\Enums\PostStatus;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Illuminate\Database\Eloquent\Collection;
use Throwable;

/**
 * The "By-the-Book" Developer using a Service Layer.
 * Logic is encapsulated here, keeping the controller thin.
 */
class UserService
{
    // CREATE operation
    public function createUser(array $userData): User
    {
        return User::create([
            'email' => $userData['email'],
            'password_hash' => Hash::make($userData['password']),
            'is_active' => $userData['is_active'] ?? true,
        ]);
    }

    // READ operation with filtering
    public function findActiveUsersWithPublishedPosts(string $emailFilter = null): Collection
    {
        return User::query()
            ->where('is_active', true)
            ->when($emailFilter, function ($query, $email) {
                $query->where('email', 'like', '%' . $email . '%');
            })
            ->whereHas('posts', function ($query) {
                $query->where('status', PostStatus::PUBLISHED);
            })
            ->with('roles') // Eager load roles
            ->get();
    }

    // UPDATE operation
    public function updateUserEmail(string $userId, string $newEmail): bool
    {
        $user = User::findOrFail($userId);
        return $user->update(['email' => $newEmail]);
    }

    // DELETE operation
    public function deleteUser(string $userId): bool
    {
        $user = User::findOrFail($userId);
        return $user->delete();
    }

    // Transactional operation with many-to-many relationship
    /**
     * @throws Throwable
     */
    public function assignAdminRoleAndCreateFirstPost(string $userId, array $postData): Post
    {
        return DB::transaction(function () use ($userId, $postData) {
            $user = User::findOrFail($userId);
            $adminRole = Role::where('name', 'ADMIN')->firstOrFail();

            // Many-to-many attach
            $user->roles()->syncWithoutDetaching([$adminRole->id]);

            // One-to-many create
            $post = $user->posts()->create([
                'title' => $postData['title'],
                'content' => $postData['content'],
                'status' => PostStatus::DRAFT,
            ]);

            // Example of a condition that could cause a rollback
            if (empty($postData['title'])) {
                 // This exception will automatically trigger a rollback
                throw new \InvalidArgumentException('Post title cannot be empty.');
            }

            return $post;
        });
    }
}
?>