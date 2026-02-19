// Variation 4: The "Modern Async/Await with Higher-Order Function" Approach
// Developer Style: Focuses on modern JavaScript, functional composition, and DRY (Don't Repeat Yourself) principles.
// Abstracts the repetitive cache-aside logic into a reusable higher-order function.
// Naming convention: Descriptive and functional (e.g., createCachedFetcher).

// To run this:
// 1. npm install fastify uuid
// 2. node this_file.js
// 3. Test with curl:
//    - curl http://localhost:3000/users/1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed (first time: miss, second time: hit)
//    - curl http://localhost:3000/posts/f47ac10b-58cc-4372-a567-0e02b2c3d479
//    - curl -X PUT -H "Content-Type: application/json" -d '{"title":"My Updated Post HOF"}' http://localhost:3000/posts/f47ac10b-58cc-4372-a567-0e02b2c3d479 (invalidates cache)
//    - curl http://localhost:3000/posts/f47ac10b-58cc-4372-a567-0e02b2c3d479 (miss again, then hit)

const Fastify = require('fastify');
const { v4: uuidv4 } = require('uuid');

// --- Mock Data Source ---
const dataSource = (() => {
    const users = new Map();
    const posts = new Map();

    const adminUserId = '1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
    users.set(adminUserId, {
        id: adminUserId, email: 'admin@example.com', password_hash: '$2b$10$...',
        role: 'ADMIN', is_active: true, created_at: new Date().toISOString(),
    });
    const postId = 'f47ac10b-58cc-4372-a567-0e02b2c3d479';
    posts.set(postId, {
        id: postId, user_id: adminUserId, title: 'My First Post',
        content: 'This is the content.', status: 'PUBLISHED',
    });

    return {
        fetchUser: async (id) => {
            console.log(`[DB] Querying user with id: ${id}`);
            await new Promise(res => setTimeout(res, 50));
            return users.get(id);
        },
        fetchPost: async (id) => {
            console.log(`[DB] Querying post with id: ${id}`);
            await new Promise(res => setTimeout(res, 50));
            return posts.get(id);
        },
        updatePost: async (id, data) => {
            console.log(`[DB] Updating post with id: ${id}`);
            await new Promise(res => setTimeout(res, 50));
            const post = posts.get(id);
            if (!post) return null;
            const updatedPost = { ...post, ...data };
            posts.set(id, updatedPost);
            return updatedPost;
        }
    };
})();

// --- Cache Manager and Higher-Order Function ---
const cacheManager = (() => {
    // Simple Map-based cache with TTL and LRU-like behavior
    const cache = new Map();
    const MAX_SIZE = 100;
    const TTL = 1000 * 60 * 5;

    const get = (key) => {
        const entry = cache.get(key);
        if (!entry) return null;
        if (Date.now() > entry.expiry) {
            cache.delete(key);
            return null;
        }
        // Refresh for LRU
        cache.delete(key);
        cache.set(key, entry);
        return entry.value;
    };

    const set = (key, value) => {
        if (cache.size >= MAX_SIZE) {
            const oldestKey = cache.keys().next().value;
            cache.delete(oldestKey);
        }
        const entry = { value, expiry: Date.now() + TTL };
        cache.set(key, entry);
    };

    const del = (key) => cache.delete(key);

    // The HOF for cache-aside logic
    const createCachedFetcher = (fetchFunction, keyPrefix, logger) => {
        return async (id) => {
            const cacheKey = `${keyPrefix}:${id}`;
            
            const cachedData = get(cacheKey);
            if (cachedData) {
                logger.info(`[CACHE] HIT for key: ${cacheKey}`);
                return cachedData;
            }

            logger.info(`[CACHE] MISS for key: ${cacheKey}`);
            const data = await fetchFunction(id);

            if (data) {
                set(cacheKey, data);
                logger.info(`[CACHE] SET for key: ${cacheKey}`);
            }
            return data;
        };
    };

    return { createCachedFetcher, invalidate: del };
})();

// --- Server Setup ---
const app = Fastify({ logger: true });

// Create cached versions of our data fetching functions
const getCachedUserById = cacheManager.createCachedFetcher(dataSource.fetchUser, 'user', app.log);
const getCachedPostById = cacheManager.createCachedFetcher(dataSource.fetchPost, 'post', app.log);

// --- Route Handlers ---
app.get('/users/:id', async (request, reply) => {
    const user = await getCachedUserById(request.params.id);
    if (!user) {
        return reply.code(404).send({ error: 'User not found' });
    }
    return reply.send(user);
});

app.get('/posts/:id', async (request, reply) => {
    const post = await getCachedPostById(request.params.id);
    if (!post) {
        return reply.code(404).send({ error: 'Post not found' });
    }
    return reply.send(post);
});

app.put('/posts/:id', async (request, reply) => {
    const { id } = request.params;
    const updatedPost = await dataSource.updatePost(id, request.body);

    if (!updatedPost) {
        return reply.code(404).send({ error: 'Post not found' });
    }

    // Explicitly invalidate the cache
    const cacheKey = `post:${id}`;
    cacheManager.invalidate(cacheKey);
    app.log.info(`[CACHE] DELETED/INVALIDATED key: ${cacheKey}`);

    return reply.send(updatedPost);
});

const start = async () => {
    try {
        await app.listen({ port: 3000 });
    } catch (err) {
        app.log.error(err);
        process.exit(1);
    }
};

start();