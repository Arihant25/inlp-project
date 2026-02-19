<script>
// Variation 3: "The OOP Enthusiast" - Class-based Controller
// Style: Groups related route handlers as methods within a `UserController` class.
// Structure: A class is defined to handle the logic, which is then instantiated and its methods are bound to Fastify routes.
// Naming: Follows class-based conventions (e.g., `userController.getOne`).
// Features: Useful for managing dependencies (like a DB connection) via the class constructor.

const fastify = require('fastify')({ logger: true });
const { randomUUID } = require('crypto');

// --- Mock Data Store ---
class UserDataStore {
    constructor() {
        this.users = new Map();
        this._seed();
    }

    _seed() {
        const user1Id = randomUUID();
        this.users.set(user1Id, {
            id: user1Id, email: 'admin@example.com', password_hash: 'abc', role: 'ADMIN', is_active: true, created_at: new Date().toISOString()
        });
        const user2Id = randomUUID();
        this.users.set(user2Id, {
            id: user2Id, email: 'user@example.com', password_hash: 'def', role: 'USER', is_active: false, created_at: new Date().toISOString()
        });
    }
}

// --- Controller Class ---
class UserController {
    constructor(dataStore) {
        this.db = dataStore;
    }

    // Helper to prevent leaking password hashes
    _sanitize(user) {
        const { password_hash, ...safeUser } = user;
        return safeUser;
    }

    async create(request, reply) {
        const { email, password, role } = request.body;
        
        for (const user of this.db.users.values()) {
            if (user.email === email) {
                return reply.code(409).send({ error: 'Email already in use' });
            }
        }

        const newUser = {
            id: randomUUID(),
            email,
            password_hash: `hashed_${password}`,
            role,
            is_active: request.body.is_active ?? true,
            created_at: new Date().toISOString()
        };
        this.db.users.set(newUser.id, newUser);
        return reply.code(201).send(this._sanitize(newUser));
    }

    async list(request, reply) {
        const { limit = 10, offset = 0, role, is_active } = request.query;
        
        let results = Array.from(this.db.users.values());

        if (role) results = results.filter(u => u.role === role);
        if (is_active !== undefined) results = results.filter(u => u.is_active === (String(is_active) === 'true'));

        const paginated = results.slice(offset, offset + limit);

        return {
            count: paginated.length,
            total: results.length,
            data: paginated.map(this._sanitize)
        };
    }

    async getOne(request, reply) {
        const user = this.db.users.get(request.params.id);
        if (!user) {
            return reply.code(404).send({ error: 'User not found' });
        }
        return this._sanitize(user);
    }

    async update(request, reply) {
        const user = this.db.users.get(request.params.id);
        if (!user) {
            return reply.code(404).send({ error: 'User not found' });
        }
        
        // Using PATCH semantics (partial update)
        const updatedUser = { ...user, ...request.body };
        this.db.users.set(request.params.id, updatedUser);
        
        return this._sanitize(updatedUser);
    }

    async remove(request, reply) {
        const success = this.db.users.delete(request.params.id);
        if (!success) {
            return reply.code(404).send({ error: 'User not found' });
        }
        return reply.code(204).send();
    }
}

// --- Server and Route Registration ---
const server = fastify;
const dataStore = new UserDataStore();
const userController = new UserController(dataStore);

const userSchemas = {
    params: { type: 'object', properties: { id: { type: 'string', format: 'uuid' } } },
    querystring: {
        type: 'object',
        properties: {
            limit: { type: 'integer' },
            offset: { type: 'integer' },
            role: { type: 'string' },
            is_active: { type: 'boolean' }
        }
    },
    createBody: {
        type: 'object',
        required: ['email', 'password', 'role'],
        properties: {
            email: { type: 'string', format: 'email' },
            password: { type: 'string' },
            role: { type: 'string', enum: ['ADMIN', 'USER'] },
            is_active: { type: 'boolean' }
        }
    },
    updateBody: {
        type: 'object',
        minProperties: 1,
        properties: {
            email: { type: 'string', format: 'email' },
            role: { type: 'string', enum: ['ADMIN', 'USER'] },
            is_active: { type: 'boolean' }
        }
    }
};

// Register routes by binding controller methods
server.post('/users', { schema: { body: userSchemas.createBody } }, userController.create.bind(userController));
server.get('/users', { schema: { querystring: userSchemas.querystring } }, userController.list.bind(userController));
server.get('/users/:id', { schema: { params: userSchemas.params } }, userController.getOne.bind(userController));
server.patch('/users/:id', { schema: { params: userSchemas.params, body: userSchemas.updateBody } }, userController.update.bind(userController));
server.delete('/users/:id', { schema: { params: userSchemas.params } }, userController.remove.bind(userController));

const startServer = async () => {
    try {
        await server.listen({ port: 3000 });
    } catch (err) {
        server.log.error(err);
        process.exit(1);
    }
};

if (require.main === module) {
    startServer();
}

module.exports = server;
</script>