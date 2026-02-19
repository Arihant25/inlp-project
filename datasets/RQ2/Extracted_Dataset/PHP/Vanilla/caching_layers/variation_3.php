<?php

// Variation 3: The "Modern PHP / Type-Hinted" Developer
// Style: Leverages PHP 8.1+ features like enums, readonly properties, constructor promotion, and strict types.

declare(strict_types=1);

// --- Domain Model with Modern Features ---

enum UserRole: string {
    case ADMIN = 'ADMIN';
    case USER = 'USER';
}

enum PostStatus: string {
    case DRAFT = 'DRAFT';
    case PUBLISHED = 'PUBLISHED';
}

final class User {
    public function __construct(
        public readonly string $id,
        public string $email,
        public readonly string $password_hash,
        public readonly UserRole $role,
        public bool $is_active,
        public readonly int $created_at,
    ) {}
}

final class Post {
    public function __construct(
        public readonly string $id,
        public readonly string $user_id,
        public string $title,
        public string $content,
        public PostStatus $status,
    ) {}
}

// --- Caching Infrastructure with Interfaces ---

interface CacheInterface {
    public function get(string $key): mixed;
    public function set(string $key, mixed $value, int $ttlSeconds): void;
    public function delete(string $key): void;
    public function has(string $key): bool;
}

final class CacheItem {
    public function __construct(
        public readonly mixed $value,
        public readonly int $expiresAt
    ) {}

    public function isExpired(): bool {
        return time() > $this->expiresAt;
    }
}

final class DoublyLinkedNode {
    public ?DoublyLinkedNode $prev = null;
    public ?DoublyLinkedNode $next = null;
    public function __construct(public string $key, public CacheItem $item) {}
}

final class InMemoryLruCache implements CacheInterface {
    private DoublyLinkedNode $head;
    private DoublyLinkedNode $tail;
    /** @var array<string, DoublyLinkedNode> */
    private array $lookup = [];

    public function __construct(private readonly int $capacity = 100) {
        $this->head = new DoublyLinkedNode('', new CacheItem(null, 0));
        $this->tail = new DoublyLinkedNode('', new CacheItem(null, 0));
        $this->head->next = $this->tail;
        $this->tail->prev = $this->head;
    }

    public function get(string $key): mixed {
        if (!isset($this->lookup[$key])) {
            return null;
        }

        $node = $this->lookup[$key];
        if ($node->item->isExpired()) {
            $this->delete($key);
            return null;
        }

        $this->detach($node);
        $this->attach($node);

        return $node->item->value;
    }


    public function set(string $key, mixed $value, int $ttlSeconds = 3600): void {
        if (isset($this->lookup[$key])) {
            $this->delete($key);
        }

        if (count($this->lookup) >= $this->capacity) {
            $this->evict();
        }

        $item = new CacheItem($value, time() + $ttlSeconds);
        $node = new DoublyLinkedNode($key, $item);
        $this->lookup[$key] = $node;
        $this->attach($node);
    }

    public function delete(string $key): void {
        if (!isset($this->lookup[$key])) {
            return;
        }
        $node = $this->lookup[$key];
        $this->detach($node);
        unset($this->lookup[$key]);
    }


    public function has(string $key): bool {
        return isset($this->lookup[$key]) && !$this->lookup[$key]->item->isExpired();
    }

    private function attach(DoublyLinkedNode $node): void {
        $node->next = $this->head->next;
        $node->prev = $this->head;
        $this->head->next->prev = $node;
        $this->head->next = $node;
    }

    private function detach(DoublyLinkedNode $node): void {
        $node->prev->next = $node->next;
        $node->next->prev = $node->prev;
    }

    private function evict(): void {
        $lruNode = $this->tail->prev;
        if ($lruNode !== $this->head) {
            $this->delete($lruNode->key);
        }
    }
}

// --- Service Layer ---

class DataSource {
    private static array $storage = [];
    public static function findUser(string $id): ?User {
        echo "--- DATASOURCE: Reading user {$id} ---\n";
        usleep(50000);
        return self::$storage["user:{$id}"] ?? null;
    }
    public static function persistUser(User $user): void {
        echo "--- DATASOURCE: Writing user {$user->id} ---\n";
        usleep(50000);
        self::$storage["user:{$user->id}"] = $user;
    }
}

class UserService {
    public function __construct(
        private readonly CacheInterface $cache,
        private readonly DataSource $dataSource
    ) {}

    // Implements Cache-Aside pattern
    public function findById(string $id): ?User {
        $cacheKey = "user:{$id}";
        $cachedUser = $this->cache->get($cacheKey);

        if ($cachedUser instanceof User) {
            echo "CACHE HIT for user {$id}\n";
            return $cachedUser;
        }

        echo "CACHE MISS for user {$id}\n";
        $userFromDb = $this->dataSource::findUser($id);

        if ($userFromDb) {
            $this->cache->set($cacheKey, $userFromDb, 60);
        }

        return $userFromDb;
    }

    // Implements cache invalidation
    public function updateUserEmail(string $id, string $newEmail): ?User {
        $user = $this->findById($id);
        if ($user) {
            $user->email = $newEmail;
            $this->dataSource::persistUser($user);
            $this->cache->delete("user:{$id}");
            echo "INVALIDATED cache for user {$id}\n";
            return $user;
        }
        return null;
    }
}

// --- Main Execution ---

// Setup
$userId = '33333333-3333-3333-3333-333333333333';
$initialUser = new User($userId, 'modern@example.com', 'hash3', UserRole::ADMIN, true, time());
DataSource::persistUser($initialUser);

$cache = new InMemoryLruCache(10);
$userService = new UserService($cache, new DataSource());

echo "1. First call to find user:\n";
$user1 = $userService->findById($userId);
var_dump($user1->email);

echo "\n2. Second call (should hit cache):\n";
$user2 = $userService->findById($userId);
var_dump($user2->email);

echo "\n3. Update user's email (invalidates cache):\n";
$userService->updateUserEmail($userId, 'modern.updated@example.com');

echo "\n4. Third call (should miss, then fetch from DB):\n";
$user3 = $userService->findById($userId);
var_dump($user3->email);

?>