// Variation 1: The "Classic" Functional Approach
// Developer Style: Prefers clear separation of concerns into functional modules.
// Uses a popular, battle-tested library for caching.
// Naming convention: camelCase for functions and variables.

// To run this:
// 1. npm install fastify lru-cache uuid
// 2. node this_file.js
// 3. Test with curl:
//    - curl http://localhost:3000/users/1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed (first time: miss, second time: hit)
//    - curl http://localhost:3000/posts/f47ac10b-58cc-4372-a567-0e02b2c3d479
//    - curl -X PUT -H "Content-Type: application/json" -d '{"title":"My Updated Post"}' http://localhost:3000/posts/f47ac10b-58cc-4372-a567-0e02b2c3d479 (invalidates cache)
//    - curl http://localhost:3000/posts/f47ac10b-58cc-4372-a567-0e02b2c3d479 (miss again, then hit)

const Fastify = require('fastify');
const { LRUCache } = require('lru-cache');
const { v4: uuidv4 } = require('uuid');

// --- Mock Database Module ---
const db = (() => {
    const users = new Map();
    const posts = new Map();

    // Seed data
    const adminUserId = '1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
    const regularUserId = 'c2a4b3e1-4f2a-4b1d-8e7f-6a5c4b3d2e1a';

    users.set(adminUserId, {
        id: adminUserId,
        email: 'admin@example.com',
        password_hash: '$2b$10$...',
        role: 'ADMIN',
        is_active: true,
        created_at: new Date().toISOString(),
    });
    users.set(regularUserId, {
        id: regularUserId,
        email: 'user@example.com',
        password_hash: '$2b$10$...',
        role: 'USER',
        is_active: true,
        created_at: new Date().toISOString(),
    });

    const postId = 'f47ac10b-58cc-4372-a567-0e02b2c3d479';
    posts.set(postId, {
        id: postId,
        user_id: adminUserId,
        title: 'My First Post',
        content: 'This is the content of the first post.',
        status: 'PUBLISHED',
    });

    const findUserById = async (id) => {
        console.log(`[DB] Querying user with id: ${id}`);
        await new Promise(resolve => setTimeout(resolve, 50)); // Simulate DB latency
        return users.get(id);
    };

    const findPostById = async (id) => {
        console.log(`[DB] Querying post with id: ${id}`);
        await new Promise(resolve => setTimeout(resolve, 50)); // Simulate DB latency
        return posts.get(id);
    };

    const updatePost = async (id, data) => {
        console.log(`[DB] Updating post with id: ${id}`);
        await new Promise(resolve => setTimeout(resolve, 50)); // Simulate DB latency
        const post = posts.get(id);
        if (!post) return null;
        const updatedPost = { ...post, ...data };
        posts.set(id, updatedPost);
        return updatedPost;
    };

    return { findUserById, findPostById, updatePost };
})();

// --- Cache Module ---
const cache = (() => {
    const options = {
        max: 100, // Max 100 items (LRU)
        ttl: 1000 * 60 * 5, // 5 minutes TTL
    };
    const lruCache = new LRUCache(options);

    const get = (key) => lruCache.get(key);
    const set = (key, value) => lruCache.set(key, value);
    const del = (key) => lruCache.delete(key);

    return { get, set, del };
})();


// --- Server Setup & Routes ---
const fastify = Fastify({ logger: true });

// GET User by ID - Cache-Aside Pattern
fastify.get('/users/:id', async (request, reply) => {
    const { id } = request.params;
    const cacheKey = `user:${id}`;

    let user = cache.get(cacheKey);

    if (user) {
        fastify.log.info(`[CACHE] HIT for key: ${cacheKey}`);
        return reply.send(user);
    }

    fastify.log.info(`[CACHE] MISS for key: ${cacheKey}`);
    user = await db.findUserById(id);

    if (!user) {
        return reply.code(404).send({ error: 'User not found' });
    }

    cache.set(cacheKey, user);
    fastify.log.info(`[CACHE] SET for key: ${cacheKey}`);
    return reply.send(user);
});

// GET Post by ID - Cache-Aside Pattern
fastify.get('/posts/:id', async (request, reply) => {
    const { id } = request.params;
    const cacheKey = `post:${id}`;

    let post = cache.get(cacheKey);

    if (post) {
        fastify.log.info(`[CACHE] HIT for key: ${cacheKey}`);
        return reply.send(post);
    }

    fastify.log.info(`[CACHE] MISS for key: ${cacheKey}`);
    post = await db.findPostById(id);

    if (!post) {
        return reply.code(404).send({ error: 'Post not found' });
    }

    cache.set(cacheKey, post);
    fastify.log.info(`[CACHE] SET for key: ${cacheKey}`);
    return reply.send(post);
});

// PUT Post by ID - Cache Invalidation
fastify.put('/posts/:id', async (request, reply) => {
    const { id } = request.params;
    const cacheKey = `post:${id}`;

    const updatedPost = await db.updatePost(id, request.body);

    if (!updatedPost) {
        return reply.code(404).send({ error: 'Post not found' });
    }

    // Invalidation strategy: delete the key from cache
    cache.del(cacheKey);
    fastify.log.info(`[CACHE] DELETED/INVALIDATED key: ${cacheKey}`);

    return reply.send(updatedPost);
});

const start = async () => {
    try {
        await fastify.listen({ port: 3000 });
    } catch (err) {
        fastify.log.error(err);
        process.exit(1);
    }
};

start();