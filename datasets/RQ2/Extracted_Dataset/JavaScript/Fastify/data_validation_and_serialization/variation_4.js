<script>
// Variation 4: The "Minimalist & Modern" Async/Await Approach
// This style uses modern JS features like async/await IIFE for top-level await simulation.
// Schemas are defined inline with routes for maximum co-location. It's very concise.

// --- MOCK DEPENDENCIES ---
// To run this: npm install fastify ajv-formats fastify-xml-body fast-xml-parser uuid
import Fastify from 'fastify';
import addFormats from 'ajv-formats';
import fastifyXmlBody from 'fastify-xml-body';
import { XMLBuilder } from 'fast-xml-parser';
import { v4 as uuidv4 } from 'uuid';

// --- IN-MEMORY STORAGE ---
const users = new Map();
const posts = new Map();

// --- MAIN ASYNC IIFE (Immediately Invoked Function Expression) ---
(async () => {
    const fastify = Fastify({
        logger: { level: 'info' },
        ajv: {
            customOptions: { allErrors: true, coerceTypes: true },
            plugins: [
                addFormats,
                (ajv) => ajv.addKeyword({
                    keyword: 'password',
                    // Simple password strength check
                    validate: (_, data) => typeof data === 'string' && data.length > 7 && /\d/.test(data),
                    error: { message: 'password must be at least 8 characters and include a number' }
                })
            ]
        }
    });

    // --- PLUGINS ---
    await fastify.register(fastifyXmlBody);

    // --- CUSTOM ERROR HANDLER ---
    fastify.setErrorHandler((error, req, reply) => {
        if (error.validation) {
            return reply.status(400).send({
                error: 'Validation Failed',
                messages: error.validation.map(e => `${e.keyword} validation failed for ${e.instancePath || 'body'}: ${e.message}`)
            });
        }
        fastify.log.error(error);
        reply.status(500).send({ error: 'Something went wrong' });
    });

    // --- ROUTES ---

    // Create a User (JSON in, JSON out)
    fastify.post('/users', {
        schema: {
            description: 'Create a new user.',
            tags: ['user'],
            body: {
                type: 'object',
                required: ['email', 'password', 'phone'],
                properties: {
                    email: { type: 'string', format: 'email' },
                    password: { type: 'string', password: true },
                    phone: { type: 'string', description: 'E.164 format', pattern: '^\\+[1-9]\\d{1,14}$' },
                    role: { type: 'string', enum: ['ADMIN', 'USER'], default: 'USER' },
                    is_active: { type: 'boolean', default: true }
                }
            },
            response: {
                201: {
                    description: 'Successful response',
                    type: 'object',
                    properties: {
                        id: { type: 'string', format: 'uuid' },
                        email: { type: 'string', format: 'email' },
                        role: { type: 'string' },
                        is_active: { type: 'boolean' },
                        created_at: { type: 'string', format: 'date-time' }
                    }
                }
            }
        }
    }, async (request, reply) => {
        const { email, password, role, is_active } = request.body;
        const newUser = {
            id: uuidv4(),
            email,
            password_hash: `hashed_${password}`, // Never store plain text passwords
            role,
            is_active,
            created_at: new Date().toISOString()
        };
        users.set(newUser.id, newUser);
        reply.code(201);
        return newUser;
    });

    // Create a Post (XML in, JSON out)
    fastify.post('/posts/xml', {
        schema: {
            description: 'Create a post from XML data.',
            tags: ['post'],
            body: {
                type: 'object',
                properties: {
                    post: {
                        type: 'object',
                        required: ['user_id', 'title', 'content'],
                        properties: {
                            user_id: { type: 'string', format: 'uuid' },
                            title: { type: 'string' },
                            content: { type: 'string' }
                        }
                    }
                }
            }
        }
    }, async (request, reply) => {
        const { user_id, title, content } = request.body.post;
        if (!users.has(user_id)) {
            return reply.code(400).send({ error: `User with id ${user_id} not found.` });
        }
        const newPost = {
            id: uuidv4(),
            user_id,
            title,
            content,
            status: 'DRAFT'
        };
        posts.set(newPost.id, newPost);
        reply.code(201);
        return newPost;
    });

    // Get a User (JSON out, XML out)
    fastify.get('/users/:id/xml', {
        schema: {
            description: 'Get a single user by ID, formatted as XML.',
            tags: ['user'],
            params: {
                type: 'object',
                properties: { id: { type: 'string', format: 'uuid' } }
            }
        }
    }, async (request, reply) => {
        const user = users.get(request.params.id);
        if (!user) {
            return reply.code(404).send({ error: 'User not found' });
        }
        const xmlBuilder = new XMLBuilder({ format: true });
        const xml = xmlBuilder.build({ user });
        reply.header('Content-Type', 'application/xml; charset=utf-8').send(xml);
    });

    // --- START SERVER ---
    try {
        // Seed data for testing
        const mockUserId = 'a1b2c3d4-e5f6-7890-1234-567890abcdef';
        users.set(mockUserId, {
            id: mockUserId, email: 'test@example.com', password_hash: 'hashed',
            role: 'USER', is_active: true, created_at: new Date().toISOString(),
        });
        fastify.log.info(`Mock user created with ID: ${mockUserId}`);

        await fastify.listen({ port: 3003 });
    } catch (err) {
        fastify.log.error(err);
        process.exit(1);
    }
})();
</script>