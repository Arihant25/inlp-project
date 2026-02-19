/*
 * Variation 1: Classic Functional Approach
 *
 * This variation demonstrates a traditional, functional approach to organizing middleware.
 * Each piece of middleware is a self-contained function, often in its own file.
 * They are then imported and applied sequentially in the main application file.
 *
 * To run this code:
 * 1. Save as `server.js`.
 * 2. Run `npm init -y`.
 * 3. Run `npm install express cors express-rate-limit morgan uuid`.
 * 4. Run `node server.js`.
 */

const express = require('express');
const cors = require('cors');
const rateLimit = require('express-rate-limit');
const morgan = require('morgan');
const { v4: uuidv4 } = require('uuid');

// --- Mock Database ---
const mockUsers = [
    { id: uuidv4(), email: 'admin@example.com', password_hash: '...', role: 'ADMIN', is_active: true, created_at: new Date() },
    { id: uuidv4(), email: 'user@example.com', password_hash: '...', role: 'USER', is_active: true, created_at: new Date() },
];
const mockPosts = [
    { id: uuidv4(), user_id: mockUsers[1].id, title: 'First Post', content: 'Hello World!', status: 'PUBLISHED' },
];


// --- Middleware Implementation (Logically separated as if in different files) ---

// 1. Request Logging Middleware (e.g., from middleware/requestLogger.js)
const requestLogger = morgan('dev');

// 2. CORS Handling Middleware (e.g., from middleware/corsHandler.js)
const corsHandler = cors({
    origin: '*', // In production, restrict this to allowed origins
    methods: ['GET', 'POST', 'PUT', 'DELETE'],
    allowedHeaders: ['Content-Type', 'Authorization'],
});

// 3. Rate Limiting Middleware (e.g., from middleware/rateLimiter.js)
const apiLimiter = rateLimit({
    windowMs: 15 * 60 * 1000, // 15 minutes
    max: 100, // Limit each IP to 100 requests per windowMs
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: 'Too many requests, please try again later.' },
});

// 4. Request/Response Transformation Middleware (e.g., from middleware/transformer.js)
const requestTransformer = (req, res, next) => {
    // Request transformation: Add a unique ID to each request
    req.requestId = uuidv4();
    
    // Response transformation: Add a standard header to all responses
    res.setHeader('X-Request-ID', req.requestId);
    
    // Capture original res.json to wrap the response
    const originalJson = res.json;
    res.json = function(body) {
        const wrappedBody = {
            success: true,
            requestId: req.requestId,
            data: body,
        };
        // Restore original function and call it
        res.json = originalJson;
        return originalJson.call(this, wrappedBody);
    };

    next();
};

// 5. Error Handling Middleware (e.g., from middleware/errorHandler.js)
// This must have 4 arguments to be identified as an error handler by Express.
const errorHandler = (err, req, res, next) => {
    console.error(`[${req.requestId}] Error: ${err.stack}`);
    
    const statusCode = err.statusCode || 500;
    
    // Ensure the response wrapper is not used twice if error occurs after it's set
    if (res.json.toString().includes('wrappedBody')) {
        const originalJson = Object.getPrototypeOf(res).json;
        res.json = originalJson;
    }

    res.status(statusCode).json({
        success: false,
        requestId: req.requestId,
        error: {
            message: err.message || 'An unexpected error occurred.',
            status: statusCode,
        },
    });
};


// --- Express Application Setup ---

const app = express();
const PORT = 3001;

// Apply global middleware
app.use(express.json()); // for parsing application/json
app.use(requestLogger);
app.use(corsHandler);
app.use('/api/', apiLimiter); // Apply rate limiter only to API routes
app.use(requestTransformer);

// --- Routes ---
const apiRouter = express.Router();

apiRouter.get('/users', (req, res) => {
    const activeUsers = mockUsers.filter(u => u.is_active).map(({ password_hash, ...user }) => user);
    res.json(activeUsers);
});

apiRouter.get('/posts', (req, res) => {
    res.json(mockPosts);
});

apiRouter.post('/posts', (req, res) => {
    const { title, content } = req.body;
    if (!title || !content) {
        // Forward error to the error handler
        const err = new Error('Title and content are required.');
        err.statusCode = 400;
        throw err;
    }
    const newPost = {
        id: uuidv4(),
        user_id: mockUsers[1].id, // Mock user
        title,
        content,
        status: 'DRAFT',
    };
    mockPosts.push(newPost);
    res.status(201).json(newPost);
});

// Route that intentionally throws an error to test the handler
apiRouter.get('/error', (req, res, next) => {
    throw new Error("This is a test error!");
});

app.use('/api', apiRouter);

// Apply the final error handling middleware
app.use(errorHandler);

// Start the server
app.listen(PORT, () => {
    console.log(`Variation 1 (Functional) server running on http://localhost:${PORT}`);
});