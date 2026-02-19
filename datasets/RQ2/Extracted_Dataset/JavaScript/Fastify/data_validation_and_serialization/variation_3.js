<script>
// Variation 3: The "OOP" Class-based Approach
// Encapsulates routing logic within classes. Each class represents a resource.
// This pattern can be familiar to developers from class-based frameworks.

// --- MOCK DEPENDENCIES ---
// To run this: npm install fastify ajv-formats fastify-xml-body fast-xml-parser uuid
import Fastify from 'fastify';
import addFormats from 'ajv-formats';
import fastifyXmlBody from 'fastify-xml-body';
import { XMLBuilder } from 'fast-xml-parser';
import { v4 as uuidv4 } from 'uuid';

// --- MOCK DATABASE / SERVICE LAYER ---
class MockDB {
    constructor() {
        this.users = new Map();
        this.posts = new Map();
    }
}

class UserService {
    constructor(db) {
        this.db = db;
    }
    create(userData) {
        const password_hash = `hashed_${userData.password}`;
        const newUser = {
            id: uuidv4(),
            email: userData.email,
            password_hash,
            role: userData.role,
            is_active: userData.is_active,
            created_at: new Date().toISOString(),
        };
        this.db.users.set(newUser.id, newUser);
        return newUser;
    }
    findById(id) {
        return this.db.users.get(id);
    }
}

// --- ROUTER ABSTRACTION ---
class ResourceRouter {
    constructor(fastify, service) {
        this.fastify = fastify;
        this.service = service;
        this.registerRoutes();
    }
    registerRoutes() {
        throw new Error("Subclasses must implement registerRoutes()");
    }
}

// --- USER ROUTER IMPLEMENTATION ---
class UserRouter extends ResourceRouter {
    static schemas = {
        createBody: {
            type: 'object',
            required: ['email', 'password', 'phone'],
            properties: {
                email: { type: 'string', format: 'email' },
                password: { type: 'string', password: true },
                phone: { type: 'string', pattern: '^\\+[1-9]\\d{1,14}$' },
                role: { type: 'string', enum: ['ADMIN', 'USER'], default: 'USER' },
            },
        },
        response: {
            type: 'object',
            properties: {
                id: { type: 'string', format: 'uuid' },
                email: { type: 'string', format: 'email' },
                role: { type: 'string', enum: ['ADMIN', 'USER'] },
                is_active: { type: 'boolean' },
                created_at: { type: 'string', format: 'date-time' },
            }
        }
    };

    registerRoutes() {
        this.fastify.post('/users', {
            schema: {
                body: UserRouter.schemas.createBody,
                response: { 201: UserRouter.schemas.response }
            }
        }, this.createUser.bind(this));

        this.fastify.get('/users/:id/xml', {
            schema: {
                params: { type: 'object', properties: { id: { type: 'string', format: 'uuid' } } }
            }
        }, this.getUserAsXml.bind(this));
    }

    async createUser(request, reply) {
        const user = this.service.create(request.body);
        return reply.code(201).send(user);
    }

    async getUserAsXml(request, reply) {
        const user = this.service.findById(request.params.id);
        if (!user) {
            return reply.code(404).send({ error: 'Not Found' });
        }
        const xmlBuilder = new XMLBuilder({ format: true });
        const xml = xmlBuilder.build({ UserRecord: user });
        return reply.header('Content-Type', 'application/xml').send(xml);
    }
}

// --- SERVER CLASS ---
class AppServer {
    constructor() {
        this.app = Fastify({
            logger: true,
            ajv: {
                customOptions: { allErrors: true, coerceTypes: true },
                plugins: [addFormats, (ajv) => {
                    ajv.addKeyword({
                        keyword: 'password',
                        validate: (s, d) => typeof d === 'string' && d.length >= 8 && /\d/.test(d),
                        error: { message: 'password must meet complexity requirements' }
                    });
                }]
            }
        });
        this.db = new MockDB();
    }

    _setupPlugins() {
        this.app.register(fastifyXmlBody);
    }

    _setupErrorHandlers() {
        this.app.setErrorHandler((error, request, reply) => {
            if (error.validation) {
                reply.status(400).send({
                    error: 'Bad Request',
                    details: error.validation.map(v => v.message)
                });
            } else {
                this.app.log.error(error);
                reply.status(500).send({ error: 'Server Error' });
            }
        });
    }

    _registerRouters() {
        new UserRouter(this.app, new UserService(this.db));
        // In a real app, you would have a PostRouter here as well.
    }

    _seedData() {
        const mockUserId = 'a1b2c3d4-e5f6-7890-1234-567890abcdef';
        this.db.users.set(mockUserId, {
            id: mockUserId, email: 'test@example.com', password_hash: 'hashed',
            role: 'USER', is_active: true, created_at: new Date().toISOString(),
        });
        console.log(`Mock user created with ID: ${mockUserId}`);
    }

    async start() {
        this._setupPlugins();
        this._setupErrorHandlers();
        this._registerRouters();
        this._seedData();
        try {
            await this.app.listen({ port: 3002 });
        } catch (err) {
            this.app.log.error(err);
            process.exit(1);
        }
    }
}

// --- BOOTSTRAP ---
const server = new AppServer();
server.start();
</script>