<script>
// Variation 1: Functional Chaining with a Middleware Runner
// Style: Procedural/Functional, explicit `next` function calls.
// Developer "Alex" prefers clear, sequential execution flow.

(function() {
    // --- Mocks and Utilities ---
    const mockDB = {
        users: new Map(),
        posts: new Map()
    };

    const Roles = { ADMIN: 'ADMIN', USER: 'USER' };
    const PostStatus = { DRAFT: 'DRAFT', PUBLISHED: 'PUBLISHED' };

    // Simple UUID generator
    const uuidv4 = () => 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = Math.random() * 16 | 0, v = c === 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });

    // Seed Data
    const adminUserId = uuidv4();
    mockDB.users.set(adminUserId, {
        id: adminUserId,
        email: 'admin@example.com',
        password_hash: 'hashed_password_admin',
        role: Roles.ADMIN,
        is_active: true,
        created_at: new Date().toISOString()
    });
    const regularUserId = uuidv4();
    mockDB.users.set(regularUserId, {
        id: regularUserId,
        email: 'user@example.com',
        password_hash: 'hashed_password_user',
        role: Roles.USER,
        is_active: true,
        created_at: new Date().toISOString()
    });
    const postId = uuidv4();
    mockDB.posts.set(postId, {
        id: postId,
        user_id: regularUserId,
        title: 'My First Post',
        content: 'This is the content of the first post.',
        status: PostStatus.PUBLISHED
    });

    // --- Middleware Implementations ---

    // 1. Request Logging
    const requestLogger = (req, res, next) => {
        console.log(`[${new Date().toISOString()}] ${req.method} ${req.url} from IP: ${req.ip}`);
        next();
    };

    // 2. CORS Handling
    const corsHandler = (req, res, next) => {
        res.setHeader('Access-Control-Allow-Origin', '*');
        res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
        if (req.method === 'OPTIONS') {
            res.status(204).send('');
            return; // End the chain for OPTIONS pre-flight
        }
        next();
    };

    // 3. Rate Limiting (In-memory)
    const rateLimitStore = new Map();
    const RATE_LIMIT_WINDOW_MS = 60000; // 1 minute
    const RATE_LIMIT_MAX_REQUESTS = 10;
    const rateLimiter = (req, res, next) => {
        const now = Date.now();
        const client = rateLimitStore.get(req.ip) || { count: 0, startTime: now };

        if (now - client.startTime > RATE_LIMIT_WINDOW_MS) {
            client.count = 1;
            client.startTime = now;
        } else {
            client.count++;
        }

        rateLimitStore.set(req.ip, client);

        if (client.count > RATE_LIMIT_MAX_REQUESTS) {
            const err = new Error('Too many requests');
            err.statusCode = 429;
            return next(err); // Pass error to error handler
        }
        next();
    };

    // 4. Request/Response Transformation (Decorator/Wrapper Pattern)
    const transformUserResponse = (handler) => {
        return (req, res, next) => {
            // Hijack the original send method
            const originalSend = res.send;
            res.send = function(body) {
                try {
                    if (res.statusCode === 200 && req.url.startsWith('/api/users/')) {
                        const user = JSON.parse(body);
                        // Transformation: remove sensitive fields
                        const transformedUser = {
                            id: user.id,
                            email: user.email,
                            role: user.role,
                            isActive: user.is_active // a small rename for the client
                        };
                        originalSend.call(this, JSON.stringify(transformedUser, null, 2));
                    } else {
                        originalSend.call(this, body);
                    }
                } catch (e) {
                    originalSend.call(this, body); // Send original if parsing fails
                }
            };
            handler(req, res, next);
        };
    };

    // 5. Error Handling (must be the last middleware)
    const errorHandler = (err, req, res, next) => {
        console.error('An error occurred:', err.message);
        const statusCode = err.statusCode || 500;
        res.status(statusCode).send(JSON.stringify({
            error: {
                message: err.message,
                status: statusCode
            }
        }));
    };

    // --- Core Application Logic (Handlers) ---
    const getUserById = (req, res, next) => {
        const userId = req.url.split('/')[3];
        if (!mockDB.users.has(userId)) {
            const err = new Error('User not found');
            err.statusCode = 404;
            return next(err);
        }
        const user = mockDB.users.get(userId);
        res.setHeader('Content-Type', 'application/json');
        res.status(200).send(JSON.stringify(user));
    };

    // --- Middleware Runner ---
    class App {
        constructor() {
            this.middlewares = [];
            this.errorMiddleware = null;
        }

        use(middleware) {
            if (middleware.length === 4) { // Basic check for error middleware
                this.errorMiddleware = middleware;
            } else {
                this.middlewares.push(middleware);
            }
        }

        run(req, res, handler) {
            const allHandlers = [...this.middlewares, handler];
            let index = 0;

            const next = (err) => {
                if (err) {
                    return this.errorMiddleware ? this.errorMiddleware(err, req, res, () => {}) : console.error("Unhandled error:", err);
                }
                if (index < allHandlers.length) {
                    const layer = allHandlers[index++];
                    try {
                        layer(req, res, next);
                    } catch (e) {
                        next(e);
                    }
                }
            };
            next();
        }
    }

    // --- Simulation ---
    console.log("--- Variation 1: Functional Chaining ---");

    // Create a mock request/response
    const mockRequest = {
        method: 'GET',
        url: `/api/users/${adminUserId}`,
        ip: '127.0.0.1',
        headers: { 'Content-Type': 'application/json' }
    };

    const mockResponse = {
        _headers: {},
        _statusCode: 200,
        setHeader(key, value) { this._headers[key] = value; console.log(`[RES] Set Header: ${key}=${value}`); },
        status(code) { this._statusCode = code; return this; },
        send(body) { console.log(`[RES] Status: ${this._statusCode}\n[RES] Body:\n${body}`); }
    };

    // Setup the app and middleware chain
    const app = new App();
    app.use(requestLogger);
    app.use(corsHandler);
    app.use(rateLimiter);
    app.use(errorHandler); // Register error handler

    // Apply the response transformer decorator to the final handler
    const decoratedGetUserHandler = transformUserResponse(getUserById);

    // Run the request through the middleware chain
    app.run(mockRequest, mockResponse, decoratedGetUserHandler);

    // Simulate a rate-limited request
    console.log("\n--- Simulating Rate Limit ---");
    for (let i = 0; i < 11; i++) {
        console.log(`Request #${i + 1}`);
        app.run(mockRequest, { ...mockResponse }, decoratedGetUserHandler);
    }
})();
</script>