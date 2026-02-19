// Variation 3: The "Fastify Decorator & Plugin" Approach
// Developer Style: Embraces the framework's ecosystem, preferring plugins and decorators for clean, idiomatic code.
// Logic is organized into self-contained, reusable plugins.
// Naming convention: Follows Fastify plugin conventions.

// To run this:
// 1. npm install fastify fastify-plugin lru-cache uuid
// 2. node this_file.js
// 3. Test with curl:
//    - curl http://localhost:3000/users/1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed (first time: miss, second time: hit)
//    - curl http://localhost:3000/posts/f47ac10b-58cc-4372-a567-0e02b2c3d479
//    - curl -X PUT -H "Content-Type: application/json" -d '{"title":"My Updated Post Plugin"}' http://localhost:3000/posts/f47ac10b-58cc-4372-a567-0e02b2c3d479 (invalidates cache)
//    - curl http://localhost:3000/posts/f47ac10b-58cc-4372-a567-0e02b2c3d479 (miss again, then hit)

const Fastify = require('fastify');
const fp = require('fastify-plugin');
const { LRUCache } = require('lru-cache');
const { v4: uuidv4 } = require('uuid');

// --- Cache Plugin ---
const cachePlugin = fp(async (fastify, options) => {
    const cache = new LRUCache({
        max: options.max || 100,
        ttl: options.ttl || 1000 * 60 * 5, // 5 minutes
    });

    fastify.decorate('cache', {
        get: (key) => cache.get(key),
        set: (key, value) => cache.set(key, value),
        del: (key) => cache.delete(key),
    });
});

// --- Database Plugin (Mock) ---
const dbPlugin = fp(async (fastify, options) => {
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

    const db = {
        users: {
            findById: async (id) => {
                fastify.log.info(`[DB] Querying user with id: ${id}`);
                await new Promise(res => setTimeout(res, 50));
                return users.get(id);
            }
        },
        posts: {
            findById: async (id) => {
                fastify.log.info(`[DB] Querying post with id: ${id}`);
                await new Promise(res => setTimeout(res, 50));
                return posts.get(id);
            },
            update: async (id, data) => {
                fastify.log.info(`[DB] Updating post with id: ${id}`);
                await new Promise(res => setTimeout(res, 50));
                const post = posts.get(id);
                if (!post) return null;
                const updatedPost = { ...post, ...data };
                posts.set(id, updatedPost);
                return updatedPost;
            }
        }
    };

    fastify.decorate('db', db);
});

// --- Routes Plugin ---
const routesPlugin = fp(async (fastify, options) => {
    fastify.get('/users/:id', async (request, reply) => {
        const { id } = request.params;
        const cacheKey = `user:${id}`;

        const cachedUser = fastify.cache.get(cacheKey);
        if (cachedUser) {
            fastify.log.info(`[CACHE] HIT for key: ${cacheKey}`);
            return reply.send(cachedUser);
        }

        fastify.log.info(`[CACHE] MISS for key: ${cacheKey}`);
        const user = await fastify.db.users.findById(id);
        if (!user) {
            return reply.code(404).send({ error: 'User not found' });
        }

        fastify.cache.set(cacheKey, user);
        fastify.log.info(`[CACHE] SET for key: ${cacheKey}`);
        return reply.send(user);
    });

    fastify.get('/posts/:id', async (request, reply) => {
        const { id } = request.params;
        const cacheKey = `post:${id}`;

        const cachedPost = fastify.cache.get(cacheKey);
        if (cachedPost) {
            fastify.log.info(`[CACHE] HIT for key: ${cacheKey}`);
            return reply.send(cachedPost);
        }

        fastify.log.info(`[CACHE] MISS for key: ${cacheKey}`);
        const post = await fastify.db.posts.findById(id);
        if (!post) {
            return reply.code(404).send({ error: 'Post not found' });
        }

        fastify.cache.set(cacheKey, post);
        fastify.log.info(`[CACHE] SET for key: ${cacheKey}`);
        return reply.send(post);
    });

    fastify.put('/posts/:id', async (request, reply) => {
        const { id } = request.params;
        const cacheKey = `post:${id}`;

        const updatedPost = await fastify.db.posts.update(id, request.body);
        if (!updatedPost) {
            return reply.code(404).send({ error: 'Post not found' });
        }

        fastify.cache.del(cacheKey);
        fastify.log.info(`[CACHE] DELETED/INVALIDATED key: ${cacheKey}`);

        return reply.send(updatedPost);
    });
});

// --- Main Server Application ---
const buildServer = () => {
    const app = Fastify({ logger: true });

    app.register(cachePlugin);
    app.register(dbPlugin);
    app.register(routesPlugin);

    return app;
};

const start = async () => {
    const server = buildServer();
    try {
        await server.listen({ port: 3000 });
    } catch (err) {
        server.log.error(err);
        process.exit(1);
    }
};

start();