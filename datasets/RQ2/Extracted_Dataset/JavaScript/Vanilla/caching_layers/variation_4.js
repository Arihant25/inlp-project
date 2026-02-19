<script>
// Variation 4: The "Modern Async/Await & Service-Oriented" Developer
// Style: Heavily uses async/await, clear service boundaries, generic components.

// --- Mock Asynchronous Database Client ---
class DatabaseClient {
    constructor() {
        this.data = {
            users: new Map(),
            posts: new Map(),
        };
        this.#seed();
    }

    #seed() {
        const user1 = { id: 'a1b2c3d4', email: 'dev1@startup.io', password_hash: '...', role: 'USER', is_active: true, created_at: new Date().toISOString() };
        const user2 = { id: 'e5f6g7h8', email: 'dev2@startup.io', password_hash: '...', role: 'ADMIN', is_active: true, created_at: new Date().toISOString() };
        this.data.users.set(user1.id, user1);
        this.data.users.set(user2.id, user2);

        const post1 = { id: 'p1q2r3s4', user_id: user1.id, title: 'Async Caching', content: '...', status: 'PUBLISHED' };
        this.data.posts.set(post1.id, post1);
    }

    async find(collection, id) {
        console.log(`[DB-CLIENT] ASYNC FIND in '${collection}' for id '${id}'`);
        await new Promise(resolve => setTimeout(resolve, 60)); // Simulate network latency
        return this.data[collection]?.get(id) ?? null;
    }

    async save(collection, entity) {
        console.log(`[DB-CLIENT] ASYNC SAVE in '${collection}' for id '${entity.id}'`);
        await new Promise(resolve => setTimeout(resolve, 60));
        const existing = this.data[collection]?.get(entity.id) || {};
        const updated = { ...existing, ...entity };
        this.data[collection]?.set(entity.id, updated);
        return updated;
    }
}

// --- Self-Contained LRU Cache Manager ---
class LRUCacheManager {
    #capacity;
    #map;
    #head;
    #tail;

    constructor(capacity) {
        this.#capacity = capacity;
        this.#map = new Map();
        this.#head = { key: 'head', next: null };
        this.#tail = { key: 'tail', prev: this.#head };
        this.#head.next = this.#tail;
    }

    get(key) {
        if (!this.#map.has(key)) {
            return null;
        }
        const node = this.#map.get(key);
        this.#promote(node);
        return node.value;
    }

    set(key, value) {
        if (this.#map.has(key)) {
            const node = this.#map.get(key);
            node.value = value;
            this.#promote(node);
        } else {
            if (this.#map.size >= this.#capacity) {
                this.#evict();
            }
            const newNode = { key, value, prev: null, next: null };
            this.#map.set(key, newNode);
            this.#attach(newNode);
        }
    }

    delete(key) {
        if (this.#map.has(key)) {
            const node = this.#map.get(key);
            this.#detach(node);
            this.#map.delete(key);
        }
    }

    #promote(node) {
        this.#detach(node);
        this.#attach(node);
    }

    #detach(node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    #attach(node) {
        node.next = this.#head.next;
        node.prev = this.#head;
        this.#head.next.prev = node;
        this.#head.next = node;
    }

    #evict() {
        const lruNode = this.#tail.prev;
        if (lruNode !== this.#head) {
            this.#detach(lruNode);
            this.#map.delete(lruNode.key);
        }
    }
}

// --- Generic Caching Service for Cache-Aside Pattern ---
class GenericCacheService {
    #cache;
    #fetcher;
    #keyPrefix;
    #ttl;

    constructor({ cache, fetcher, keyPrefix, ttl = 30000 }) {
        this.#cache = cache;
        this.#fetcher = fetcher;
        this.#keyPrefix = keyPrefix;
        this.#ttl = ttl;
    }

    async findById(id) {
        const key = `${this.#keyPrefix}:${id}`;
        const cached = this.#cache.get(key);

        if (cached && cached.expiresAt > Date.now()) {
            console.log(`[CACHE-SERVICE] HIT for key '${key}'`);
            return cached.payload;
        }
        
        console.log(`[CACHE-SERVICE] MISS for key '${key}'`);
        const payload = await this.#fetcher(id);
        if (payload) {
            this.#cache.set(key, { payload, expiresAt: Date.now() + this.#ttl });
        }
        return payload;
    }

    async invalidate(id) {
        const key = `${this.#keyPrefix}:${id}`;
        console.log(`[CACHE-SERVICE] INVALIDATING key '${key}'`);
        this.#cache.delete(key);
    }
}

// --- Application Bootstrap and Demonstration ---
async function bootstrap() {
    console.log("--- Variation 4: Modern Async/Await & Service-Oriented Demo ---");

    // 1. Initialize core infrastructure
    const dbClient = new DatabaseClient();
    const mainCache = new LRUCacheManager(20);

    // 2. Create specific services by composing generic components
    const userService = new GenericCacheService({
        cache: mainCache,
        fetcher: (id) => dbClient.find('users', id),
        keyPrefix: 'user',
        ttl: 3000 // 3 second TTL
    });

    const postService = new GenericCacheService({
        cache: mainCache,
        fetcher: (id) => dbClient.find('posts', id),
        keyPrefix: 'post',
        ttl: 10000
    });
    
    const userId = 'a1b2c3d4';

    console.log("\nStep 1: Fetch user. Expect a cache miss.");
    let user = await userService.findById(userId);
    console.log("  => Fetched:", user.email);

    console.log("\nStep 2: Fetch user again. Expect a cache hit.");
    user = await userService.findById(userId);
    console.log("  => Fetched:", user.email);

    console.log("\nStep 3: Invalidate user cache.");
    await userService.invalidate(userId);

    console.log("\nStep 4: Fetch user again. Expect a cache miss due to invalidation.");
    user = await userService.findById(userId);
    console.log("  => Fetched:", user.email);
    
    console.log("\nStep 5: Wait for TTL to expire...");
    await new Promise(resolve => setTimeout(resolve, 3100));

    console.log("\nStep 6: Fetch user again. Expect a cache miss due to expiration.");
    user = await userService.findById(userId);
    console.log("  => Fetched:", user.email);
}

bootstrap();
</script>