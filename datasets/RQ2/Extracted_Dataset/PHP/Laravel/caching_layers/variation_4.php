<?php

namespace App\Services\Variation4;

use Illuminate\Support\Facades\Cache as LaravelCache;
use Illuminate\Support\Str;
use stdClass;

// Mock Enums for self-containment
if (!enum_exists('App\Enums\Variation4\UserRole')) {
    enum UserRole: string { case ADMIN = 'ADMIN'; case USER = 'USER'; }
}
if (!enum_exists('App\Enums\Variation4\PostStatus')) {
    enum PostStatus: string { case DRAFT = 'DRAFT'; case PUBLISHED = 'PUBLISHED'; }
}

class DoublyLinkedNode
{
    public ?string $key;
    public $value;
    public ?DoublyLinkedNode $prev = null;
    public ?DoublyLinkedNode $next = null;

    public function __construct(?string $key, $value)
    {
        $this->key = $key;
        $this->value = $value;
    }
}

/**
 * A classic LRU (Least Recently Used) Cache implementation.
 * This serves as a fast, in-memory, per-request cache (L1 Cache).
 */
class LRUCache
{
    private int $capacity;
    private DoublyLinkedNode $head;
    private DoublyLinkedNode $tail;
    private array $map = [];

    public function __construct(int $capacity = 50)
    {
        $this->capacity = $capacity;
        $this->head = new DoublyLinkedNode(null, null);
        $this->tail = new DoublyLinkedNode(null, null);
        $this->head->next = $this->tail;
        $this->tail->prev = $this->head;
    }

    public function get(string $key)
    {
        if (!isset($this->map[$key])) {
            return null;
        }

        $node = $this->map[$key];
        $this->moveToHead($node);
        return $node->value;
    }

    public function set(string $key, $value): void
    {
        if (isset($this->map[$key])) {
            $node = $this->map[$key];
            $node->value = $value;
            $this->moveToHead($node);
        } else {
            $newNode = new DoublyLinkedNode($key, $value);
            $this->map[$key] = $newNode;
            $this->addNode($newNode);

            if (count($this->map) > $this->capacity) {
                $tail = $this->popTail();
                unset($this->map[$tail->key]);
            }
        }
    }

    public function delete(string $key): void
    {
        if (isset($this->map[$key])) {
            $node = $this->map[$key];
            $this->removeNode($node);
            unset($this->map[$key]);
        }
    }

    private function moveToHead(DoublyLinkedNode $node): void
    {
        $this->removeNode($node);
        $this->addNode($node);
    }

    private function addNode(DoublyLinkedNode $node): void
    {
        $node->prev = $this->head;
        $node->next = $this->head->next;
        $this->head->next->prev = $node;
        $this->head->next = $node;
    }

    private function removeNode(DoublyLinkedNode $node): void
    {
        $prev = $node->prev;
        $next = $node->next;
        $prev->next = $next;
        $next->prev = $prev;
    }

    private function popTail(): DoublyLinkedNode
    {
        $res = $this->tail->prev;
        $this->removeNode($res);
        return $res;
    }
}

/**
 * A manager that orchestrates a multi-layer cache.
 * L1: In-memory LRU cache (per-request).
 * L2: Persistent shared cache (e.g., Redis, Memcached via Laravel's Cache facade).
 * L3: Primary Database (mocked).
 */
class PostCacheManager
{
    private LRUCache $l1Cache; // In-memory, request-scoped
    private const L2_CACHE_TTL = 7200; // 2 hours for persistent cache

    public function __construct()
    {
        // In a real app, this would be a singleton for the request lifecycle.
        $this->l1Cache = new LRUCache();
    }

    private function getL2CacheKey(string $id): string
    {
        return "post:l2:{$id}";
    }

    public function findPost(string $id): ?stdClass
    {
        // 1. Check L1 Cache (In-memory LRU)
        $post = $this->l1Cache->get($id);
        if ($post !== null) {
            return $post;
        }

        // 2. Check L2 Cache (Persistent, e.g., Redis)
        $l2Key = $this->getL2CacheKey($id);
        $post = LaravelCache::get($l2Key);
        if ($post !== null) {
            $this->l1Cache->set($id, $post); // Warm L1 cache
            return $post;
        }

        // 3. Cache Miss: Get from Database (Primary Source)
        $post = $this->fetchFromDatabase($id);
        if ($post) {
            // Populate both caches
            LaravelCache::put($l2Key, $post, self::L2_CACHE_TTL);
            $this->l1Cache->set($id, $post);
        }

        return $post;
    }

    public function updatePost(string $id, array $data): ?stdClass
    {
        // Update the database first
        // $post = Post::find($id)->update($data);
        $post = $this->fetchFromDatabase($id);
        if ($post) {
            $post->title = $data['title'];
            $post->content = $data['content'];
        }

        // Invalidation: Delete from both caches
        $this->invalidate($id);

        return $post;
    }

    public function invalidate(string $id): void
    {
        // Delete from L1 and L2
        $this->l1Cache->delete($id);
        LaravelCache::forget($this->getL2CacheKey($id));
    }

    private function fetchFromDatabase(string $id): ?stdClass
    {
        // Mocking a DB call
        if ($id === '4d2a4a5c-1a2b-3c4d-4e5f-6a7b8c9d0e4f') {
            return (object)[
                'id' => $id,
                'user_id' => (string) Str::uuid(),
                'title' => 'Multi-Layer Cached Post',
                'content' => 'This content came from the "database".',
                'status' => PostStatus::PUBLISHED->value,
            ];
        }
        return null;
    }
}

namespace App\Http\Controllers\Variation4;

use Illuminate\Http\Request;
use Illuminate\Routing\Controller;
use Illuminate\Http\JsonResponse;
use App\Services\Variation4\PostCacheManager;

class PostController extends Controller
{
    private $postManager;

    public function __construct(PostCacheManager $postManager)
    {
        $this->postManager = $postManager;
    }

    public function show(string $id): JsonResponse
    {
        $post = $this->postManager->findPost($id);
        return response()->json($post);
    }

    public function update(Request $request, string $id): JsonResponse
    {
        $post = $this->postManager->updatePost($id, $request->only(['title', 'content']));
        return response()->json($post);
    }
}