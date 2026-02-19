/*
 * Variation 4: All-in-One Configurable Middleware Chain
 *
 * This variation centralizes middleware setup into a single, configurable function.
 * It emphasizes configuration over code, allowing features to be toggled or adjusted
 * from a single config object. It also adds custom helper methods to the response
 * object (`res.apiSuccess`, `res.apiError`) for standardized responses.
 *
 * To run this code:
 * 1. Save as `bootstrap.js`.
 * 2. Run `npm init -y`.
 * 3. Run `npm install express cors express-rate-limit chalk uuid`.
 * 4. Run `node bootstrap.js`.
 */

const express = require('express');
const cors = require('cors');
const rateLimit = require('express-rate-limit');
const chalk = require('chalk');
const { v4: uuidv4 } = require('uuid');

// --- Mock Data ---
const db = {
    users: [{ id: uuidv4(), email: 'admin@example.com', password_hash: '...', role: 'ADMIN', is_active: true, created_at: new Date() }],
    posts: [{ id: uuidv4(), user_id: null, title: 'A Post', content: 'Some text.', status: 'PUBLISHED' }],
};
db.posts[0].user_id = db.users[0].id;

// --- Core Middleware Setup Function ---

const setupCoreMiddleware = (app, config) => {
    // 1. Request Logging
    if (config.logging.enabled) {
        app.use((req, res, next) => {
            const start = process.hrtime();
            req.id = uuidv4();
            
            res.on('finish', () => {
                const diff = process.hrtime(start);
                const duration = (diff[0] * 1e3 + diff[1] * 1e-6).toFixed(3);
                const status = res.statusCode;
                
                let statusColor = chalk.green;
                if (status >= 500) statusColor = chalk.red;
                else if (status >= 400) statusColor = chalk.yellow;
                
                console.log(
                    `${chalk.gray(req.id)} | ${statusColor(status)} | ${chalk.cyan(duration.padStart(7, ' '))}ms | ${chalk.bold(req.method)} ${req.originalUrl}`
                );
            });
            next();
        });
    }

    // 2. CORS Handling
    if (config.cors.enabled) {
        app.use(cors(config.cors.options));
    }

    // 3. Rate Limiting
    if (config.rateLimit.enabled) {
        app.use('/api/', rateLimit(config.rateLimit.options));
    }

    // Body Parser
    app.use(express.json());

    // 4. Request/Response Transformation (via response helpers)
    app.use((req, res, next) => {
        res.apiSuccess = (data, statusCode = 200) => {
            return res.status(statusCode).json({
                status: 'success',
                requested_at: new Date().toISOString(),
                data: data,
            });
        };

        res.apiError = (message, statusCode = 500, code = 'INTERNAL_ERROR') => {
            return res.status(statusCode).json({
                status: 'error',
                error: { code, message },
            });
        };
        next();
    });
};

// --- Final Error Handler ---
const setupErrorHandler = (app) => {
    // Catch-all for 404s
    app.use((req, res, next) => {
        res.apiError('The requested resource was not found.', 404, 'NOT_FOUND');
    });

    // Global error handler
    app.use((err, req, res, next) => {
        console.error(chalk.red.bold(`[ERROR] ${req.id} | ${err.stack}`));
        const statusCode = err.statusCode || 500;
        res.apiError(err.message || 'An unexpected server error occurred.', statusCode);
    });
};

// --- Application Bootstrap ---

const app = express();
const PORT = 3004;

// Central configuration object
const appConfig = {
    logging: { enabled: true },
    cors: {
        enabled: true,
        options: { origin: '*' },
    },
    rateLimit: {
        enabled: true,
        options: {
            windowMs: 5 * 60 * 1000, // 5 minutes
            max: 200,
        },
    },
};

// Initialize all core middleware
setupCoreMiddleware(app, appConfig);

// --- API Routes ---
app.get('/api/users', (req, res) => {
    const users = db.users.map(({ password_hash, ...user }) => user);
    res.apiSuccess(users);
});

app.post('/api/posts', (req, res) => {
    const { title, content } = req.body;
    if (!title) {
        // Use the custom error response helper
        return res.apiError('Title is a required field.', 400, 'VALIDATION_ERROR');
    }
    const newPost = { id: uuidv4(), user_id: db.users[0].id, title, content, status: 'DRAFT' };
    db.posts.push(newPost);
    res.apiSuccess(newPost, 201);
});

app.get('/api/broken-route', (req, res, next) => {
    // Simulate a database error
    const err = new Error('Failed to connect to the database.');
    err.statusCode = 503;
    next(err);
});

// Initialize the final error handlers
setupErrorHandler(app);

app.listen(PORT, () => {
    console.log(`Variation 4 (Configurable Chain) server running on http://localhost:${PORT}`);
});