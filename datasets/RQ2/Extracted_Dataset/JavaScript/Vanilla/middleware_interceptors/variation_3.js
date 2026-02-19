<script>
// Variation 3: Promise-based "Onion" Middleware
// Style: Async/await, functional, inspired by Koa.js.
// Developer "Charlie" loves modern async patterns and concise syntax.

(async function() {
    // --- Mocks and Utilities ---
    const db = {
        users: new Map(),
        posts: new Map()
    };

    const Role = { ADMIN: 'ADMIN', USER: 'USER' };
    const Status = { DRAFT: 'DRAFT', PUBLISHED: 'PUBLISHED' };

    const uuid = () => ([1e7]+-1e3+-4e3+-8e3+-1e11).replace(/[018]/g, c =>
        (c ^ crypto.getRandomValues(new Uint8Array(1))[0] & 15 >> c / 4).toString(16)
    );

    // Seed Data
    const user_id = uuid();
    db.users.set(user_id, {
        id: user_id, email: 'charlie@dev.io', password_hash: 'secret', role: Role.USER, is_active: true, created_at: new Date()
    });
    const post_id = uuid();
    db.posts.set(post_id, {
        id: post_id, user_id: user_id, title: 'Async All the Way', content: '...', status: Status.PUBLISHED
    });

    // --- Middleware Implementations ---

    // 1. Error Handling (Top-level wrapper)
    const handleErrors = async (ctx, next) => {
        try {
            await next();
        } catch (err) {
            console.error(`[ERROR] Caught: ${err.message}`);
            ctx.res.status = err.status || 500;
            ctx.res.body = { error: err.message };
        }
    };

    // 2. Request Logging
    const logRequest = async (ctx, next) => {
        const start = Date.now();
        console.log(`--> ${ctx.req.method} ${ctx.req.path}`);
        await next(); // Go down the chain
        const ms = Date.now() - start;
        console.log(`<-- ${ctx.req.method} ${ctx.req.path} - ${ctx.res.status} - ${ms}ms`);
    };

    // 3. CORS
    const cors = async (ctx, next) => {
        ctx.res.headers['Access-Control-Allow-Origin'] = 'https://example.com';
        await next();
    };

    // 4. Rate Limiting
    const requests = new Map();
    const limit = (max, windowMs) => async (ctx, next) => {
        const ip = ctx.req.ip;
        const now = Date.now();
        const records = (requests.get(ip) || []).filter(time => now - time < windowMs);
        
        if (records.length >= max) {
            const err = new Error('Too many requests, please try again later.');
            err.status = 429;
            throw err;
        }
        
        records.push(now);
        requests.set(ip, records);
        await next();
    };

    // 5. Response Transformation (Decorator/Wrapper)
    // This middleware waits for the downstream handlers to finish, then transforms the body.
    const transformPostResponse = async (ctx, next) => {
        await next(); // Let the route handler set the body first

        if (ctx.res.body && ctx.req.path.startsWith('/posts/')) {
            const post = ctx.res.body;
            // Transformation: Add a summary and format date
            ctx.res.body = {
                postId: post.id,
                title: post.title.toUpperCase(),
                summary: post.content.substring(0, 10) + '...',
                status: post.status,
                authorId: post.user_id
            };
        }
    };

    // --- Route Handler ---
    const getPost = async (ctx) => {
        const id = ctx.req.params.id;
        if (!db.posts.has(id)) {
            const err = new Error('Post not found');
            err.status = 404;
            throw err;
        }
        ctx.res.status = 200;
        ctx.res.body = db.posts.get(id);
    };

    // --- Middleware Composition ---
    const compose = (middlewares) => {
        return (context) => {
            const dispatch = (i) => {
                const fn = middlewares[i];
                if (!fn) return Promise.resolve();
                try {
                    return Promise.resolve(fn(context, () => dispatch(i + 1)));
                } catch (err) {
                    return Promise.reject(err);
                }
            };
            return dispatch(0);
        };
    };

    // --- Simulation ---
    console.log("--- Variation 3: Promise-based 'Onion' Middleware ---");

    // Define the middleware stack for a specific route
    const postRouteStack = [
        handleErrors,
        logRequest,
        cors,
        limit(10, 60000),
        transformPostResponse,
        getPost // The final handler is just another middleware
    ];

    const app = compose(postRouteStack);

    // Mock Context
    const mockContext = {
        req: {
            method: 'GET',
            path: `/posts/${post_id}`,
            ip: '::1',
            params: { id: post_id }
        },
        res: {
            status: 404, // Default status
            headers: {},
            body: null
        }
    };

    // Run the simulation
    await app(mockContext);

    // Output the final response
    console.log("\n--- Final Response ---");
    console.log(`Status: ${mockContext.res.status}`);
    console.log(`Headers: ${JSON.stringify(mockContext.res.headers)}`);
    console.log(`Body:\n${JSON.stringify(mockContext.res.body, null, 2)}`);

    // Simulate a 429 error
    console.log("\n--- Simulating Rate Limit Error ---");
    const rateLimitContext = { ...mockContext, res: { status: 404, headers: {}, body: null } };
    for (let i = 0; i < 11; i++) {
        await app(rateLimitContext);
    }
    console.log(`Final Status after rate limit: ${rateLimitContext.res.status}`);
    console.log(`Final Body after rate limit: ${JSON.stringify(rateLimitContext.res.body)}`);

})();
</script>