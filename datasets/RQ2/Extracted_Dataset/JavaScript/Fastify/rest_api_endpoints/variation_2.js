<script>
// Variation 2: "The Architect" - Service Layer & Encapsulation
// Style: Separates business logic (Service) from routing (Controller/Routes).
// Structure: Simulates a multi-file structure using comments, with logic encapsulated in a `userService` object and routes registered as a Fastify plugin.
// Naming: More descriptive names (e.g., `findUserById`).
// Features: Promotes code reuse and testability by isolating the data access layer.

const fastify = require('fastify');
const fp = require('fastify-plugin');
const { randomUUID } = require('crypto');

// --- (Simulated File: db/mockDb.js) ---
const db = {
    users: new Map(),
};
(() => {
    const userId1 = randomUUID();
    db.users.set(userId1, {
        id: userId1,
        email: 'admin@example.com',
        password_hash: 'admin_hashed_pw',
        role: 'ADMIN',
        is_active: true,
        created_at: new Date().toISOString(),
    });
    const userId2 = randomUUID();
    db.users.set(userId2, {
        id: userId2,
        email: 'user@example.com',
        password_hash: 'user_hashed_pw',
        role: 'USER',
        is_active: true,
        created_at: new Date().toISOString(),
    });
})();


// --- (Simulated File: services/userService.js) ---
const userService = {
    async createUser(userData) {
        const { email, password, role, is_active = true } = userData;
        
        for (const user of db.users.values()) {
            if (user.email === email) {
                const error = new Error('User with this email already exists.');
                error.statusCode = 409;
                throw error;
            }
        }

        const newUser = {
            id: randomUUID(),
            email,
            password_hash: `${password}_hashed`,
            role,
            is_active,
            created_at: new Date().toISOString(),
        };
        db.users.set(newUser.id, newUser);
        return newUser;
    },

    async findUserById(id) {
        return db.users.get(id);
    },

    async listAllUsers({ limit = 10, offset = 0, role, is_active }) {
        let allUsers = Array.from(db.users.values());

        if (role) allUsers = allUsers.filter(u => u.role === role);
        if (is_active !== undefined) allUsers = allUsers.filter(u => u.is_active === is_active);
        
        const total = allUsers.length;
        const data = allUsers.slice(offset, offset + limit);

        return { total, data };
    },

    async updateUser(id, updateData) {
        const user = db.users.get(id);
        if (!user) return null;

        const updatedUser = { ...user, ...updateData };
        db.users.set(id, updatedUser);
        return updatedUser;
    },

    async deleteUser(id) {
        return db.users.delete(id);
    }
};

// --- (Simulated File: schemas/userSchemas.js) ---
const userSchemas = {
    userCore: {
        type: 'object',
        properties: {
            id: { type: 'string', format: 'uuid' },
            email: { type: 'string', format: 'email' },
            role: { type: 'string', enum: ['ADMIN', 'USER'] },
            is_active: { type: 'boolean' },
            created_at: { type: 'string', format: 'date-time' }
        }
    },
    uuidParams: {
        type: 'object',
        properties: { id: { type: 'string', format: 'uuid' } }
    }
};


// --- (Simulated File: routes/userRoutes.js) ---
async function userRoutes(fastify, options) {
    // Helper to remove password hash from user object(s)
    const sanitizeUser = (user) => {
        if (!user) return null;
        const { password_hash, ...sanitized } = user;
        return sanitized;
    };

    fastify.post('/users', {
        schema: {
            body: {
                type: 'object',
                required: ['email', 'password', 'role'],
                properties: {
                    email: { type: 'string', format: 'email' },
                    password: { type: 'string', minLength: 8 },
                    role: { type: 'string', enum: ['ADMIN', 'USER'] },
                    is_active: { type: 'boolean' }
                }
            },
            response: { 201: userSchemas.userCore }
        }
    }, async (request, reply) => {
        const newUser = await userService.createUser(request.body);
        return reply.code(201).send(sanitizeUser(newUser));
    });

    fastify.get('/users', {
        schema: {
            querystring: {
                type: 'object',
                properties: {
                    limit: { type: 'integer', default: 10 },
                    offset: { type: 'integer', default: 0 },
                    role: { type: 'string', enum: ['ADMIN', 'USER'] },
                    is_active: { type: 'boolean' }
                }
            }
        }
    }, async (request, reply) => {
        const { total, data } = await userService.listAllUsers(request.query);
        return {
            total,
            limit: request.query.limit,
            offset: request.query.offset,
            data: data.map(sanitizeUser)
        };
    });

    fastify.get('/users/:id', { schema: { params: userSchemas.uuidParams } }, async (request, reply) => {
        const user = await userService.findUserById(request.params.id);
        if (!user) return reply.code(404).send({ message: 'User not found' });
        return sanitizeUser(user);
    });

    fastify.put('/users/:id', {
        schema: {
            params: userSchemas.uuidParams,
            body: {
                type: 'object',
                required: ['email', 'role', 'is_active'],
                properties: {
                    email: { type: 'string', format: 'email' },
                    role: { type: 'string', enum: ['ADMIN', 'USER'] },
                    is_active: { type: 'boolean' }
                }
            }
        }
    }, async (request, reply) => {
        const updatedUser = await userService.updateUser(request.params.id, request.body);
        if (!updatedUser) return reply.code(404).send({ message: 'User not found' });
        return sanitizeUser(updatedUser);
    });

    fastify.delete('/users/:id', { schema: { params: userSchemas.uuidParams } }, async (request, reply) => {
        const wasDeleted = await userService.deleteUser(request.params.id);
        if (!wasDeleted) return reply.code(404).send({ message: 'User not found' });
        return reply.code(204).send();
    });
}

// --- (Simulated File: server.js) ---
const server = fastify({ logger: true });

// Decorate fastify instance with the service for easy access
server.decorate('userService', userService);

// Register routes as a plugin
server.register(fp(userRoutes));

// Add a generic error handler
server.setErrorHandler(function (error, request, reply) {
    if (error.statusCode) {
        reply.status(error.statusCode).send({ message: error.message });
    } else {
        // Log unknown errors
        this.log.error(error);
        reply.status(500).send({ message: 'An internal server error occurred' });
    }
});

const start = async () => {
    try {
        await server.listen({ port: 3000 });
    } catch (err) {
        server.log.error(err);
        process.exit(1);
    }
};

if (require.main === module) {
    start();
}

module.exports = server;
</script>