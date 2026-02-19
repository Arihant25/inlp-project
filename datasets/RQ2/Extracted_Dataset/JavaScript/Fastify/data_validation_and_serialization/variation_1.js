<script>
// Variation 1: The "Classic" Functional Approach
// All logic (schemas, handlers, routes) is co-located in a single file.
// This style is common for smaller services or microservices.

// --- MOCK DEPENDENCIES ---
// To run this: npm install fastify ajv-formats fastify-xml-body fast-xml-parser uuid
import Fastify from 'fastify';
import addFormats from 'ajv-formats';
import fastifyXmlBody from 'fastify-xml-body';
import { XMLBuilder, XMLParser } from 'fast-xml-parser';
import { v4 as uuidv4 } from 'uuid';

// --- MOCK DATABASE ---
const mockUsers = new Map();
const mockPosts = new Map();

// --- FASTIFY SETUP ---
const server = Fastify({
    logger: true,
    ajv: {
        customOptions: {
            allErrors: true,
            coerceTypes: true, // Enable type coercion
        },
        plugins: [
            // Plugin to support formats like 'email', 'uuid', etc.
            addFormats,
            // Custom plugin for a password validator
            (ajv) => {
                ajv.addKeyword({
                    keyword: 'password',
                    validate: (schema, data) => {
                        // Simple password strength check: min 8 chars, 1 number, 1 letter
                        return typeof data === 'string' && data.length >= 8 && /\d/.test(data) && /[a-zA-Z]/.test(data);
                    },
                    error: {
                        message: 'password must be at least 8 characters long and contain at least one letter and one number',
                    },
                });
            }
        ]
    }
});

// Register plugins
server.register(fastifyXmlBody);

// --- DOMAIN SCHEMAS (for validation and serialization) ---
const userProperties = {
    id: { type: 'string', format: 'uuid' },
    email: { type: 'string', format: 'email' },
    role: { type: 'string', enum: ['ADMIN', 'USER'] },
    is_active: { type: 'boolean' },
    created_at: { type: 'string', format: 'date-time' },
};

const postProperties = {
    id: { type: 'string', format: 'uuid' },
    user_id: { type: 'string', format: 'uuid' },
    title: { type: 'string', minLength: 3 },
    content: { type: 'string' },
    status: { type: 'string', enum: ['DRAFT', 'PUBLISHED'] },
};

const createUserBodySchema = {
    type: 'object',
    required: ['email', 'password', 'phone'],
    properties: {
        email: { type: 'string', format: 'email' },
        password: { type: 'string', password: true }, // Using custom keyword
        phone: { type: 'string', pattern: '^\\+[1-9]\\d{1,14}$' }, // E.164 phone format
        role: { type: 'string', enum: ['ADMIN', 'USER'], default: 'USER' },
        is_active: { type: 'boolean', default: true }, // Type coercion will handle "true"
    },
};

const userResponseSchema = {
    type: 'object',
    properties: userProperties,
};

const createPostXmlSchema = {
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
};

// --- ROUTE HANDLERS ---
const createUserHandler = async (request, reply) => {
    const { email, password, role, is_active } = request.body;
    // In a real app, you'd hash the password
    const password_hash = `hashed_${password}`;
    const newUser = {
        id: uuidv4(),
        email,
        password_hash,
        role,
        is_active,
        created_at: new Date().toISOString(),
    };
    mockUsers.set(newUser.id, newUser);
    reply.code(201);
    return newUser;
};

const getUserXmlHandler = async (request, reply) => {
    const { id } = request.params;
    const user = mockUsers.get(id);
    if (!user) {
        return reply.code(404).send({ error: 'User not found' });
    }
    const xmlBuilder = new XMLBuilder({ format: true });
    const xmlData = xmlBuilder.build({ user: user });

    reply.header('Content-Type', 'application/xml; charset=utf-8').send(xmlData);
};

const createPostFromXmlHandler = async (request, reply) => {
    // fastify-xml-body has already parsed request.body into JSON
    const { user_id, title, content, status } = request.body.post;

    if (!mockUsers.has(user_id)) {
        return reply.code(400).send({ error: 'User ID does not exist' });
    }

    const newPost = {
        id: uuidv4(),
        user_id,
        title,
        content,
        status,
    };
    mockPosts.set(newPost.id, newPost);
    reply.code(201);
    return newPost;
};

// --- CUSTOM ERROR FORMATTING ---
server.setErrorHandler((error, request, reply) => {
    if (error.validation) {
        reply.status(400).send({
            statusCode: 400,
            error: 'Bad Request',
            messages: error.validation.map(err => `${err.instancePath.substring(1) || 'body'}: ${err.message}`),
        });
    } else {
        server.log.error(error);
        reply.status(500).send({ error: 'Internal Server Error' });
    }
});

// --- ROUTES ---
server.post('/users', {
    schema: {
        body: createUserBodySchema,
        response: { 201: userResponseSchema },
    },
}, createUserHandler);

server.get('/users/:id/xml', {
    schema: {
        params: {
            type: 'object',
            properties: { id: { type: 'string', format: 'uuid' } }
        }
    }
}, getUserXmlHandler);

server.post('/posts/xml', {
    schema: {
        body: createPostXmlSchema,
        response: { 201: { type: 'object', properties: postProperties } }
    }
}, createPostFromXmlHandler);


// --- SERVER START ---
const start = async () => {
    try {
        // Add a mock user for testing the post endpoint
        const mockUserId = 'a1b2c3d4-e5f6-7890-1234-567890abcdef';
        mockUsers.set(mockUserId, {
            id: mockUserId,
            email: 'test@example.com',
            password_hash: 'hashed_password123',
            role: 'USER',
            is_active: true,
            created_at: new Date().toISOString(),
        });
        console.log(`Mock user created with ID: ${mockUserId}`);

        await server.listen({ port: 3000 });
    } catch (err) {
        server.log.error(err);
        process.exit(1);
    }
};

start();
</script>