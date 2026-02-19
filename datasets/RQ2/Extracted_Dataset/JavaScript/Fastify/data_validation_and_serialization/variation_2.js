<script>
// Variation 2: The "Modular" Service-Oriented Approach
// Logic is separated into different concerns (routes, controllers, services, schemas).
// This is a common pattern for larger, more maintainable applications.

// --- MOCK DEPENDENCIES ---
// To run this: npm install fastify ajv-formats fastify-xml-body fast-xml-parser uuid
import Fastify from 'fastify';
import addFormats from 'ajv-formats';
import fastifyXmlBody from 'fastify-xml-body';
import { XMLBuilder } from 'fast-xml-parser';
import { v4 as uuidv4 } from 'uuid';

// --- MOCK DATABASE ---
const db = {
    users: new Map(),
    posts: new Map(),
};

// --- SCHEMAS MODULE ---
const Schemas = {
    User: {
        properties: {
            id: { type: 'string', format: 'uuid' },
            email: { type: 'string', format: 'email' },
            role: { type: 'string', enum: ['ADMIN', 'USER'] },
            is_active: { type: 'boolean' },
            created_at: { type: 'string', format: 'date-time' },
        },
        response: {
            type: 'object',
            properties: { /* populated below */ }
        },
        create: {
            type: 'object',
            required: ['email', 'password', 'phone'],
            properties: {
                email: { type: 'string', format: 'email' },
                password: { type: 'string', password: true },
                phone: { type: 'string', pattern: '^\\+[1-9]\\d{1,14}$' },
                role: { type: 'string', enum: ['ADMIN', 'USER'], default: 'USER' },
                is_active: { type: 'boolean', default: true },
            },
        },
    },
    Post: {
        properties: {
            id: { type: 'string', format: 'uuid' },
            user_id: { type: 'string', format: 'uuid' },
            title: { type: 'string', minLength: 3 },
            content: { type: 'string' },
            status: { type: 'string', enum: ['DRAFT', 'PUBLISHED'] },
        },
        response: {
            type: 'object',
            properties: { /* populated below */ }
        },
        createFromXml: {
            type: 'object',
            required: ['post'],
            properties: {
                post: {
                    type: 'object',
                    required: ['user_id', 'title', 'content'],
                    properties: {
                        user_id: { type: 'string', format: 'uuid' },
                        title: { type: 'string' },
                        content: { type: 'string' },
                        status: { type: 'string', enum: ['DRAFT', 'PUBLISHED'], default: 'DRAFT' },
                    }
                }
            }
        }
    }
};
Schemas.User.response.properties = Schemas.User.properties;
Schemas.Post.response.properties = Schemas.Post.properties;


// --- SERVICES MODULE (handles business logic) ---
const UserService = {
    async create(userData) {
        const password_hash = `hashed_${userData.password}`;
        const newUser = {
            id: uuidv4(),
            email: userData.email,
            password_hash,
            role: userData.role,
            is_active: userData.is_active,
            created_at: new Date().toISOString(),
        };
        db.users.set(newUser.id, newUser);
        return newUser;
    },
    async findById(id) {
        return db.users.get(id);
    }
};

const PostService = {
    async create(postData) {
        if (!db.users.has(postData.user_id)) {
            const err = new Error('User not found');
            err.statusCode = 400;
            throw err;
        }
        const newPost = { id: uuidv4(), ...postData };
        db.posts.set(newPost.id, newPost);
        return newPost;
    }
};

// --- CONTROLLERS MODULE (handles request/reply) ---
const UserController = {
    async createUser(request, reply) {
        const newUser = await UserService.create(request.body);
        reply.code(201);
        return newUser;
    },
    async getUserAsXml(request, reply) {
        const user = await UserService.findById(request.params.id);
        if (!user) {
            return reply.code(404).send({ error: 'User not found' });
        }
        const xmlBuilder = new XMLBuilder({ format: true });
        const xmlData = xmlBuilder.build({ user });
        reply.header('Content-Type', 'application/xml; charset=utf-8').send(xmlData);
    }
};

const PostController = {
    async createPostFromXml(request, reply) {
        const newPost = await PostService.create(request.body.post);
        reply.code(201);
        return newPost;
    }
};

// --- ROUTES MODULE (Fastify Plugin) ---
async function apiRoutes(fastify, options) {
    fastify.post('/users', {
        schema: { body: Schemas.User.create, response: { 201: Schemas.User.response } }
    }, UserController.createUser);

    fastify.get('/users/:id/xml', {
        schema: { params: { type: 'object', properties: { id: { type: 'string', format: 'uuid' } } } }
    }, UserController.getUserAsXml);

    fastify.post('/posts/xml', {
        schema: { body: Schemas.Post.createFromXml, response: { 201: Schemas.Post.response } }
    }, PostController.createPostFromXml);
}

// --- SERVER FACTORY ---
function buildServer() {
    const server = Fastify({
        logger: true,
        ajv: {
            customOptions: { allErrors: true, coerceTypes: true },
            plugins: [addFormats, (ajv) => {
                ajv.addKeyword({
                    keyword: 'password',
                    validate: (s, d) => typeof d === 'string' && d.length >= 8 && /\d/.test(d) && /[a-zA-Z]/.test(d),
                    error: { message: 'password must be strong' }
                });
            }]
        }
    });

    server.register(fastifyXmlBody);
    server.register(apiRoutes);

    server.setErrorHandler((error, request, reply) => {
        if (error.validation) {
            return reply.status(400).send({
                statusCode: 400,
                error: 'Validation Error',
                messages: error.validation.map(e => e.message),
            });
        }
        fastify.log.error(error);
        reply.status(error.statusCode || 500).send({ error: error.message || 'Internal Server Error' });
    });

    return server;
}

// --- MAIN EXECUTION ---
async function start() {
    const server = buildServer();
    try {
        // Add a mock user for testing
        const mockUserId = 'a1b2c3d4-e5f6-7890-1234-567890abcdef';
        db.users.set(mockUserId, {
            id: mockUserId, email: 'test@example.com', password_hash: 'hashed',
            role: 'USER', is_active: true, created_at: new Date().toISOString(),
        });
        console.log(`Mock user created with ID: ${mockUserId}`);

        await server.listen({ port: 3001 });
    } catch (err) {
        server.log.error(err);
        process.exit(1);
    }
}

start();
</script>