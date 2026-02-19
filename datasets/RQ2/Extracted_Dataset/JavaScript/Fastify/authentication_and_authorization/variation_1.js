/*
 * VARIATION 1: The "Classic Plugin" Developer
 *
 * STYLE:
 * - Organizes logic into distinct, reusable plugins (simulated as functions here).
 * - Follows the recommended Fastify pattern of composing the application from plugins.
 * - Clear separation of concerns: auth, routes, and server setup are distinct.
 * - Uses `fastify-plugin` (simulated) to decorate the server instance with shared utilities.
 *
 * STRUCTURE:
 * 1. Mock Database and Utilities.
 * 2. `authPlugin`: Handles JWT setup and decorates `fastify` with `authenticate` and `requireAdmin` hooks.
 * 3. `sessionAndOAuthPlugin`: Configures session, cookie, and OAuth2 providers.
 * 4. `apiRoutesPlugin`: Defines all application-specific routes for users and posts.
 * 5. `startServer`: The main function that composes all plugins and starts the server.
 */
const Fastify = require('fastify');
const fastifyJwt = require('@fastify/jwt');
const fastifyCookie = require('@fastify/cookie');
const fastifySession = require('@fastify/session');
const fastifyOAuth2 = require('@fastify/oauth2');
const bcrypt = require('bcrypt');
const { v4: uuidv4 } = require('uuid');

// --- Mock Database & Utilities ---
const SALT_ROUNDS = 10;
const mockDb = {
    users: new Map(),
    posts: new Map(),
};

async function initMockData() {
    const adminId = uuidv4();
    const userId = uuidv4();
    const adminPasswordHash = await bcrypt.hash('admin_password', SALT_ROUNDS);
    const userPasswordHash = await bcrypt.hash('user_password', SALT_ROUNDS);

    mockDb.users.set(adminId, {
        id: adminId,
        email: 'admin@example.com',
        password_hash: adminPasswordHash,
        role: 'ADMIN',
        is_active: true,
        created_at: new Date().toISOString(),
    });
    mockDb.users.set(userId, {
        id: userId,
        email: 'user@example.com',
        password_hash: userPasswordHash,
        role: 'USER',
        is_active: true,
        created_at: new Date().toISOString(),
    });

    const postId1 = uuidv4();
    mockDb.posts.set(postId1, {
        id: postId1,
        user_id: userId,
        title: 'User Post',
        content: 'This is a draft post by a regular user.',
        status: 'DRAFT',
    });
}

const findUserByEmail = async (email) => {
    for (const user of mockDb.users.values()) {
        if (user.email === email) {
            return user;
        }
    }
    return null;
};

// --- Plugin 1: Authentication ---
// This plugin sets up JWT and adds authentication-related decorators and hooks.
async function authPlugin(fastify, options) {
    fastify.register(fastifyJwt, {
        secret: options.secret,
    });

    // Decorator for JWT validation hook
    fastify.decorate('authenticate', async function (request, reply) {
        try {
            await request.jwtVerify();
        } catch (err) {
            reply.send(err);
        }
    });

    // Decorator for Role-Based Access Control (RBAC) hook
    fastify.decorate('requireAdmin', async function (request, reply) {
        if (request.user.role !== 'ADMIN') {
            reply.code(403).send({ error: 'Forbidden: Admin access required.' });
        }
    });
}

// --- Plugin 2: Session and OAuth ---
// This plugin configures session management and the OAuth2 client.
async function sessionAndOAuthPlugin(fastify, options) {
    fastify.register(fastifyCookie);
    fastify.register(fastifySession, {
        secret: options.sessionSecret,
        cookie: { secure: false }, // Set to true in production with HTTPS
        expires: 1800000,
    });

    fastify.register(fastifyOAuth2, {
        name: 'githubOAuth2',
        scope: ['user:email'],
        credentials: {
            client: {
                id: process.env.GITHUB_CLIENT_ID || 'your-github-client-id',
                secret: process.env.GITHUB_CLIENT_SECRET || 'your-github-client-secret',
            },
            auth: fastifyOAuth2.GITHUB_CONFIGURATION,
        },
        startRedirectPath: '/auth/github',
        callbackUri: `http://localhost:${options.port}/auth/github/callback`,
    });
}

// --- Plugin 3: API Routes ---
// This plugin defines all the core application routes.
async function apiRoutesPlugin(fastify, options) {
    // POST /login - User login with password
    fastify.post('/login', async (request, reply) => {
        const { email, password } = request.body;
        const user = await findUserByEmail(email);

        if (!user || !user.is_active) {
            return reply.code(401).send({ error: 'Invalid credentials or inactive user.' });
        }

        const match = await bcrypt.compare(password, user.password_hash);
        if (!match) {
            return reply.code(401).send({ error: 'Invalid credentials.' });
        }

        const token = fastify.jwt.sign({
            id: user.id,
            email: user.email,
            role: user.role,
        });

        reply.send({ token });
    });

    // GET /auth/github/callback - OAuth2 callback
    fastify.get('/auth/github/callback', async function (request, reply) {
        const { token } = await this.githubOAuth2.getAccessTokenFromAuthorizationCodeFlow(request);
        // In a real app, you would fetch the user's profile from GitHub,
        // find or create a user in your DB, and then generate a JWT for them.
        // For this example, we'll just return the OAuth token.
        reply.send({ github_access_token: token.access_token });
    });

    // GET /profile - Protected route to get user's own profile
    fastify.get('/profile', { preHandler: [fastify.authenticate] }, async (request, reply) => {
        const user = mockDb.users.get(request.user.id);
        if (!user) {
            return reply.code(404).send({ error: 'User not found.' });
        }
        const { password_hash, ...userProfile } = user;
        reply.send(userProfile);
    });

    // GET /posts - Protected route for all authenticated users
    fastify.get('/posts', { preHandler: [fastify.authenticate] }, async (request, reply) => {
        reply.send(Array.from(mockDb.posts.values()));
    });

    // PUT /posts/:id/publish - Admin-only route
    fastify.put('/posts/:id/publish', { preHandler: [fastify.authenticate, fastify.requireAdmin] }, async (request, reply) => {
        const post = mockDb.posts.get(request.params.id);
        if (!post) {
            return reply.code(404).send({ error: 'Post not found.' });
        }
        post.status = 'PUBLISHED';
        mockDb.posts.set(post.id, post);
        reply.send(post);
    });
}

// --- Server Start ---
const startServer = async () => {
    const server = Fastify({ logger: true });
    const PORT = 3000;

    await initMockData();

    // Register plugins
    server.register(sessionAndOAuthPlugin, {
        port: PORT,
        sessionSecret: 'a-very-long-and-secure-session-secret-string',
    });
    server.register(authPlugin, {
        secret: 'a-super-secret-jwt-key-that-is-long-and-secure',
    });
    server.register(apiRoutesPlugin);

    try {
        await server.listen({ port: PORT });
    } catch (err) {
        server.log.error(err);
        process.exit(1);
    }
};

// To run this file: `node <filename>.js`
if (require.main === module) {
    startServer();
}