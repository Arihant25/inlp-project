<?php

namespace App\Models\Variation2;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Support\Facades\Cache;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use App\Observers\Variation2\PostObserver;
use Illuminate\Database\Eloquent\Attributes\ObservedBy;
use stdClass;

// Mock Enums for self-containment
if (!enum_exists('App\Enums\Variation2\UserRole')) {
    enum UserRole: string { case ADMIN = 'ADMIN'; case USER = 'USER'; }
}
if (!enum_exists('App\Enums\Variation2\PostStatus')) {
    enum PostStatus: string { case DRAFT = 'DRAFT'; case PUBLISHED = 'PUBLISHED'; }
}

trait Cachable
{
    public static function getCacheKey(string $id): string
    {
        return strtolower(class_basename(self::class)) . ":{$id}";
    }

    /**
     * Implements Cache-Aside pattern using Laravel's remember function.
     */
    public static function findCached(string $id): ?self
    {
        return Cache::remember(self::getCacheKey($id), 3600, function () use ($id) {
            // In a real app, this would be: return self::find($id);
            // Mocking DB call for this example.
            if ($id === '2b2a4a5c-1a2b-3c4d-4e5f-6a7b8c9d0e2f') {
                $post = new Post();
                $post->id = $id;
                $post->user_id = 'a1b2c3d4-e5f6-a7b8-c9d0-e1f2a3b4c5d6';
                $post->title = 'Cached Post via Trait';
                $post->content = 'Content from "database".';
                $post->status = PostStatus::PUBLISHED;
                return $post;
            }
            return null;
        });
    }

    public static function forgetCache(string $id): void
    {
        Cache::forget(self::getCacheKey($id));
    }
}

class User extends Model
{
    use HasUuids, Cachable;
    public $incrementing = false;
    protected $keyType = 'string';
    protected $casts = ['role' => UserRole::class];
}

#[ObservedBy([PostObserver::class])]
class Post extends Model
{
    use HasUuids, Cachable;
    public $incrementing = false;
    protected $keyType = 'string';
    protected $fillable = ['title', 'content', 'status', 'user_id'];
    protected $casts = ['status' => PostStatus::class];
}

namespace App\Observers\Variation2;

use App\Models\Variation2\Post;

class PostObserver
{
    /**
     * Handle the Post "saved" event (covers created and updated).
     * This is the cache invalidation strategy.
     */
    public function saved(Post $post): void
    {
        // Invalidate the specific post cache
        Post::forgetCache($post->id);
    }

    /**
     * Handle the Post "deleted" event.
     */
    public function deleted(Post $post): void
    {
        Post::forgetCache($post->id);
    }
}

namespace App\Http\Controllers\Variation2;

use Illuminate\Http\Request;
use Illuminate\Routing\Controller;
use Illuminate\Http\JsonResponse;
use App\Models\Variation2\Post;
use App\Enums\Variation2\PostStatus;
use Illuminate\Support\Str;

class PostController extends Controller
{
    public function show(string $id): JsonResponse
    {
        // The cache-aside logic is abstracted away into the model method
        $post = Post::findCached($id);
        return response()->json($post);
    }

    public function update(Request $request, string $id): JsonResponse
    {
        // In a real app, you'd find the post first.
        // $post = Post::findOrFail($id);
        // $post->update($request->all());
        
        // For this self-contained example, we'll simulate the update
        // and manually trigger the observer's logic.
        $mockPost = new Post();
        $mockPost->id = $id;
        $mockPost->title = $request->input('title');
        
        // The observer will automatically call Post::forgetCache($id)
        (new \App\Observers\Variation2\PostObserver())->saved($mockPost);

        // We can then re-cache it if needed, or let it be lazy-loaded on next request
        $reloadedPost = Post::findCached($id); // This will miss, fetch, and re-cache

        return response()->json($reloadedPost);
    }

    public function destroy(string $id): JsonResponse
    {
        // Simulate deletion
        $mockPost = new Post();
        $mockPost->id = $id;

        // The observer automatically handles cache invalidation on deletion.
        (new \App\Observers\Variation2\PostObserver())->deleted($mockPost);

        return response()->json(['message' => 'Post cache invalidated.']);
    }
}

// NOTE: To run this, you would typically register the observer in App\Providers\EventServiceProvider:
/*
protected $listen = [
    // ...
];

public function boot(): void
{
    Post::observe(PostObserver::class);
}
*/
// The #[ObservedBy] attribute on the model (PHP 8.3+) achieves the same.
// This example uses the 'array' cache driver, which is fine for this pattern.