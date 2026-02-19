/*
 * Variation 3: Modular/Factory Approach
 *
 * This variation uses factory functions to create configured middleware. This pattern
 * separates configuration from implementation, making the middleware more reusable
 * and the main application file cleaner. It's driven by a central config object.
 *
 * To run this code:
 * 1. Save as `main.js`.
 * 2. Run `npm init -y`.
 * 3. Run `npm install express cors express-rate-limit pino-http uuid`.
 * 4. Run `node main.js`.
 */

const express = require('express');
const cors = require('cors');
const rateLimit = require('express-rate-limit');
const pinoHttp = require('pino-http');
const { v4: uuidv4 } = require('uuid');

// --- Mock Data Store ---
const DATA_STORE = {
    users: new Map(),
    posts: new Map(),
};

(() => {
    const adminId = uuidv4();
    const userId = uuidv4();
    DATA_STORE.users.set(adminId, { id: adminId, email: 'admin@example.com', password_hash: '...', role: 'ADMIN', is_active: true, created_at: new Date() });
    DATA_STORE.users.set(userId, { id: userId, email: 'user@example.com', password_hash: '...', role: 'USER', is_active: true, created_at: new Date() });
    const postId = uuidv4();
    DATA_STORE.posts.set(postId, { id: postId, user_id: userId, title: 'My First Post', content: 'Content here.', status: 'PUBLISHED' });
})();


// --- Configuration (e.g., from config/middleware.config.js) ---
const middleware_config = {
    logger: {
        level: process.env.LOG_LEVEL || 'info',
        prettyPrint: process.env.NODE_ENV !== 'production',
    },
    cors: {
        origin: ['http://localhost:3000', 'https://my-app.com'],
        methods: 'GET,HEAD,PUT,PATCH,POST,DELETE',
        preflightContinue: false,
        optionsSuccessStatus: 204,
    },
    rate_limiter: {
        windowMs: 60 * 1000, // 1 minute
        max: 50,
        message: { error: 'Too many API requests from this IP, please try again after a minute' },
    },
};

// --- Middleware Factories (e.g., from factories/middleware.factory.js) ---
const MiddlewareFactory = {
    createLogger: (options) => pinoHttp(options),
    createCorsHandler: (options) => cors(options),
    createRateLimiter: (options) => rateLimit(options),
    createResponseTransformer: () => {
        return (req, res, next) => {
            const originalSend = res.send;
            res.send = function(body) {
                // Only wrap if it's a JSON response
                if (res.getHeader('Content-Type')?.includes('application/json')) {
                    try {
                        const parsedBody = JSON.parse(body);
                        const transformedBody = {
                            metadata: {
                                timestamp: new Date().toISOString(),
                                path: req.path,
                            },
                            payload: parsedBody,
                        };
                        return originalSend.call(this, JSON.stringify(transformedBody));
                    } catch (e) {
                        // Not valid JSON, send as is
                        return originalSend.call(this, body);
                    }
                }
                return originalSend.call(this, body);
            };
            next();
        };
    },
};

// --- Error Handler (e.g., from handlers/error.handler.js) ---
const createErrorHandler = () => {
    return (err, req, res, next) => {
        const error_status = err.statusCode || 500;
        const error_message = err.message || 'Something went wrong.';
        
        req.log.error(err);

        res.status(error_status).json({
            error: {
                status: error_status,
                message: error_message,
                ...(process.env.NODE_ENV !== 'production' && { stack: err.stack }),
            }
        });
    };
};

// --- Application Setup ---
const app = express();
const PORT = 3003;

// Create middleware instances from factories
const logger = MiddlewareFactory.createLogger(middleware_config.logger);
const corsHandler = MiddlewareFactory.createCorsHandler(middleware_config.cors);
const apiLimiter = MiddlewareFactory.createRateLimiter(middleware_config.rate_limiter);
const responseTransformer = MiddlewareFactory.createResponseTransformer();
const errorHandler = createErrorHandler();

// Apply middleware
app.use(logger);
app.use(express.json());
app.use(corsHandler);
app.use(responseTransformer);

// --- API Routes ---
const api_router = express.Router();
api_router.use(apiLimiter); // Apply rate limiting only to this router

api_router.get('/users', (req, res) => {
    const users = Array.from(DATA_STORE.users.values()).map(({ password_hash, ...user }) => user);
    res.json(users);
});

api_router.get('/posts/:id', (req, res, next) => {
    const post = DATA_STORE.posts.get(req.params.id);
    if (!post) {
        const err = new Error('Post not found');
        err.statusCode = 404;
        return next(err);
    }
    res.json(post);
});

app.use('/api/v1', api_router);

// Apply final error handler
app.use(errorHandler);

app.listen(PORT, () => {
    console.log(`Variation 3 (Factory) server running on http://localhost:${PORT}`);
});