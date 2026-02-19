<?php

namespace App\Variation2;

use Psr\Cache\CacheItemInterface;
use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\Cache\Adapter\ArrayAdapter;
use Symfony\Component\Cache\CacheInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
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
        public string $id,
        public string $email,
        public string $password_hash,
        public UserRole $role,
        public bool $is_active,
        public \DateTimeImmutable $created_at
    ) {}
}

class Post
{
    public function __construct(
        public string $id,
        public string $user_id,
        public string $title,
        public string $content,
        public PostStatus $status
    ) {}
}

// --- Mock Infrastructure ---

class PostDataStore
{
    private array $db = [];

    public function __construct()
    {
        for ($i = 0; $i < 5; $i++) {
            $id = Uuid::v4()->toRfc4122();
            $this->db[$id] = new Post($id, Uuid::v4()->toRfc4122(), "Post Title {$i}", "Content {$i}", PostStatus::PUBLISHED);
        }
    }

    public function findById(string $id): ?Post
    {
        usleep(100 * 1000); // Simulate I/O
        return $this->db[$id] ?? null;
    }

    public function findAll(): array
    {
        usleep(300 * 1000); // Simulate I/O
        return array_filter($this->db, fn($post) => $post->status === PostStatus::PUBLISHED);
    }

    public function persist(Post $post): void
    {
        $this->db[$post->id] = $post;
    }
}

// --- Caching Implementation: The "Pragmatic/Functional" Developer ---

/**
 * This manager class handles post data retrieval with a focus on concise,
 * effective caching. It uses the callback-based `get()` method extensively,
 * which is a very common and readable way to implement the cache-aside pattern.
 * It uses a dedicated cache pool for posts.
 */
class PostManager
{
    public function __construct(
        // In a real app, this would be configured in cache.yaml and wired by name.
        #[Autowire(service: 'cache.app')]
        private readonly CacheInterface $cache,
        private readonly PostDataStore $store
    ) {}

    /**
     * Finds a single post by its ID.
     * The logic is very compact due to the functional style of the `get` method.
     */
    public function find(string $id): ?Post
    {
        $key = 'post.' . $id;
        return $this->cache->get($key, function (CacheItemInterface $item) use ($id) {
            echo "--- DB HIT for single post: {$id} ---\n";
            $item->expiresAfter(\DateInterval::createFromDateString('15 minutes'));
            return $this->store->findById($id);
        });
    }

    /**
     * Finds all published posts, caching the entire collection.
     * This is a common use case for pages like a blog index.
     */
    public function findAllPublished(): array
    {
        $key = 'posts.all_published';
        return $this->cache->get($key, function (CacheItemInterface $item) {
            echo "--- DB HIT for all posts ---\n";
            $item->expiresAfter(\DateInterval::createFromDateString('5 minutes'));
            return $this->store->findAll();
        });
    }

    /**
     * Saves a post and invalidates relevant cache keys.
     * This approach requires knowing which keys to invalidate.
     */
    public function save(Post $post): void
    {
        $this->store->persist($post);

        // Invalidate both the single item and the collection cache
        $this->cache->delete('post.' . $post->id);
        $this->cache->delete('posts.all_published');
    }
}

// --- Example Usage in a Controller ---

class BlogController extends AbstractController
{
    public function __construct(private readonly PostManager $postManager) {}

    #[Route("/blog/posts/{id}", methods: ["GET"])]
    public function show(string $id): JsonResponse
    {
        $post = $this->postManager->find($id);
        return new JsonResponse($post ? ['id' => $post->id, 'title' => $post->title] : null);
    }

    #[Route("/blog/posts", methods: ["GET"])]
    public function list(): JsonResponse
    {
        $posts = $this->postManager->findAllPublished();
        return new JsonResponse(['count' => count($posts)]);
    }
}

// --- Simulation Runner ---
/*
$store = new PostDataStore();
$cache = new ArrayAdapter();
$manager = new PostManager($cache, $store);
$controller = new BlogController($manager);

$allPosts = $store->findAll();
$firstPostId = reset($allPosts)->id;

echo "1. Get single post (miss):\n";
$controller->show($firstPostId);
echo "\n2. Get single post (hit):\n";
$controller->show($firstPostId);
echo "\n\n";

echo "3. Get all posts (miss):\n";
$controller->list();
echo "\n4. Get all posts (hit):\n";
$controller->list();
echo "\n\n";

echo "5. Saving a post to invalidate caches...\n";
$postToUpdate = $store->findById($firstPostId);
$postToUpdate->title = 'A New Title';
$manager->save($postToUpdate);
echo "\n";

echo "6. Get single post after save (miss):\n";
$controller->show($firstPostId);
echo "\n7. Get all posts after save (miss):\n";
$controller->list();
*/
?>