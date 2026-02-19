/*
 * Variation 2: OOP/Class-based Approach
 *
 * This variation encapsulates middleware logic and application setup within classes.
 * A `MiddlewareRegistry` class provides methods to generate middleware instances.
 * An `App` class orchestrates the entire server setup, promoting organization and testability.
 *
 * To run this code:
 * 1. Save as `index.js`.
 * 2. Run `npm init -y`.
 * 3. Run `npm install express cors express-rate-limit winston uuid`.
 * 4. Run `node index.js`.
 */

const express = require('express');
const cors = require('cors');
const rateLimit = require('express-rate-limit');
const winston = require('winston');
const { v4: uuidv4 } = require('uuid');

// --- Mock Data ---
const MOCK_DB = {
    Users: [
        { id: uuidv4(), email: 'admin@example.com', password_hash: '...', role: 'ADMIN', is_active: true, created_at: new Date() },
        { id: uuidv4(), email: 'user@example.com', password_hash: '...', role: 'USER', is_active: true, created_at: new Date() },
    ],
    Posts: [
        { id: uuidv4(), user_id: null, title: 'First Post', content: 'Hello World!', status: 'PUBLISHED' },
    ],
};
MOCK_DB.Posts[0].user_id = MOCK_DB.Users[1].id;


// --- Class-based Middleware and Error Handling ---

class MiddlewareRegistry {
    constructor() {
        this.logger = winston.createLogger({
            level: 'info',
            format: winston.format.combine(
                winston.format.timestamp(),
                winston.format.json()
            ),
            transports: [new winston.transports.Console()],
        });
    }

    createRequestLogger() {
        return (req, res, next) => {
            const start = Date.now();
            res.on('finish', () => {
                const duration = Date.now() - start;
                this.logger.info({
                    message: 'Request processed',
                    method: req.method,
                    url: req.originalUrl,
                    status: res.statusCode,
                    duration: `${duration}ms`,
                    ip: req.ip,
                });
            });
            next();
        };
    }

    createCorsHandler() {
        const corsOptions = {
            origin: 'http://localhost:3000', // Example of a more restrictive policy
            credentials: true,
        };
        return cors(corsOptions);
    }

    createRateLimiter() {
        return rateLimit({
            windowMs: 10 * 60 * 1000, // 10 minutes
            max: 120,
            handler: (req, res) => {
                res.status(429).json({ message: 'Rate limit exceeded.' });
            },
        });
    }

    createResponseTransformer() {
        return (req, res, next) => {
            // Add a custom method to the response object for standardized success responses
            res.sendSuccess = (data, statusCode = 200) => {
                res.status(statusCode).json({
                    status: 'success',
                    data: data,
                });
            };
            next();
        };
    }
}

class ErrorHandler {
    static handle(err, req, res, next) {
        const status = err.status || 500;
        const message = err.message || 'Internal Server Error';

        // Log the full error for debugging, but don't expose stack to client
        console.error(err.stack);

        res.status(status).json({
            status: 'error',
            error: {
                message: message,
            },
        });
    }
}

// --- Class-based Controllers ---

class PostController {
    getAll(req, res) {
        res.sendSuccess(MOCK_DB.Posts);
    }

    create(req, res) {
        const { title, content, user_id } = req.body;
        if (!title || !content || !user_id) {
            const err = new Error('Missing required fields: title, content, user_id');
            err.status = 400;
            throw err;
        }
        const newPost = { id: uuidv4(), user_id, title, content, status: 'DRAFT' };
        MOCK_DB.Posts.push(newPost);
        res.sendSuccess(newPost, 201);
    }
}

// --- Main Application Class ---

class App {
    constructor() {
        this.expressApp = express();
        this.port = 3002;
        this.middlewareRegistry = new MiddlewareRegistry();
        this.postController = new PostController();

        this.setupMiddleware();
        this.setupRoutes();
        this.setupErrorHandling();
    }

    setupMiddleware() {
        this.expressApp.use(express.json());
        this.expressApp.use(this.middlewareRegistry.createRequestLogger());
        this.expressApp.use(this.middlewareRegistry.createCorsHandler());
        this.expressApp.use('/api/', this.middlewareRegistry.createRateLimiter());
        this.expressApp.use(this.middlewareRegistry.createResponseTransformer());
    }

    setupRoutes() {
        const router = express.Router();
        router.get('/posts', this.postController.getAll);
        router.post('/posts', this.postController.create);
        router.get('/users', (req, res) => {
            const publicUsers = MOCK_DB.Users.map(({ password_hash, ...user }) => user);
            res.sendSuccess(publicUsers);
        });
        router.get('/fail', (req, res) => {
            throw new Error('This is a deliberate failure.');
        });
        this.expressApp.use('/api', router);
    }

    setupErrorHandling() {
        this.expressApp.use(ErrorHandler.handle);
    }

    start() {
        this.expressApp.listen(this.port, () => {
            console.log(`Variation 2 (OOP) server running on http://localhost:${this.port}`);
        });
    }
}

// --- Application Entry Point ---
const server = new App();
server.start();