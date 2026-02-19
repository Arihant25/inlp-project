<?php

namespace App\Http\Controllers\Variation3;

use Illuminate\Http\Request;
use Illuminate\Routing\Controller;
use Illuminate\Http\JsonResponse;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Str;
use stdClass;

// Mock Enums for self-containment
if (!enum_exists('App\Enums\Variation3\UserRole')) {
    enum UserRole: string { case ADMIN = 'ADMIN'; case USER = 'USER'; }
}
if (!enum_exists('App\Enums\Variation3\PostStatus')) {
    enum PostStatus: string { case DRAFT = 'DRAFT'; case PUBLISHED = 'PUBLISHED'; }
}

// A simple static class to simulate a data source
class PostDataSource
{
    private static $data = [];

    public static function init() {
        if (empty(self::$data)) {
            $postId = '3c2a4a5c-1a2b-3c4d-4e5f-6a7b8c9d0e3f';
            self::$data[$postId] = (object)[
                'id' => $postId,
                'user_id' => (string) Str::uuid(),
                'title' => 'A Pragmatic Post',
                'content' => 'Content fetched from the data source.',
                'status' => PostStatus::PUBLISHED->value,
            ];
        }
    }

    public static function getPost(string $pid): ?stdClass
    {
        self::init();
        // Simulate a slow DB query
        sleep(1); 
        return self::$data[$pid] ?? null;
    }

    public static function savePost(string $pid, array $post_data): stdClass
    {
        self::init();
        $post = self::getPost($pid) ?? (object)['id' => $pid];
        foreach ($post_data as $key => $val) {
            $post->{$key} = $val;
        }
        self::$data[$pid] = $post;
        return $post;
    }

    public static function deletePost(string $pid): void
    {
        self::init();
        unset(self::$data[$pid]);
    }
}

class PostController extends Controller
{
    /**
     * This method uses the Cache-Aside pattern in its most direct form
     * using Laravel's `Cache::remember` helper.
     */
    public function show(string $post_id): JsonResponse
    {
        $cache_key = "post_v3_{$post_id}";
        $ttl_seconds = 60 * 60; // 1 hour

        // `remember` gets the item from the cache. If it doesn't exist,
        // it executes the Closure, puts the result in the cache, and returns it.
        $the_post = Cache::remember($cache_key, $ttl_seconds, function () use ($post_id) {
            // This closure is only executed on a cache miss.
            return PostDataSource::getPost($post_id);
        });

        if (!$the_post) {
            return response()->json(['error' => 'Not Found'], 404);
        }

        return response()->json($the_post);
    }

    /**
     * Update action with a direct cache invalidation strategy.
     */
    public function update(Request $req, string $post_id): JsonResponse
    {
        $post_data = $req->only(['title', 'content']);
        $updated_post = PostDataSource::savePost($post_id, $post_data);

        // Cache Invalidation: Explicitly delete the key.
        $cache_key = "post_v3_{$post_id}";
        Cache::forget($cache_key);

        // Optional: Re-warm the cache immediately
        // Cache::put($cache_key, $updated_post, 60 * 60);

        return response()->json($updated_post);
    }

    /**
     * Delete action with cache invalidation.
     */
    public function destroy(string $post_id): JsonResponse
    {
        PostDataSource::deletePost($post_id);

        // Cache Invalidation: Delete the key from cache.
        $cache_key = "post_v3_{$post_id}";
        Cache::forget($cache_key);

        return response()->json(null, 204);
    }
}