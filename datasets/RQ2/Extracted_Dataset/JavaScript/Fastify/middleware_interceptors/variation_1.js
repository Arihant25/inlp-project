/*
 * Variation 1: Functional & Modular
 *
 * Style:
 * - Each piece of middleware logic is encapsulated in its own function.
 * - A main `createServer` function composes these pieces.
 * - Clear separation of concerns, easy to navigate and test individual parts.
 * - Verbose and descriptive naming.
 *
 * To run:
 * 1. npm install fastify @fastify/cors @fastify/rate-limit pino-pretty
 * 2. node server.js
 */

const fastify = require('fastify');
const crypto = require('crypto');

// --- Mock Data & Domain ---

const mockUsers = [
    {
        id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
        email: 'admin@example.com',
        password_hash: '$2b$10$...',
        role: 'ADMIN',
        is_active: true,
        created_at: new Date().toISOString()
    },
    {
        id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0',
        email: 'user@example.com',
        password_hash: '$2b$10$...',
        role: 'USER',
        is_active: true,
        created_at: new Date().toISOString()
    }
];

const mockPosts = [
    {
        id: crypto.randomUUID(),
        user_id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
        title: 'Admin Post',
        content: 'This is a post by the admin.',
        status: 'PUBLISHED'
    },
    {
        id: crypto.randomUUID(),
        user_id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0',
        title: 'User Draft',
        content: 'This is a draft by a regular user.',
        status: 'DRAFT'
    }
];

// --- Middleware Modules (simulated) ---

/**
 * Registers request logging.
 * @param {import('fastify').FastifyInstance} server
 */
function registerRequestLogging(server) {
    server.addHook('onRequest', (request, reply, done) => {
        request.log.info({
            method: request.method,
            url: request.raw.url,
            ip: request.ip
        }, 'Received request');
        done();
    });
}

/**
 * Registers security-related middleware like CORS and rate limiting.
 * @param {import('fastify').FastifyInstance} server
 */
async function registerSecurityMiddleware(server) {
    await server.register(require('@fastify/cors'), {
        origin: '*', // Restrict this in production
        methods: ['GET', 'POST', 'PUT', 'DELETE']
    });

    await server.register(require('@fastify/rate-limit'), {
        max: 100,
        timeWindow: '1 minute'
    });
}

/**
 * Registers response transformation.
 * @param {import('fastify').FastifyInstance} server
 */
function registerResponseTransformer(server) {
    server.addHook('onSend', (request, reply, payload, done) => {
        // Only transform successful JSON responses
        if (reply.statusCode >= 200 && reply.statusCode < 300 && reply.getHeader('content-type')?.includes('application/json')) {
            const transformedPayload = JSON.stringify({
                status: 'success',
                data: JSON.parse(payload)
            });
            done(null, transformedPayload);
        } else {
            done(null, payload);
        }
    });
}

/**
 * Registers a centralized error handler.
 * @param {import('fastify').FastifyInstance} server
 */
function registerErrorHandler(server) {
    server.setErrorHandler((error, request, reply) => {
        request.log.error(error);

        // Handle validation errors specifically
        if (error.validation) {
            return reply.status(400).send({
                status: 'fail',
                message: 'Validation Error',
                errors: error.validation,
            });
        }
        
        // Handle custom application errors
        if (error.statusCode) {
            return reply.status(error.statusCode).send({
                status: 'error',
                message: error.message
            });
        }

        // Generic server error
        reply.status(500).send({
            status: 'error',
            message: 'An internal server error occurred'
        });
    });
}

// --- Route Definitions ---

/**
 * Registers application routes.
 * @param {import('fastify').FastifyInstance} server
 */
function registerRoutes(server) {
    const postSchema = {
        type: 'object',
        properties: {
            id: { type: 'string', format: 'uuid' },
            user_id: { type: 'string', format: 'uuid' },
            title: { type: 'string' },
            content: { type: 'string' },
            status: { type: 'string', enum: ['DRAFT', 'PUBLISHED'] }
        }
    };

    const getPostsOpts = {
        schema: {
            description: 'Get all posts for a specific user',
            params: {
                type: 'object',
                properties: {
                    userId: { type: 'string', format: 'uuid' }
                }
            },
            response: {
                200: {
                    type: 'array',
                    items: postSchema
                }
            }
        }
    };

    server.get('/posts/user/:userId', getPostsOpts, async (request, reply) => {
        const { userId } = request.params;
        const userExists = mockUsers.some(u => u.id === userId);

        if (!userExists) {
            const error = new Error('User not found');
            error.statusCode = 404;
            throw error;
        }

        const posts = mockPosts.filter(p => p.user_id === userId);
        return posts;
    });
}


// --- Server Factory ---

async function createServer() {
    const server = fastify({
        logger: {
            transport: {
                target: 'pino-pretty'
            }
        }
    });

    // Register all components
    registerRequestLogging(server);
    await registerSecurityMiddleware(server);
    registerResponseTransformer(server);
    registerErrorHandler(server);
    registerRoutes(server);

    return server;
}

// --- Main Execution ---

async function start() {
    try {
        const server = await createServer();
        await server.listen({ port: 3000, host: '0.0.0.0' });
    } catch (err) {
        console.error(err);
        process.exit(1);
    }
}

start();