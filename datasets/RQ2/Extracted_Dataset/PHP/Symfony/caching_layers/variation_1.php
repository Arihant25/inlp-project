<?php

namespace App\Variation1;

use Psr\Cache\CacheItemInterface;
use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\Cache\Adapter\ArrayAdapter;
use Symfony\Component\Cache\CacheItem;
use Symfony\Component\Cache\TagAwareCacheInterface;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\Routing\Annotation\Route;
use Symfony\Component\Uid\Uuid;

// --- Domain Model ---

enum UserRole: string
{
    case ADMIN = 'admin';
    case USER = 'user';
}

enum PostStatus: string
{
    case DRAFT = 'draft';
    case PUBLISHED = 'published';
}

class User
{
    public function __construct(
        public readonly string $id,
        public string $email,
        public string $password_hash,
        public UserRole $role,
        public bool $is_active,
        public readonly \DateTimeImmutable $created_at
    ) {}
}

class Post
{
    public function __construct(
        public readonly string $id,
        public string $user_id,
        public string $title,
        public string $content,
        public PostStatus $status
    ) {}
}

// --- Mock Infrastructure ---

/**
 * A mock repository to simulate database interactions.
 */
class PostRepository
{
    private array $posts = [];

    public function __construct()
    {
        $userId = Uuid::v4()->toRfc4122();
        $postId = Uuid::v4()->toRfc4122();
        $this->posts[$postId] = new Post($postId, $userId, 'First Post', 'Content of the first post.', PostStatus::PUBLISHED);
    }

    public function find(string $id): ?Post
    {
        // Simulate database latency
        usleep(150 * 1000); // 150ms
        return $this->posts[$id] ?? null;
    }

    public function save(Post $post): void
    {
        // Simulate database write
        usleep(50 * 1000); // 50ms
        $this->posts[$post->id] = $post;
    }

    public function delete(string $id): void
    {
        unset($this->posts[$id]);
    }
}

// --- Caching Implementation: "By-the-Book" Service-Oriented Developer ---

/**
 * This service encapsulates all business logic related to Posts.
 * It uses a TagAwareCache for fine-grained cache invalidation. This is a robust,
 * production-grade approach that follows Symfony best practices closely.
 */
class PostService
{
    // Cache constants for consistency
    private const CACHE_TAG_POST = 'posts';
    private const CACHE_TAG_POST_ID_PREFIX = 'post_id_';

    public function __construct(
        private readonly PostRepository $postRepository,
        private readonly TagAwareCacheInterface $cache
    ) {}

    /**
     * Retrieves a post, using the cache-aside pattern.
     * Each post is tagged globally and with its own ID for precise invalidation.
     */
    public function getPost(string $id): ?Post
    {
        $cacheKey = 'post_' . $id;

        return $this->cache->get($cacheKey, function (CacheItemInterface $item) use ($id) {
            // This callback is only executed on a cache miss.
            echo "--- CACHE MISS for post {$id} ---\n";

            $item->expiresAfter(3600); // Expire after 1 hour

            // Tagging allows invalidating groups of related items.
            $item->tag([
                self::CACHE_TAG_POST,
                self::CACHE_TAG_POST_ID_PREFIX . $id
            ]);

            return $this->postRepository->find($id);
        });
    }

    /**
     * Updates a post and invalidates the corresponding cache item.
     * Using tags ensures that if this post were part of other cached collections
     * (e.g., "latest_posts"), those could also be invalidated simultaneously.
     */
    public function updatePostTitle(string $id, string $newTitle): ?Post
    {
        $post = $this->postRepository->find($id);
        if (!$post) {
            return null;
        }

        $post->title = $newTitle;
        $this->postRepository->save($post);

        // Invalidate all cache items tagged with this post's ID.
        $this->cache->invalidateTags([self::CACHE_TAG_POST_ID_PREFIX . $id]);

        return $post;
    }

    /**
     * Deletes a post and invalidates its cache.
     */
    public function deletePost(string $id): void
    {
        $this->postRepository->delete($id);
        $this->cache->invalidateTags([self::CACHE_TAG_POST_ID_PREFIX . $id]);
    }
}

// --- Example Usage in a Controller ---

class PostController extends AbstractController
{
    public function __construct(private readonly PostService $postService) {}

    #[Route("/posts/{id}", methods: ["GET"])]
    public function getPostAction(string $id): JsonResponse
    {
        $startTime = microtime(true);
        $post = $this->postService->getPost($id);
        $duration = microtime(true) - $startTime;

        if (!$post) {
            return new JsonResponse(['error' => 'Post not found'], 404);
        }

        return new JsonResponse([
            'post' => ['id' => $post->id, 'title' => $post->title],
            'retrieval_time_ms' => round($duration * 1000, 2),
            'message' => 'First request will be slow (cache miss), subsequent ones will be fast (cache hit).'
        ]);
    }
}

// --- Simulation Runner ---
/*
$repository = new PostRepository();
$cache = new TagAwareCache(new ArrayAdapter());
$service = new PostService($repository, $cache);
$controller = new PostController($service);

$postId = array_key_first($repository->posts);

echo "1. First request (Cache Miss):\n";
$controller->getPostAction($postId)->sendContent();
echo "\n\n";

echo "2. Second request (Cache Hit):\n";
$controller->getPostAction($postId)->sendContent();
echo "\n\n";

echo "3. Updating post title...\n";
$service->updatePostTitle($postId, 'Updated Post Title');
echo "\n";

echo "4. Request after update (Cache Miss):\n";
$controller->getPostAction($postId)->sendContent();
echo "\n\n";

echo "5. Request after miss (Cache Hit):\n";
$controller->getPostAction($postId)->sendContent();
echo "\n";
*/
?>