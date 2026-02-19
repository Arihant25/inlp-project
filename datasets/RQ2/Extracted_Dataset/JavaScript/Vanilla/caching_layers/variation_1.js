<script>
// Variation 1: The "Classic OOP" Developer
// Style: Uses ES6 classes, clear separation of concerns, verbose naming.

// --- MOCK DATABASE ---
class MockDatabase {
    constructor() {
        this.users = new Map();
        this.posts = new Map();
        this._initializeData();
    }

    _initializeData() {
        const user1 = {
            id: '1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d',
            email: 'admin@example.com',
            password_hash: 'hashed_password_1',
            role: 'ADMIN',
            is_active: true,
            created_at: new Date().toISOString()
        };
        const user2 = {
            id: 'f0e9d8c7-b6a5-4f3e-2d1c-0b9a8f7e6d5c',
            email: 'user@example.com',
            password_hash: 'hashed_password_2',
            role: 'USER',
            is_active: true,
            created_at: new Date().toISOString()
        };
        this.users.set(user1.id, user1);
        this.users.set(user2.id, user2);

        const post1 = {
            id: 'p1a2b3c4-d5e6-f7a8-b9c0-d1e2f3a4b5c6',
            user_id: user2.id,
            title: 'First Post by User',
            content: 'This is the content of the first post.',
            status: 'PUBLISHED'
        };
        this.posts.set(post1.id, post1);
    }

    async findUserById(id) {
        console.log(`[DB] Fetching user with id: ${id}`);
        await new Promise(resolve => setTimeout(resolve, 50)); // Simulate I/O latency
        return this.users.get(id) || null;
    }

    async findPostById(id) {
        console.log(`[DB] Fetching post with id: ${id}`);
        await new Promise(resolve => setTimeout(resolve, 50)); // Simulate I/O latency
        return this.posts.get(id) || null;
    }

    async updateUser(user) {
        console.log(`[DB] Updating user with id: ${user.id}`);
        await new Promise(resolve => setTimeout(resolve, 50));
        if (this.users.has(user.id)) {
            this.users.set(user.id, { ...this.users.get(user.id), ...user });
            return this.users.get(user.id);
        }
        return null;
    }
}

// --- LRU CACHE IMPLEMENTATION ---
class LRUCache {
    constructor(capacity) {
        this.capacity = capacity;
        this.cache = new Map();
        // Dummy head and tail nodes to avoid edge cases
        this.head = new DoublyLinkedNode();
        this.tail = new DoublyLinkedNode();
        this.head.next = this.tail;
        this.tail.prev = this.head;
    }

    get(key) {
        if (this.cache.has(key)) {
            const node = this.cache.get(key);
            this._moveToHead(node);
            return node.value;
        }
        return null;
    }

    set(key, value) {
        if (this.cache.has(key)) {
            const node = this.cache.get(key);
            node.value = value;
            this._moveToHead(node);
        } else {
            if (this.cache.size >= this.capacity) {
                this._removeTail();
            }
            const newNode = new DoublyLinkedNode(key, value);
            this.cache.set(key, newNode);
            this._addToHead(newNode);
        }
    }


    delete(key) {
        if (this.cache.has(key)) {
            const node = this.cache.get(key);
            this._removeNode(node);
            this.cache.delete(key);
            return true;
        }
        return false;
    }

    _moveToHead(node) {
        this._removeNode(node);
        this._addToHead(node);
    }

    _removeNode(node) {
        const prevNode = node.prev;
        const nextNode = node.next;
        prevNode.next = nextNode;
        nextNode.prev = prevNode;
    }

    _addToHead(node) {
        node.prev = this.head;
        node.next = this.head.next;
        this.head.next.prev = node;
        this.head.next = node;
    }

    _removeTail() {
        const tailNode = this.tail.prev;
        if (tailNode !== this.head) {
            this._removeNode(tailNode);
            this.cache.delete(tailNode.key);
        }
    }
}

class DoublyLinkedNode {
    constructor(key = null, value = null) {
        this.key = key;
        this.value = value;
        this.prev = null;
        this.next = null;
    }
}

// --- CACHING SERVICE LAYER ---
class CachingService {
    constructor(capacity = 50, defaultTTL = 30000) { // default TTL of 30 seconds
        this.lruCache = new LRUCache(capacity);
        this.defaultTTL = defaultTTL;
    }

    get(key) {
        const cachedItem = this.lruCache.get(key);
        if (!cachedItem) {
            return null;
        }

        if (Date.now() > cachedItem.expiresAt) {
            console.log(`[CACHE] Expired item for key: ${key}`);
            this.lruCache.delete(key);
            return null;
        }

        return cachedItem.data;
    }

    set(key, data, ttl = this.defaultTTL) {
        const expiresAt = Date.now() + ttl;
        this.lruCache.set(key, { data, expiresAt });
    }

    invalidate(key) {
        console.log(`[CACHE] Invalidating key: ${key}`);
        return this.lruCache.delete(key);
    }
}

// --- APPLICATION SERVICES ---
class UserService {
    constructor(database, cache) {
        this.database = database;
        this.cache = cache;
        this.userCachePrefix = 'user:';
    }

    async getUserById(userId) {
        const cacheKey = `${this.userCachePrefix}${userId}`;
        
        // 1. Try to get from cache (Cache-Aside)
        const cachedUser = this.cache.get(cacheKey);
        if (cachedUser) {
            console.log(`[CACHE HIT] Found user ${userId} in cache.`);
            return cachedUser;
        }
        console.log(`[CACHE MISS] User ${userId} not in cache.`);

        // 2. On miss, get from database
        const userFromDb = await this.database.findUserById(userId);

        // 3. Store in cache and return
        if (userFromDb) {
            this.cache.set(cacheKey, userFromDb);
        }
        return userFromDb;
    }

    async updateUser(user) {
        const updatedUser = await this.database.updateUser(user);
        if (updatedUser) {
            // Invalidate cache on write
            const cacheKey = `${this.userCachePrefix}${user.id}`;
            this.cache.invalidate(cacheKey);
        }
        return updatedUser;
    }
}


// --- DEMONSTRATION ---
async function main() {
    console.log("--- Variation 1: Classic OOP Demo ---");

    const db = new MockDatabase();
    const cache = new CachingService(10, 5000); // 10 items, 5s TTL
    const userService = new UserService(db, cache);

    const userIdToFetch = '1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d';

    console.log("\n1. First fetch for user (should be a cache miss):");
    let user = await userService.getUserById(userIdToFetch);
    console.log("Fetched User:", user.email);

    console.log("\n2. Second fetch for user (should be a cache hit):");
    user = await userService.getUserById(userIdToFetch);
    console.log("Fetched User:", user.email);

    console.log("\n3. Updating user (should invalidate cache):");
    await userService.updateUser({ id: userIdToFetch, email: 'new.admin@example.com' });

    console.log("\n4. Fetching user again (should be a cache miss):");
    user = await userService.getUserById(userIdToFetch);
    console.log("Fetched User:", user.email);
    
    console.log("\n5. Fetching user again (should be a cache hit):");
    user = await userService.getUserById(userIdToFetch);
    console.log("Fetched User:", user.email);
}

main();
</script>