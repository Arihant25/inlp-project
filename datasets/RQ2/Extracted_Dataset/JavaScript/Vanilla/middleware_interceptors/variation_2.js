<script>
// Variation 2: Class-based Middleware Pipeline
// Style: Object-Oriented, encapsulating logic within classes.
// Developer "Brenda" prefers structured, testable classes for each piece of functionality.

(function() {
    // --- Mocks and Utilities ---
    const mock_db = {
        Users: new Map(),
        Posts: new Map()
    };

    const UserRole = { ADMIN: 'ADMIN', USER: 'USER' };
    const PostStatus = { DRAFT: 'DRAFT', PUBLISHED: 'PUBLISHED' };

    const generateUUID = () => 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = Math.random() * 16 | 0, v = c === 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });

    // Seed Data
    const adminId = generateUUID();
    mock_db.Users.set(adminId, {
        id: adminId, email: 'admin@corp.com', password_hash: 'hash1', role: UserRole.ADMIN, is_active: true, created_at: new Date().toISOString()
    });
    const userId = generateUUID();
    mock_db.Users.set(userId, {
        id: userId, email: 'user@corp.com', password_hash: 'hash2', role: UserRole.USER, is_active: true, created_at: new Date().toISOString()
    });

    // --- Middleware Classes ---

    class Middleware {
        process(context, next) {
            throw new Error("Process method must be implemented by subclasses.");
        }
    }

    class LoggingMiddleware extends Middleware {
        process(context, next) {
            console.log(`[Request Start] ${context.req.method} ${context.req.url}`);
            const result = next(context);
            console.log(`[Request End] Status: ${context.res.statusCode}`);
            return result;
        }
    }

    class CORSMiddleware extends Middleware {
        process(context, next) {
            context.res.setHeader('Access-Control-Allow-Origin', '*');
            context.res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
            if (context.req.method === 'OPTIONS') {
                context.res.status(204).send('');
                return; // Stop processing
            }
            return next(context);
        }
    }

    class RateLimitMiddleware extends Middleware {
        constructor(maxRequests, windowMs) {
            super();
            this.maxRequests = maxRequests;
            this.windowMs = windowMs;
            this.clients = new Map();
        }

        process(context, next) {
            const now = Date.now();
            const clientIp = context.req.ip;
            let clientRecord = this.clients.get(clientIp);

            if (!clientRecord || (now - clientRecord.startTime > this.windowMs)) {
                clientRecord = { count: 1, startTime: now };
            } else {
                clientRecord.count++;
            }
            this.clients.set(clientIp, clientRecord);

            if (clientRecord.count > this.maxRequests) {
                throw { message: 'Rate limit exceeded', statusCode: 429 };
            }
            return next(context);
        }
    }

    // Decorator/Wrapper implemented as a middleware class
    class ResponseTransformerMiddleware extends Middleware {
        process(context, next) {
            // Execute the next middleware/handler first
            const result = next(context);

            // Now, transform the response if applicable
            if (context.res.body && context.req.url.includes('/users/')) {
                const user = context.res.body;
                // Transformation logic
                context.res.body = {
                    identifier: user.id,
                    emailAddress: user.email,
                    accountStatus: user.is_active ? 'ACTIVE' : 'INACTIVE'
                };
            }
            return result;
        }
    }

    class ErrorHandlingMiddleware extends Middleware {
        process(context, next) {
            try {
                return next(context);
            } catch (error) {
                console.error(`[ERROR] ${error.message}`);
                context.res.status(error.statusCode || 500);
                context.res.body = { error: error.message || 'Internal Server Error' };
            }
        }
    }

    // --- Pipeline Runner ---
    class Pipeline {
        constructor() {
            this.middlewares = [];
        }

        add(middleware) {
            this.middlewares.push(middleware);
            return this;
        }

        build(finalHandler) {
            // The final handler is the center of the "onion"
            let composed = (context) => finalHandler(context);

            // Wrap it with middlewares in reverse order
            for (let i = this.middlewares.length - 1; i >= 0; i--) {
                const currentMiddleware = this.middlewares[i];
                const nextComposed = composed;
                composed = (context) => currentMiddleware.process(context, nextComposed);
            }
            return composed;
        }
    }

    // --- Core Application Logic ---
    const getUserHandler = (context) => {
        const userId = context.req.url.split('/').pop();
        if (!mock_db.Users.has(userId)) {
            throw { message: 'User entity not found', statusCode: 404 };
        }
        context.res.status(200);
        context.res.body = mock_db.Users.get(userId);
    };

    // --- Simulation ---
    console.log("--- Variation 2: Class-based Middleware Pipeline ---");

    const mockRequest = {
        method: 'GET',
        url: `/api/v2/users/${adminId}`,
        ip: '192.168.1.1',
        headers: {}
    };

    const mockResponse = {
        headers: {},
        statusCode: 200,
        body: null,
        setHeader(key, value) { this.headers[key] = value; },
        status(code) { this.statusCode = code; return this; },
        send(finalBody) {
            const output = finalBody || this.body;
            console.log(`[Response] Status: ${this.statusCode}`);
            console.log(`[Response] Headers: ${JSON.stringify(this.headers)}`);
            console.log(`[Response] Body:\n${JSON.stringify(output, null, 2)}`);
        }
    };

    // Create a context object to pass through the pipeline
    const requestContext = { req: mockRequest, res: mockResponse };

    // Build the pipeline
    const appPipeline = new Pipeline()
        .add(new ErrorHandlingMiddleware()) // Error handler wraps everything
        .add(new LoggingMiddleware())
        .add(new CORSMiddleware())
        .add(new RateLimitMiddleware(5, 60000))
        .add(new ResponseTransformerMiddleware()); // Transformation happens before sending

    const requestProcessor = appPipeline.build(getUserHandler);

    // Execute the request
    requestProcessor(requestContext);
    mockResponse.send(); // Final send-off

    // Simulate a 404 error
    console.log("\n--- Simulating Not Found Error ---");
    const notFoundRequest = { ...mockRequest, url: '/api/v2/users/non-existent-id' };
    const notFoundResponse = { ...mockResponse, body: null };
    const notFoundContext = { req: notFoundRequest, res: notFoundResponse };
    requestProcessor(notFoundContext);
    notFoundResponse.send();

})();
</script>