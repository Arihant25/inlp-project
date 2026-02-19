/*
 * Variation 3: OOP/Class-based
 *
 * Style:
 * - The entire server setup is encapsulated within a `Server` class.
 * - Configuration, middleware, routes, and the start process are managed by class methods.
 * - This pattern is familiar to developers from object-oriented backgrounds (e.g., Java, C#).
 * - State (like the fastify instance and config) is managed within the class instance.
 *
 * To run:
 * 1. npm install fastify @fastify/cors @fastify/rate-limit pino-pretty
 * 2. node server.js
 */

const fastify = require('fastify');
const cors = require('@fastify/cors');
const rateLimit = require('@fastify/rate-limit');
const { randomUUID } = require('crypto');

class AppServer {
    constructor(config) {
        this.config = config;
        this.app = fastify({
            logger: {
                level: this.config.logLevel || 'info',
                transport: {
                    target: 'pino-pretty'
                }
            }
        });

        // Mock Data
        this.users = [
            { id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', role: 'ADMIN' },
            { id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', role: 'USER' }
        ];
        this.posts = [
            { id: randomUUID(), user_id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', title: 'Admin Post', content: 'Content', status: 'PUBLISHED' },
            { id: randomUUID(), user_id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', title: 'User Draft', content: 'Content', status: 'DRAFT' }
        ];

        this._setupMiddleware();
        this._setupRoutes();
    }

    _setupMiddleware() {
        // CORS
        this.app.register(cors, { origin: this.config.cors.origin });

        // Rate Limiting
        this.app.register(rateLimit, {
            max: this.config.rateLimit.max,
            timeWindow: this.config.rateLimit.timeWindow
        });

        // Request Logging Hook
        this.app.addHook('onRequest', (req, reply, done) => {
            req.log.info({ method: req.method, url: req.url }, 'Incoming Request');
            done();
        });

        // Response Transformation Hook
        this.app.addHook('onSend', (req, reply, payload, done) => {
            if (reply.statusCode >= 200 && reply.statusCode < 300) {
                const newPayload = JSON.stringify({
                    metadata: { timestamp: new Date().toISOString() },
                    payload: JSON.parse(payload)
                });
                done(null, newPayload);
            } else {
                done(null, payload);
            }
        });

        // Centralized Error Handler
        this.app.setErrorHandler((error, request, reply) => {
            request.log.error(`Error processing request ${request.id}: ${error.message}`);
            
            const statusCode = error.statusCode || 500;
            const responseBody = {
                error: true,
                message: error.message || 'An unexpected error occurred.',
                details: error.validation ? error.validation.map(v => v.message) : null
            };

            reply.status(statusCode).send(responseBody);
        });
    }

    _setupRoutes() {
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

        this.app.get('/users/:userId/posts', {
            schema: {
                params: {
                    type: 'object',
                    properties: { userId: { type: 'string', format: 'uuid' } },
                    required: ['userId']
                },
                response: { 200: { type: 'array', items: postSchema } }
            }
        }, async (request, reply) => {
            const { userId } = request.params;
            if (!this.users.some(u => u.id === userId)) {
                const err = new Error('User not found');
                err.statusCode = 404;
                throw err;
            }
            return this.posts.filter(p => p.user_id === userId);
        });
    }

    async start() {
        try {
            await this.app.listen({ port: this.config.port, host: this.config.host });
            this.app.log.info(`Server listening on port ${this.config.port}`);
        } catch (err) {
            this.app.log.error(err);
            process.exit(1);
        }
    }
}

// --- Application Entry Point ---

const serverConfig = {
    port: 3000,
    host: '127.0.0.1',
    logLevel: 'info',
    cors: {
        origin: '*'
    },
    rateLimit: {
        max: 150,
        timeWindow: '1 minute'
    }
};

const server = new AppServer(serverConfig);
server.start();