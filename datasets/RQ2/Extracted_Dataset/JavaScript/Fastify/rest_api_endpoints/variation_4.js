<script>
// Variation 4: "The Modernist" - Modular Schemas & Async Plugins
// Style: Emphasizes modularity, modern JS, and idiomatic Fastify patterns.
// Structure: Routes are encapsulated in an async Fastify plugin. Schemas are defined separately for clarity and reuse.
// Naming: Concise and modern (e.g., `listUsers`, `updateUser`).
// Features: Uses `fastify-plugin` and an async plugin function, a best practice for organizing larger applications.

const Fastify = require('fastify');
const fp = require('fastify-plugin');
const { randomUUID } = require('crypto');

// --- (Simulated File: db/index.js) ---
// A simple in-memory data store
const db = {
    users: new Map(),
};
// Seed with initial data
(() => {
    const users = [
        { id: randomUUID(), email: 'admin.dev@example.com', password_hash: 'hash1', role: 'ADMIN', is_active: true, created_at: new Date() },
        { id: randomUUID(), email: 'user.dev@example.com', password_hash: 'hash2', role: 'USER', is_active: true, created_at: new Date() },
        { id: randomUUID(), email: 'inactive.user@example.com', password_hash: 'hash3', role: 'USER', is_active: false, created_at: new Date() }
    ];
    users.forEach(user => db.users.set(user.id, { ...user, created_at: user.created_at.toISOString() }));
})();


// --- (Simulated File: schemas/user.schema.js) ---
const UserSchema = {
    $id: 'User',
    type: 'object',
    properties: {
        id: { type: 'string', format: 'uuid' },
        email: { type: 'string', format: 'email' },
        role: { type: 'string', enum: ['ADMIN', 'USER'] },
        is_active: { type: 'boolean' },
        created_at: { type: 'string', format: 'date-time' },
    },
};

const sharedSchemas = {
    params: {
        type: 'object',
        properties: {
            id: { type: 'string', format: 'uuid' },
        },
    },
    headers: {
        type: 'object',
        properties: {
            'x-request-id': { type: 'string', format: 'uuid' }
        }
    }
};


// --- (Simulated File: routes/user.routes.js) ---
const userRoutes = async (fastify, opts) => {
    // Add shared schemas to the Fastify instance for reusability
    fastify.addSchema(UserSchema);
    
    // POST /users
    fastify.route({
        method: 'POST',
        url: '/users',
        schema: {
            description: 'Create a new user',
            tags: ['Users'],
            body: {
                type: 'object',
                required: ['email', 'password', 'role'],
                properties: {
                    email: { type: 'string', format: 'email' },
                    password: { type: 'string', minLength: 8 },
                    role: { $ref: 'User#/properties/role' },
                },
            },
            response: { 201: { $ref: 'User#' } },
        },
        handler: async (req, reply) => {
            const { email, password, role } = req.body;
            const newUser = {
                id: randomUUID(),
                email,
                password_hash: `pbkdf2_${password}`,
                role,
                is_active: true,
                created_at: new Date().toISOString(),
            };
            db.users.set(newUser.id, newUser);
            const { password_hash, ...response } = newUser;
            return reply.code(201).send(response);
        },
    });

    // GET /users
    fastify.route({
        method: 'GET',
        url: '/users',
        schema: {
            description: 'List and search for users',
            tags: ['Users'],
            querystring: {
                type: 'object',
                properties: {
                    limit: { type: 'number', default: 20 },
                    page: { type: 'number', default: 1 },
                    role: { $ref: 'User#/properties/role' },
                    is_active: { type: 'boolean' },
                },
            },
        },
        handler: async (req, reply) => {
            const { limit, page, role, is_active } = req.query;
            const offset = (page - 1) * limit;

            const filteredUsers = Array.from(db.users.values())
                .filter(user => (role ? user.role === role : true))
                .filter(user => (is_active !== undefined ? user.is_active === is_active : true));

            const data = filteredUsers.slice(offset, offset + limit).map(({ password_hash, ...u }) => u);
            
            return {
                meta: { total: filteredUsers.length, page, limit },
                data,
            };
        },
    });

    // GET /users/:id
    fastify.route({
        method: 'GET',
        url: '/users/:id',
        schema: {
            description: 'Get a single user by their ID',
            tags: ['Users'],
            params: sharedSchemas.params,
            response: { 200: { $ref: 'User#' } },
        },
        handler: async (req, reply) => {
            const { id } = req.params;
            const user = db.users.get(id);
            if (!user) return reply.code(404).send({ message: 'Not Found' });
            const { password_hash, ...response } = user;
            return response;
        },
    });

    // PATCH /users/:id
    fastify.route({
        method: 'PATCH',
        url: '/users/:id',
        schema: {
            description: 'Partially update a user',
            tags: ['Users'],
            params: sharedSchemas.params,
            body: {
                type: 'object',
                minProperties: 1,
                properties: {
                    role: { $ref: 'User#/properties/role' },
                    is_active: { $ref: 'User#/properties/is_active' },
                },
            },
            response: { 200: { $ref: 'User#' } },
        },
        handler: async (req, reply) => {
            const { id } = req.params;
            const user = db.users.get(id);
            if (!user) return reply.code(404).send({ message: 'Not Found' });

            Object.assign(user, req.body);
            db.users.set(id, user);
            const { password_hash, ...response } = user;
            return response;
        },
    });

    // DELETE /users/:id
    fastify.route({
        method: 'DELETE',
        url: '/users/:id',
        schema: {
            description: 'Delete a user',
            tags: ['Users'],
            params: sharedSchemas.params,
            response: { 204: { type: 'null' } },
        },
        handler: async (req, reply) => {
            const { id } = req.params;
            if (!db.users.has(id)) return reply.code(404).send({ message: 'Not Found' });
            db.users.delete(id);
            return reply.code(204).send();
        },
    });
};


// --- (Simulated File: app.js) ---
const buildApp = (opts = {}) => {
    const app = Fastify(opts);
    // Register plugins
    app.register(fp(userRoutes), { prefix: '/api/v1' });
    return app;
};

const start = async () => {
    const app = buildApp({
        logger: {
            level: 'info',
            transport: { target: 'pino-pretty' },
        },
    });
    try {
        await app.listen({ port: 3000 });
    } catch (err) {
        app.log.error(err);
        process.exit(1);
    }
};

if (require.main === module) {
    start();
}

module.exports = buildApp;
</script>