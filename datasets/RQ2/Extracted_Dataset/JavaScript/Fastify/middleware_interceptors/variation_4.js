/*
 * Variation 4: Inline & Concise
 *
 * Style:
 * - All setup is done in a single, linear script.
 * - Uses anonymous arrow functions directly for hooks and handlers.
 * - Minimal boilerplate and abstraction, which is common in smaller services or scripts.
 * - Variable names are often shorter (e.g., `req`, `res`, `err`).
 *
 * To run:
 * 1. npm install fastify @fastify/cors @fastify/rate-limit pino-pretty
 * 2. node server.js
 */

const fastify = require('fastify')({
    logger: {
        transport: {
            target: 'pino-pretty',
            options: { colorize: true }
        }
    }
});
const { randomUUID } = require('crypto');

// --- Mock Data ---
const db = {
    users: new Map([
        ['a1b2c3d4-e5f6-7890-1234-567890abcdef', { id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', role: 'ADMIN' }],
        ['b2c3d4e5-f6a7-8901-2345-67890abcdef0', { id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', role: 'USER' }]
    ]),
    posts: [
        { id: randomUUID(), user_id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', title: 'Admin Post', content: '...', status: 'PUBLISHED' },
        { id: randomUUID(), user_id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', title: 'User Draft', content: '...', status: 'DRAFT' }
    ]
};

// --- Middleware & Hooks Registration ---

// 1. CORS
fastify.register(require('@fastify/cors'), { origin: '*' });

// 2. Rate Limiting
fastify.register(require('@fastify/rate-limit'), { max: 100, timeWindow: '1 minute' });

// 3. Request Logging
fastify.addHook('onRequest', (req, reply, done) => {
    req.log.info(`--> ${req.method} ${req.url}`);
    done();
});

// 4. Response Transformation
fastify.addHook('onSend', (req, reply, payload, done) => {
    // Don't wrap errors
    if (reply.statusCode >= 400) {
        return done(null, payload);
    }
    try {
        const data = JSON.parse(payload);
        const wrapped = JSON.stringify({ ok: true, result: data });
        done(null, wrapped);
    } catch (e) {
        done(null, payload); // Pass non-JSON payloads through
    }
});

// 5. Error Handling
fastify.setErrorHandler((err, req, reply) => {
    req.log.error(err);
    const code = err.statusCode ?? 500;
    reply.status(code).send({
        ok: false,
        error: {
            message: err.message,
            ...(err.validation && { details: err.validation }),
        }
    });
});

// --- Route Definition ---

const postSchema = {
    type: 'object',
    properties: {
        id: { type: 'string' },
        user_id: { type: 'string' },
        title: { type: 'string' },
        status: { type: 'string' }
    }
};

fastify.get('/users/:userId/posts', {
    schema: {
        params: {
            userId: { type: 'string', format: 'uuid' }
        },
        response: {
            200: {
                type: 'array',
                items: postSchema
            }
        }
    }
}, async (req, reply) => {
    const { userId } = req.params;
    if (!db.users.has(userId)) {
        return reply.code(404).send({ ok: false, error: { message: 'User not found' } });
    }
    const userPosts = db.posts.filter(p => p.user_id === userId);
    return userPosts;
});

// --- Server Start ---

const start = async () => {
    try {
        await fastify.listen({ port: 3000 });
    } catch (err) {
        fastify.log.error(err);
        process.exit(1);
    }
};

start();