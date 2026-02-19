<?php

namespace App\Variation4;

use Psr\Cache\CacheItemInterface;
use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\Cache\Adapter\ArrayAdapter;
use Symfony\Component\Cache\CacheInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\Routing\Annotation\Route;
use Symfony\Component\Uid\Uuid;

// --- Domain Model ---

enum UserRole: string { case ADMIN = 'admin'; case USER = 'user'; }
enum PostStatus: string { case DRAFT = 'draft'; case PUBLISHED = 'published'; }

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

class UserRepository
{
    private array $users = [];

    public function __construct()
    {
        for ($i = 0; $i < 10; $i++) {
            $id = Uuid::v4()->toRfc4122();
            $this->users[$id] = new User($id, "user{$i}@example.com", 'hash', UserRole::USER, true, new \DateTimeImmutable());
        }
    }

    public function findOneById(string $id): ?User
    {
        echo "--- Fetching user {$id} from primary datastore ---\n";
        usleep(50 * 1000); // Simulate latency
        return $this->users[$id] ?? null;
    }

    public function save(User $user): void
    {
        $this->users[$user->id] = $user;
    }
}

// --- Caching Implementation: The "Advanced/LRU" Specialist ---

/*
This implementation demonstrates a specialized cache pool configured for LRU-like behavior.
The `ArrayAdapter` is configured with a max item limit. When the limit is reached,
the oldest items are evicted. This is useful for caching frequently accessed but
non-critical data, like user profiles.

This would be configured in a YAML file:

# config/packages/cache.yaml
framework:
    cache:
        pools:
            cache.user_profiles:
                adapter: cache.adapter.array
                default_lifetime: 3600
                # ArrayAdapter specific options
                provider: 'app.cache.provider.lru_array'

services:
    app.cache.provider.lru_array:
        class: Symfony\Component\Cache\Adapter\ArrayAdapter
        arguments:
            - 0 # default lifetime, overridden by pool config
            - false # store serialized
            - 5 # max items for LRU behavior
            - 0 # max lifetime
*/

/**
 * This service manages user profile data, using a dedicated, size-limited
 * (LRU-style) in-memory cache for high-performance access to frequently
 * viewed profiles.
 */
class UserProfileService
{
    public function __construct(
        // In a real app, this wires to the 'cache.user_profiles' pool defined in YAML.
        // For this example, we'll inject a pre-configured ArrayAdapter.
        private readonly CacheInterface $userProfileCache,
        private readonly UserRepository $userRepository
    ) {}

    /**
     * Gets a user's profile, leveraging the specialized LRU cache.
     */
    public function getProfile(string $userId): ?array
    {
        $cacheKey = 'user_profile_' . str_replace('-', '_', $userId);

        return $this->userProfileCache->get($cacheKey, function (CacheItemInterface $item) use ($userId) {
            $user = $this->userRepository->findOneById($userId);
            if (!$user) {
                // Cache a null result briefly to prevent repeated DB lookups for non-existent users
                $item->expiresAfter(60);
                return null;
            }

            // Cache the profile data for a longer period
            $item->expiresAfter(3600);

            return [
                'id' => $user->id,
                'email' => $user->email,
                'role' => $user->role->value,
            ];
        });
    }

    /**
     * Updates a user's profile and performs an explicit cache deletion.
     * This ensures data consistency.
     */
    public function updateEmail(string $userId, string $newEmail): bool
    {
        $user = $this->userRepository->findOneById($userId);
        if (!$user) {
            return false;
        }

        $user->email = $newEmail;
        $this->userRepository->save($user);

        // Explicitly delete the item from the cache
        $cacheKey = 'user_profile_' . str_replace('-', '_', $userId);
        $this->userProfileCache->delete($cacheKey);

        return true;
    }
}

// --- Example Usage in a Controller ---

class UserController extends AbstractController
{
    public function __construct(private readonly UserProfileService $profileService) {}

    #[Route("/users/{id}/profile", methods: ["GET"])]
    public function profile(string $id): JsonResponse
    {
        $profileData = $this->profileService->getProfile($id);
        return new JsonResponse($profileData);
    }
}

// --- Simulation Runner ---
/*
// Manually create the LRU-configured cache adapter
$lruCache = new ArrayAdapter(0, false, 5); // Max 5 items
$userRepo = new UserRepository();
$service = new UserProfileService($lruCache, $userRepo);
$controller = new UserController($service);

$userIds = array_keys($userRepo->users);

echo "1. Fetching first 5 users to fill the cache (all misses):\n";
for ($i = 0; $i < 5; $i++) {
    $service->getProfile($userIds[$i]);
}
echo "\n2. Fetching user 0 again (should be a hit):\n";
$service->getProfile($userIds[0]);

echo "\n3. Fetching user 6 (a new user). This will be a miss and should evict user 0 (the LRU item):\n";
$service->getProfile($userIds[6]);

echo "\n4. Fetching user 0 again (should now be a miss because it was evicted):\n";
$service->getProfile($userIds[0]);

echo "\n5. Updating user 2's email (will cause a cache delete):\n";
$service->updateEmail($userIds[2], 'new.email@example.com');

echo "\n6. Fetching user 2 again (should be a miss due to invalidation):\n";
$service->getProfile($userIds[2]);
*/
?>