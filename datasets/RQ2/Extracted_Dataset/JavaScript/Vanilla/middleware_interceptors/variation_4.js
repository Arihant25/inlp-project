<script>
// Variation 4: Explicit Decorator Pattern with a Central Dispatcher
// Style: Functional composition, explicitly wrapping handlers with decorators.
// Developer "Diana" values composition over inheritance and clear, single-responsibility functions.

(function() {
    // --- Domain and Mock Data ---
    const DOMAIN_MODEL = {
        USER_ROLE: { ADMIN: 'ADMIN', USER: 'USER' },
        POST_STATUS: { DRAFT: 'DRAFT', PUBLISHED: 'PUBLISHED' }
    };

    const MOCK_DATA_STORE = {
        users: new Map(),
        posts: new Map()
    };

    const generateId = () => Math.random().toString(36).substring(2, 15);

    const userId = generateId();
    MOCK_DATA_STORE.users.set(userId, {
        id: userId,
        email: 'diana@example.com',
        password_hash: 'super_secret_hash',
        role: DOMAIN_MODEL.USER_ROLE.ADMIN,
        is_active: true,
        created_at: new Date().getTime()
    });

    // --- Base Handler and Dispatcher ---
    // The core function that a request eventually hits.
    const baseUserHandler = (request) => {
        const userId = request.path.split('/').pop();
        if (!MOCK_DATA_STORE.users.has(userId)) {
            return { status: 404, body: { message: 'User not found' } };
        }
        const user = MOCK_DATA_STORE.users.get(userId);
        return { status: 200, body: user };
    };

    // A central point that takes a request and a fully decorated handler.
    const dispatcher = (request, handler) => {
        console.log(`\n>>> Dispatching ${request.method} ${request.path}`);
        const response = handler(request);
        console.log(`<<< Responded with status ${response.status}`);
        console.log("Final Response Body:", JSON.stringify(response.body, null, 2));
        return response;
    };

    // --- Decorator Functions (Middleware) ---

    // Decorator 1: Error Handling
    const withErrorHandling = (handler) => {
        return (request) => {
            try {
                return handler(request);
            } catch (error) {
                console.error("[Decorator:ErrorHandling] Caught exception:", error.message);
                return {
                    status: error.statusCode || 500,
                    body: { error: 'An unexpected error occurred.' }
                };
            }
        };
    };

    // Decorator 2: Request Logging
    const withLogging = (handler) => {
        return (request) => {
            console.log(`[Decorator:Logging] IP=${request.clientIp}, Method=${request.method}`);
            return handler(request);
        };
    };

    // Decorator 3: CORS Handling
    const withCORS = (handler) => {
        return (request) => {
            const response = handler(request);
            const headers = { ...response.headers, 'Access-Control-Allow-Origin': '*' };
            return { ...response, headers };
        };
    };

    // Decorator 4: Rate Limiting
    const rateLimiterState = new Map();
    const withRateLimiting = (handler, { max, windowSeconds }) => {
        return (request) => {
            const ip = request.clientIp;
            const now = Math.floor(Date.now() / 1000);
            const clientTimestamps = (rateLimiterState.get(ip) || []).filter(ts => now - ts < windowSeconds);

            if (clientTimestamps.length >= max) {
                return { status: 429, body: { message: 'Rate limit exceeded' } };
            }

            clientTimestamps.push(now);
            rateLimiterState.set(ip, clientTimestamps);
            return handler(request);
        };
    };

    // Decorator 5: Response Transformation
    const withUserResponseTransformer = (handler) => {
        return (request) => {
            const response = handler(request);
            // Only transform successful responses for users
            if (response.status === 200 && request.path.includes('/users/')) {
                const originalUser = response.body;
                // Transformation: remove sensitive data and rename fields
                response.body = {
                    userId: originalUser.id,
                    contactEmail: originalUser.email,
                    accountType: originalUser.role,
                    creationTimestamp: originalUser.created_at
                };
                console.log("[Decorator:Transformer] Transformed user response.");
            }
            return response;
        };
    };

    // --- Simulation ---
    console.log("--- Variation 4: Explicit Decorator Pattern ---");

    // Mock Request Object
    const mockRequest = {
        method: 'GET',
        path: `/api/users/${userId}`,
        clientIp: '203.0.113.55',
        headers: { 'Accept': 'application/json' }
    };

    // Compose the final handler by wrapping the base handler with decorators.
    // The order is important: outer decorators run first.
    let decoratedHandler = withErrorHandling(
        withLogging(
            withCORS(
                withRateLimiting(
                    withUserResponseTransformer(
                        baseUserHandler
                    ), 
                    { max: 5, windowSeconds: 60 }
                )
            )
        )
    );

    // Dispatch the request to the fully decorated handler
    dispatcher(mockRequest, decoratedHandler);

    // Simulate a failing request (not found)
    const failingRequest = { ...mockRequest, path: '/api/users/nonexistent' };
    dispatcher(failingRequest, decoratedHandler);

    // Simulate a rate limit breach
    console.log("\n--- Simulating Rate Limit Breach ---");
    for (let i = 0; i < 6; i++) {
        dispatcher(mockRequest, decoratedHandler);
    }

})();
</script>