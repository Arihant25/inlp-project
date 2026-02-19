<?php

namespace App\Variation3;

use Psr\Cache\CacheItemInterface;
use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\Cache\Adapter\ArrayAdapter;
use Symfony\Component\Cache\CacheInterface;
use Symfony\Component\Cache\TagAwareCacheInterface;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\Routing\Annotation\Route;
use Symfony\Component\Uid\Uuid;

// --- Domain Model ---

enum UserRole: string { case ADMIN = 'admin'; case USER = 'user'; }
enum PostStatus: string { case DRAFT = 'draft'; case PUBLISHED = 'published'; }

class User {
    public function __construct(public string $id, public string $email) {}
}
class Post {
    public function __construct(public string $id, public string $user_id, public string $title, public string $content, public PostStatus $status) {}
}

// --- Caching Implementation: The "Decorator Pattern" Enthusiast ---

/*
This approach uses the Decorator pattern to add caching capabilities to a repository
transparently. The application's services are unaware of the caching layer.

This is configured in Symfony's service container like this:

# config/services.yaml
services:
    App\Variation3\DatabasePostRepository: ~

    App\Variation3\PostRepositoryInterface: '@App\Variation3\DatabasePostRepository'

    App\Variation3\CachedPostRepository:
        decorates: App\Variation3\PostRepositoryInterface
        arguments: ['@.inner', '@Symfony\Contracts\Cache\TagAwareCacheInterface']
*/

/**
 * The contract for any Post repository.
 */
interface PostRepositoryInterface
{
    public function getById(string $id): ?Post;
    public function save(Post $post): void;
}

/**
 * The "real" repository that interacts with the primary data source (e.g., Doctrine).
 */
class DatabasePostRepository implements PostRepositoryInterface
{
    private array $storage = [];

    public function __construct()
    {
        $id = Uuid::v4()->toRfc4122();
        $this->storage[$id] = new Post($id, Uuid::v4()->toRfc4122(), 'Original Title', 'Content', PostStatus::PUBLISHED);
    }

    public function getById(string $id): ?Post
    {
        echo "--- DATABASE READ for post {$id} ---\n";
        usleep(120 * 1000); // Simulate DB latency
        return $this->storage[$id] ?? null;
    }

    public function save(Post $post): void
    {
        echo "--- DATABASE WRITE for post {$post->id} ---\n";
        usleep(80 * 1000);
        $this->storage[$post->id] = $post;
    }
}

/**
 * The decorator that adds a caching layer around the real repository.
 * It implements the same interface, so it can be swapped in transparently.
 */
class CachedPostRepository implements PostRepositoryInterface
{
    private const POST_TAG = 'post';

    public function __construct(
        private readonly PostRepositoryInterface $innerRepository,
        private readonly TagAwareCacheInterface $cache
    ) {}

    public function getById(string $id): ?Post
    {
        $key = 'post_id_' . $id;
        return $this->cache->get($key, function (CacheItemInterface $item) use ($id) {
            $item->expiresAfter(3600);
            $item->tag([self::POST_TAG, 'post-' . $id]);
            // On cache miss, it calls the decorated (real) repository
            return $this->innerRepository->getById($id);
        });
    }

    public function save(Post $post): void
    {
        // First, call the decorated repository to persist the change
        $this->innerRepository->save($post);

        // Then, invalidate the cache for this specific item
        $this->cache->invalidateTags(['post-' . $post->id]);
    }
}

/**
 * The service layer is completely unaware of caching. It just uses the repository interface.
 * Due to DI decoration, it will receive the CachedPostRepository instance.
 */
class PostQueryService
{
    public function __construct(private readonly PostRepositoryInterface $postRepository) {}

    public function findPost(string $id): ?Post
    {
        return $this->postRepository->getById($id);
    }
    
    public function updatePost(string $id, string $newTitle): ?Post
    {
        $post = $this->postRepository->getById($id);
        if ($post) {
            $post->title = $newTitle;
            $this->postRepository->save($post);
        }
        return $post;
    }
}

// --- Example Usage in a Controller ---

class PostApiController extends AbstractController
{
    public function __construct(private readonly PostQueryService $postQueryService) {}

    #[Route("/api/posts/{id}", methods: ["GET"])]
    public function getPost(string $id): JsonResponse
    {
        $post = $this->postQueryService->findPost($id);
        return new JsonResponse($post ? ['id' => $post->id, 'title' => $post->title] : null);
    }
}

// --- Simulation Runner ---
/*
// Manual DI setup to simulate Symfony's container
$dbRepo = new DatabasePostRepository();
$cache = new \Symfony\Component\Cache\Adapter\TagAwareAdapter(new ArrayAdapter());
$cachedRepo = new CachedPostRepository($dbRepo, $cache); // Decoration
$service = new PostQueryService($cachedRepo); // Service gets the decorator
$controller = new PostApiController($service);

$postId = array_key_first($dbRepo->storage);

echo "1. First call (cache miss, DB read):\n";
$controller->getPost($postId);
echo "\n2. Second call (cache hit):\n";
$controller->getPost($postId);
echo "\n\n";

echo "3. Updating post (DB write, cache invalidation):\n";
$service->updatePost($postId, 'A Decorated Title');
echo "\n";

echo "4. Call after update (cache miss, DB read):\n";
$controller->getPost($postId);
echo "\n5. Final call (cache hit):\n";
$controller->getPost($postId);
*/
?>