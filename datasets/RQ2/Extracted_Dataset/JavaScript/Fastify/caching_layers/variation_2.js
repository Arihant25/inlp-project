// Variation 2: The "OOP/Service Layer" Approach
// Developer Style: Prefers structuring logic within classes and services for better encapsulation and testability.
// Implements a custom LRU cache to demonstrate understanding of the underlying mechanism.
// Naming convention: PascalCase for classes, camelCase for methods/variables.

// To run this:
// 1. npm install fastify uuid
// 2. node this_file.js
// 3. Test with curl:
//    - curl http://localhost:3000/users/1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed (first time: miss, second time: hit)
//    - curl http://localhost:3000/posts/f47ac10b-58cc-4372-a567-0e02b2c3d479
//    - curl -X PUT -H "Content-Type: application/json" -d '{"title":"My Updated Post OOP"}' http://localhost:3000/posts/f47ac10b-58cc-4372-a567-0e02b2c3d479 (invalidates cache)
//    - curl http://localhost:3000/posts/f47ac10b-58cc-4372-a567-0e02b2c3d479 (miss again, then hit)

const Fastify = require('fastify');
const { v4: uuidv4 } = require('uuid');

// --- Domain Enums ---
const UserRole = { ADMIN: 'ADMIN', USER: 'USER' };
const PostStatus = { DRAFT: 'DRAFT', PUBLISHED: 'PUBLISHED' };

// --- Custom Cache Implementation ---
class LruCacheService {
    constructor(capacity = 100, ttl = 1000 * 60 * 5) {
        this.capacity = capacity;
        this.ttl = ttl;
        this.cache = new Map();
    }

    get(key) {
        if (!this.cache.has(key)) {
            return null;
        }

        const entry = this.cache.get(key);

        // Check TTL
        if (Date.now() > entry.expiry) {
            this.cache.delete(key);
            return null;
        }

        // Refresh entry to mark as recently used (for LRU)
        this.cache.delete(key);
        this.cache.set(key, entry);

        return entry.value;
    }

    set(key, value) {
        if (this.cache.has(key)) {
            this.cache.delete(key);
        } else if (this.cache.size >= this.capacity) {
            // Evict the least recently used item (first item in Map's iteration)
            const firstKey = this.cache.keys().next().value;
            this.cache.delete(firstKey);
        }

        const entry = {
            value,
            expiry: Date.now() + this.ttl,
        };
        this.cache.set(key, entry);
    }

    delete(key) {
        this.cache.delete(key);
    }
}

// --- Mock Database Class ---
class MockDatabase {
    constructor() {
        this.users = new Map();
        this.posts = new Map();
        this._seedData();
    }

    _seedData() {
        const adminUserId = '1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
        this.users.set(adminUserId, {
            id: adminUserId, email: 'admin@example.com', password_hash: '$2b$10$...',
            role: UserRole.ADMIN, is_active: true, created_at: new Date().toISOString(),
        });
        const postId = 'f47ac10b-58cc-4372-a567-0e02b2c3d479';
        this.posts.set(postId, {
            id: postId, user_id: adminUserId, title: 'My First Post',
            content: 'This is the content.', status: PostStatus.PUBLISHED,
        });
    }

    async findUserById(id) {
        console.log(`[DB] Querying user with id: ${id}`);
        await new Promise(res => setTimeout(res, 50));
        return this.users.get(id);
    }

    async findPostById(id) {
        console.log(`[DB] Querying post with id: ${id}`);
        await new Promise(res => setTimeout(res, 50));
        return this.posts.get(id);
    }

    async updatePost(id, data) {
        console.log(`[DB] Updating post with id: ${id}`);
        await new Promise(res => setTimeout(res, 50));
        const post = this.posts.get(id);
        if (!post) return null;
        const updatedPost = { ...post, ...data };
        this.posts.set(id, updatedPost);
        return updatedPost;
    }
}

// --- Business Logic Services ---
class PostService {
    constructor(db, cache, logger) {
        this.db = db;
        this.cache = cache;
        this.logger = logger;
        this.CACHE_PREFIX = 'post:';
    }

    async findById(id) {
        const cacheKey = `${this.CACHE_PREFIX}${id}`;
        let post = this.cache.get(cacheKey);

        if (post) {
            this.logger.info(`[CACHE] HIT for key: ${cacheKey}`);
            return post;
        }

        this.logger.info(`[CACHE] MISS for key: ${cacheKey}`);
        post = await this.db.findPostById(id);

        if (post) {
            this.cache.set(cacheKey, post);
            this.logger.info(`[CACHE] SET for key: ${cacheKey}`);
        }
        return post;
    }

    async update(id, data) {
        const updatedPost = await this.db.updatePost(id, data);
        if (updatedPost) {
            const cacheKey = `${this.CACHE_PREFIX}${id}`;
            this.cache.delete(cacheKey);
            this.logger.info(`[CACHE] DELETED/INVALIDATED key: ${cacheKey}`);
        }
        return updatedPost;
    }
}

class UserService {
    constructor(db, cache, logger) {
        this.db = db;
        this.cache = cache;
        this.logger = logger;
        this.CACHE_PREFIX = 'user:';
    }

    async findById(id) {
        const cacheKey = `${this.CACHE_PREFIX}${id}`;
        let user = this.cache.get(cacheKey);
        if (user) {
            this.logger.info(`[CACHE] HIT for key: ${cacheKey}`);
            return user;
        }
        this.logger.info(`[CACHE] MISS for key: ${cacheKey}`);
        user = await this.db.findUserById(id);
        if (user) {
            this.cache.set(cacheKey, user);
            this.logger.info(`[CACHE] SET for key: ${cacheKey}`);
        }
        return user;
    }
}

// --- Server Setup ---
const server = Fastify({ logger: true });

// Instantiate services (Dependency Injection)
const db = new MockDatabase();
const cache = new LruCacheService();
const userService = new UserService(db, cache, server.log);
const postService = new PostService(db, cache, server.log);

// --- Route Definitions ---
server.get('/users/:id', async (request, reply) => {
    const user = await userService.findById(request.params.id);
    if (!user) {
        return reply.code(404).send({ error: 'User not found' });
    }
    return reply.send(user);
});

server.get('/posts/:id', async (request, reply) => {
    const post = await postService.findById(request.params.id);
    if (!post) {
        return reply.code(404).send({ error: 'Post not found' });
    }
    return reply.send(post);
});

server.put('/posts/:id', async (request, reply) => {
    const post = await postService.update(request.params.id, request.body);
    if (!post) {
        return reply.code(404).send({ error: 'Post not found' });
    }
    return reply.send(post);
});

const start = async () => {
    try {
        await server.listen({ port: 3000 });
    } catch (err) {
        server.log.error(err);
        process.exit(1);
    }
};

start();