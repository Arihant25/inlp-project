<script>
const http = require('http');
const { randomUUID } = require('crypto');

// --- Utils & Config ---
const parseJSON = (req) => new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => { body += chunk.toString(); });
    req.on('error', reject);
    req.on('end', () => {
        try {
            resolve(body ? JSON.parse(body) : {});
        } catch (e) {
            reject(e);
        }
    });
});

const send = (res, statusCode, data) => {
    res.writeHead(statusCode, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(data));
};

const db = new Map([
    ['f47ac10b-58cc-4372-a567-0e02b2c3d479', { id: 'f47ac10b-58cc-4372-a567-0e02b2c3d479', email: 'admin@example.com', password_hash: '$2b$10$fakedhashforadminexample', role: 'ADMIN', is_active: true, created_at: new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString() }],
    ['a1b2c3d4-e5f6-7890-1234-567890abcdef', { id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', email: 'user1@example.com', password_hash: '$2b$10$fakedhashforuserexample1', role: 'USER', is_active: true, created_at: new Date(Date.now() - 1 * 24 * 60 * 60 * 1000).toISOString() }],
    ['b2c3d4e5-f6a7-8901-2345-67890abcdef0', { id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', email: 'user2@example.com', password_hash: '$2b$10$fakedhashforuserexample2', role: 'USER', is_active: false, created_at: new Date().toISOString() }],
]);

// --- API Handlers ---
const handlers = {
    'GET /users': async ({ query }) => {
        let users = Array.from(db.values());

        if (query.has('role')) {
            users = users.filter(u => u.role === query.get('role').toUpperCase());
        }
        if (query.has('is_active')) {
            users = users.filter(u => u.is_active.toString() === query.get('is_active'));
        }

        const page = Number(query.get('page') || 1);
        const limit = Number(query.get('limit') || 10);
        const start = (page - 1) * limit;
        const end = start + limit;

        return {
            statusCode: 200,
            body: {
                total: users.length,
                page,
                limit,
                data: users.slice(start, end)
            }
        };
    },

    'POST /users': async ({ body }) => {
        if (!body.email || !body.password_hash || !body.role) {
            return { statusCode: 400, body: { error: 'Missing required fields' } };
        }
        const newUser = {
            id: randomUUID(),
            email: body.email,
            password_hash: body.password_hash,
            role: body.role,
            is_active: body.is_active ?? true,
            created_at: new Date().toISOString(),
        };
        db.set(newUser.id, newUser);
        return { statusCode: 201, body: newUser };
    },

    'GET /users/:id': async ({ params }) => {
        const user = db.get(params.id);
        return user
            ? { statusCode: 200, body: user }
            : { statusCode: 404, body: { error: 'User not found' } };
    },

    'PUT /users/:id': async ({ params, body }) => {
        if (!db.has(params.id)) {
            return { statusCode: 404, body: { error: 'User not found' } };
        }
        const updatedUser = { ...db.get(params.id), ...body, id: params.id };
        db.set(params.id, updatedUser);
        return { statusCode: 200, body: updatedUser };
    },

    'DELETE /users/:id': async ({ params }) => {
        const success = db.delete(params.id);
        return success
            ? { statusCode: 204, body: null }
            : { statusCode: 404, body: { error: 'User not found' } };
    }
};

// --- Router & Server ---
const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const pathSegments = url.pathname.split('/').filter(Boolean);
    
    let handler;
    let params = {};

    // Simple dynamic route matching
    if (pathSegments.length === 2 && pathSegments[0] === 'users') {
        const routeKey = `${req.method} /users/:id`;
        if (handlers[routeKey]) {
            handler = handlers[routeKey];
            params.id = pathSegments[1];
        }
    } else {
        const routeKey = `${req.method} ${url.pathname}`;
        handler = handlers[routeKey];
    }

    if (!handler) {
        return send(res, 404, { error: 'Not Found' });
    }

    try {
        const body = ['POST', 'PUT', 'PATCH'].includes(req.method) ? await parseJSON(req) : null;
        const context = { params, query: url.searchParams, body };
        
        const { statusCode, body: responseBody } = await handler(context);

        if (statusCode === 204) {
            res.writeHead(204).end();
        } else {
            send(res, statusCode, responseBody);
        }
    } catch (e) {
        console.error(`Error processing request: ${e}`);
        send(res, e instanceof SyntaxError ? 400 : 500, { error: e instanceof SyntaxError ? 'Invalid JSON body' : 'Internal Server Error' });
    }
});

const PORT = 3003;
server.listen(PORT, () => {
    console.log(`Variation 4 (Modern/Minimalist) server running on http://localhost:${PORT}`);
});
</script>