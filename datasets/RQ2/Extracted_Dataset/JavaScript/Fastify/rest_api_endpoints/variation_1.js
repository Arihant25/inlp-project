<script>
// Variation 1: The Pragmatist - Functional & Inline
// Style: All logic is self-contained within the route handlers in a single file.
// Structure: A single script with Fastify instance, mock data, and routes defined sequentially.
// Naming: Standard camelCase (e.g., `getUserById`).
// Features: Uses Fastify's built-in JSON schema validation directly inline with the route definitions.

const fastify = require('fastify')({ logger: true });
const { randomUUID } = require('crypto');

// --- Mock Database ---
const mockDb = {
    users: new Map(),
};

// --- Seed Data ---
(() => {
    const userId1 = randomUUID();
    const userId2 = randomUUID();
    mockDb.users.set(userId1, {
        id: userId1,
        email: 'admin@example.com',
        password_hash: 'admin_hashed_pw',
        role: 'ADMIN',
        is_active: true,
        created_at: new Date().toISOString(),
    });
    mockDb.users.set(userId2, {
        id: userId2,
        email: 'user@example.com',
        password_hash: 'user_hashed_pw',
        role: 'USER',
        is_active: false,
        created_at: new Date().toISOString(),
    });
})();


// --- API Endpoints ---

// [POST] /users - Create a new user
fastify.post('/users', {
    schema: {
        body: {
            type: 'object',
            required: ['email', 'password', 'role'],
            properties: {
                email: { type: 'string', format: 'email' },
                password: { type: 'string', minLength: 8 },
                role: { type: 'string', enum: ['ADMIN', 'USER'] },
                is_active: { type: 'boolean', default: true }
            }
        },
        response: {
            201: {
                type: 'object',
                properties: {
                    id: { type: 'string', format: 'uuid' },
                    email: { type: 'string' },
                    role: { type: 'string' },
                    is_active: { type: 'boolean' },
                    created_at: { type: 'string', format: 'date-time' }
                }
            }
        }
    }
}, async (request, reply) => {
    const { email, password, role, is_active } = request.body;
    
    // Check for existing email
    for (const user of mockDb.users.values()) {
        if (user.email === email) {
            return reply.code(409).send({ message: 'User with this email already exists.' });
        }
    }

    const newUser = {
        id: randomUUID(),
        email,
        password_hash: `${password}_hashed`, // Mock hashing
        role,
        is_active,
        created_at: new Date().toISOString()
    };

    mockDb.users.set(newUser.id, newUser);
    
    // Exclude password_hash from response
    const { password_hash, ...userResponse } = newUser;
    return reply.code(201).send(userResponse);
});

// [GET] /users - List users with pagination and filtering
fastify.get('/users', {
    schema: {
        querystring: {
            type: 'object',
            properties: {
                limit: { type: 'integer', minimum: 1, default: 10 },
                offset: { type: 'integer', minimum: 0, default: 0 },
                role: { type: 'string', enum: ['ADMIN', 'USER'] },
                is_active: { type: 'boolean' }
            }
        }
    }
}, async (request, reply) => {
    const { limit, offset, role, is_active } = request.query;
    
    let allUsers = Array.from(mockDb.users.values());

    // Filtering
    if (role) {
        allUsers = allUsers.filter(user => user.role === role);
    }
    if (is_active !== undefined) {
        allUsers = allUsers.filter(user => user.is_active === is_active);
    }

    const total = allUsers.length;
    
    // Pagination
    const paginatedUsers = allUsers.slice(offset, offset + limit);

    // Exclude password_hash from response
    const responseData = paginatedUsers.map(({ password_hash, ...user }) => user);

    return {
        total,
        limit,
        offset,
        data: responseData
    };
});

// [GET] /users/:id - Get a single user by ID
fastify.get('/users/:id', {
    schema: {
        params: {
            type: 'object',
            properties: {
                id: { type: 'string', format: 'uuid' }
            }
        }
    }
}, async (request, reply) => {
    const { id } = request.params;
    const user = mockDb.users.get(id);

    if (!user) {
        return reply.code(404).send({ message: 'User not found' });
    }
    
    const { password_hash, ...userResponse } = user;
    return userResponse;
});

// [PATCH] /users/:id - Update a user
fastify.patch('/users/:id', {
    schema: {
        params: {
            type: 'object',
            properties: {
                id: { type: 'string', format: 'uuid' }
            }
        },
        body: {
            type: 'object',
            properties: {
                email: { type: 'string', format: 'email' },
                role: { type: 'string', enum: ['ADMIN', 'USER'] },
                is_active: { type: 'boolean' }
            },
            minProperties: 1
        }
    }
}, async (request, reply) => {
    const { id } = request.params;
    const user = mockDb.users.get(id);

    if (!user) {
        return reply.code(404).send({ message: 'User not found' });
    }

    const updatedUser = { ...user, ...request.body };
    mockDb.users.set(id, updatedUser);

    const { password_hash, ...userResponse } = updatedUser;
    return userResponse;
});

// [DELETE] /users/:id - Delete a user
fastify.delete('/users/:id', {
    schema: {
        params: {
            type: 'object',
            properties: {
                id: { type: 'string', format: 'uuid' }
            }
        }
    }
}, async (request, reply) => {
    const { id } = request.params;
    const deleted = mockDb.users.delete(id);

    if (!deleted) {
        return reply.code(404).send({ message: 'User not found' });
    }

    return reply.code(204).send();
});


// --- Server Start ---
const start = async () => {
    try {
        await fastify.listen({ port: 3000 });
    } catch (err) {
        fastify.log.error(err);
        process.exit(1);
    }
};

// To run this file: `node <filename>.js`
// This check prevents the server from starting when the file is imported for testing.
if (require.main === module) {
    start();
}

module.exports = fastify; // For testing
</script>