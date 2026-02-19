/*
 * Variation 2: Plugin-based Encapsulation
 *
 * Style:
 * - All core middleware/hook logic is encapsulated within a single Fastify plugin.
 * - This promotes reusability and follows the "everything is a plugin" philosophy of Fastify.
 * - The main server file is very clean, only registering the core plugin and routes.
 * - Uses `fastify-plugin` to avoid encapsulation, making decorators and hooks available globally.
 *
 * To run:
 * 1. npm install fastify @fastify/cors @fastify/rate-limit fastify-plugin pino-pretty
 * 2. node server.js
 */

const Fastify = require('fastify');
const fp = require('fastify-plugin');
const crypto = require('crypto');

// --- Mock Data ---
const MOCK_USERS = [
    { id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', role: 'ADMIN' },
    { id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', role: 'USER' }
];

const MOCK_POSTS = [
    { id: crypto.randomUUID(), user_id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', title: 'Admin Post', content: 'Content here', status: 'PUBLISHED' },
    { id: crypto.randomUUID(), user_id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', title: 'User Draft', content: 'Content here', status: 'DRAFT' }
];

// --- Core Middleware Plugin ---

const coreHooksPlugin = fp(async function(fastify, opts) {
    // 1. CORS Handling
    fastify.register(require('@fastify/cors'), {
        origin: opts.corsOrigin || false,
    });

    // 2. Rate Limiting
    fastify.register(require('@fastify/rate-limit'), {
        max: opts.rateLimitMax || 100,
        timeWindow: '1 minute',
    });

    // 3. Request Logging
    fastify.addHook('onRequest', (request, reply, done) => {
        request.log.info({ reqId: request.id, method: request.method, url: request.url }, 'Request received');
        done();
    });
    
    // 4. Request Transformation (adding a property)
    fastify.addHook('preHandler', (request, reply, done) => {
        request.requestTimestamp = Date.now();
        done();
    });

    // 5. Response Transformation
    fastify.addHook('onSend', (request, reply, payload, done) => {
        let newPayload = payload;
        if (reply.statusCode >= 200 && reply.statusCode < 300 && payload) {
            try {
                const data = JSON.parse(payload);
                newPayload = JSON.stringify({
                    request_id: request.id,
                    data: data
                });
            } catch (e) {
                // Not a JSON payload, pass through
            }
        }
        done(null, newPayload);
    });

    // 6. Error Handling
    fastify.setErrorHandler((error, request, reply) => {
        fastify.log.error(error);
        const statusCode = error.statusCode || 500;
        const response = {
            request_id: request.id,
            error: {
                message: error.message || 'Internal Server Error',
                code: error.code || 'UNSPECIFIED_ERROR',
                details: error.validation ? error.validation : undefined,
            }
        };
        reply.status(statusCode).send(response);
    });
});

// --- Routes Plugin ---

const routesPlugin = fp(async function(fastify, opts) {
    const postResponseSchema = {
        200: {
            type: 'array',
            items: {
                type: 'object',
                properties: {
                    id: { type: 'string' },
                    user_id: { type: 'string' },
                    title: { type: 'string' },
                    content: { type: 'string' },
                    status: { type: 'string' }
                }
            }
        }
    };

    fastify.get('/posts/user/:userId', { schema: { response: postResponseSchema } }, async (request, reply) => {
        const { userId } = request.params;
        if (!MOCK_USERS.find(u => u.id === userId)) {
            const err = new Error('User specified by ID does not exist.');
            err.statusCode = 404;
            err.code = 'USER_NOT_FOUND';
            throw err;
        }
        return MOCK_POSTS.filter(p => p.user_id === userId);
    });
});

// --- Server Bootstrap ---

const buildServer = async () => {
    const server = Fastify({
        logger: {
            level: 'info',
            transport: {
                target: 'pino-pretty'
            }
        }
    });

    // Register the core plugin with options
    server.register(coreHooksPlugin, {
        corsOrigin: '*',
        rateLimitMax: 200
    });

    // Register application routes
    server.register(routesPlugin, { prefix: '/api/v1' });

    return server;
};

const start = async () => {
    try {
        const app = await buildServer();
        await app.listen({ port: 3000 });
    } catch (err) {
        console.error(err);
        process.exit(1);
    }
};

start();