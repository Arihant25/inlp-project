<?php

namespace App\Http\Controllers\Variation1;

use Illuminate\Http\Request;
use Illuminate\Routing\Controller;
use Illuminate\Http\JsonResponse;
use App\Services\Variation1\PostService;

class PostController extends Controller
{
    protected $postService;

    public function __construct(PostService $postService)
    {
        $this->postService = $postService;
    }

    public function show(string $id): JsonResponse
    {
        $post = $this->postService->findPostById($id);
        return response()->json($post);
    }

    public function update(Request $request, string $id): JsonResponse
    {
        $updatedPost = $this->postService->updatePost($id, $request->only(['title', 'content']));
        return response()->json($updatedPost);
    }

    public function destroy(string $id): JsonResponse
    {
        $this->postService->deletePost($id);
        return response()->json(['message' => 'Post deleted.']);
    }
}

namespace App\Services\Variation1;

use App\Repositories\Variation1\PostRepositoryInterface;
use Illuminate\Support\Facades\Cache;
use stdClass;

class PostService
{
    private const CACHE_TTL = 3600; // 1 hour
    private const CACHE_TAG_POSTS = 'posts';

    protected $postRepository;

    public function __construct(PostRepositoryInterface $postRepository)
    {
        $this->postRepository = $postRepository;
    }

    private function getPostCacheKey(string $id): string
    {
        return "post:{$id}";
    }

    private function getUserPostsCacheKey(string $userId): string
    {
        return "user:{$userId}:posts";
    }

    /**
     * Implements Cache-Aside pattern.
     */
    public function findPostById(string $id): ?stdClass
    {
        $cacheKey = $this->getPostCacheKey($id);

        // 1. Attempt to get from cache
        $post = Cache::tags([self::CACHE_TAG_POSTS])->get($cacheKey);

        if ($post === null) {
            // 2. Cache miss: get from primary data source
            $post = $this->postRepository->find($id);

            if ($post) {
                // 3. Store in cache with tags and TTL
                Cache::tags([self::CACHE_TAG_POSTS, $this->getUserPostsCacheKey($post->user_id)])
                    ->put($cacheKey, $post, self::CACHE_TTL);
            }
        }

        return $post;
    }

    public function updatePost(string $id, array $data): ?stdClass
    {
        $post = $this->postRepository->update($id, $data);

        if ($post) {
            // Invalidation strategy: Flush relevant tags
            $this->invalidatePostCache($id, $post->user_id);

            // Re-cache the updated data
            $cacheKey = $this->getPostCacheKey($id);
            Cache::tags([self::CACHE_TAG_POSTS, $this->getUserPostsCacheKey($post->user_id)])
                ->put($cacheKey, $post, self::CACHE_TTL);
        }

        return $post;
    }

    public function deletePost(string $id): void
    {
        $post = $this->postRepository->find($id); // Need user_id for tag invalidation
        if ($post) {
            $this->postRepository->delete($id);
            // Invalidation strategy: Flush relevant tags
            $this->invalidatePostCache($id, $post->user_id);
        }
    }

    /**
     * Invalidation can be complex. Tagging helps manage related keys.
     * Here we invalidate the specific post and any collections it belongs to.
     */
    private function invalidatePostCache(string $postId, string $userId): void
    {
        // Delete specific item cache
        Cache::tags([self::CACHE_TAG_POSTS])->forget($this->getPostCacheKey($postId));
        
        // Invalidate user's post list cache (example of related data)
        Cache::tags([$this->getUserPostsCacheKey($userId)])->flush();
    }
}

namespace App\Repositories\Variation1;

use stdClass;
use Illuminate\Support\Str;

// Mock Enums for self-containment
if (!enum_exists('App\Enums\Variation1\UserRole')) {
    enum UserRole: string { case ADMIN = 'ADMIN'; case USER = 'USER'; }
}
if (!enum_exists('App\Enums\Variation1\PostStatus')) {
    enum PostStatus: string { case DRAFT = 'DRAFT'; case PUBLISHED = 'PUBLISHED'; }
}

interface PostRepositoryInterface
{
    public function find(string $id): ?stdClass;
    public function update(string $id, array $data): ?stdClass;
    public function delete(string $id): bool;
}

class MockPostRepository implements PostRepositoryInterface
{
    private static $db = [];

    public function __construct()
    {
        if (empty(self::$db)) {
            $userId = (string) Str::uuid();
            self::$db = [
                '1b2a4a5c-1a2b-3c4d-4e5f-6a7b8c9d0e1f' => (object)[
                    'id' => '1b2a4a5c-1a2b-3c4d-4e5f-6a7b8c9d0e1f',
                    'user_id' => $userId,
                    'title' => 'Initial Post',
                    'content' => 'This is the first post.',
                    'status' => PostStatus::PUBLISHED->value,
                ]
            ];
        }
    }

    public function find(string $id): ?stdClass
    {
        return self::$db[$id] ?? null;
    }

    public function update(string $id, array $data): ?stdClass
    {
        if (isset(self::$db[$id])) {
            self::$db[$id]->title = $data['title'] ?? self::$db[$id]->title;
            self::$db[$id]->content = $data['content'] ?? self::$db[$id]->content;
            return self::$db[$id];
        }
        return null;
    }

    public function delete(string $id): bool
    {
        if (isset(self::$db[$id])) {
            unset(self::$db[$id]);
            return true;
        }
        return false;
    }
}

// NOTE: To run this, you would need to configure a cache driver that supports tags, like 'redis'.
// In config/cache.php, set 'default' => 'redis'.
// In a service provider's boot() method, you would bind the repository:
// $this->app->bind(\App\Repositories\Variation1\PostRepositoryInterface::class, \App\Repositories\Variation1\MockPostRepository::class);