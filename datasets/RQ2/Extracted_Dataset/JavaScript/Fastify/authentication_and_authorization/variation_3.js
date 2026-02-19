/*
 * VARIATION 3: The "Monolithic/Functional" Developer
 *
 * STYLE:
 * - All code is contained within a single file and a single bootstrapping function.
 * - Prefers a direct, functional approach over complex structures or abstractions.
 * - Route handlers are often defined as inline arrow functions.
 * - Good for small services, prototypes, or developers who prefer seeing all logic in one place.
 *
 * STRUCTURE:
 * 1. All dependencies and mock data are declared at the top.
 * 2. A single `start` async function initializes everything.
 * 3. Inside `start`, plugins are registered sequentially.
 * 4. Auth hooks (`verifyJwtAndUser`, `ensureAdmin`) are defined as simple functions.
 * 5. All routes are defined directly on the `server` object with inline handlers.
 */
const fastify = require('fastify')({ logger: true });
const bcrypt = require('bcrypt');
const { v4: uuidv4 } = require('uuid');

// --- Package Dependencies ---
const JWT_SECRET = 'another-super-secure-secret-for-jwt-variation-3';
const SESSION_SECRET = 'another-super-secure-secret-for-session-variation-3';
const PORT = 3002;

// --- In-Memory Data Store ---
const dataStore = {
    users: {},
    posts: {},
};

const seedData = async () => {
    const adminId = uuidv4();
    const userId = uuidv4();
    dataStore.users[adminId] = {
        id: adminId,
        email: 'admin.mono@example.com',
        password_hash: await bcrypt.hash('!Password123', 12),
        role: 'ADMIN',
        is_active: true,
        created_at: new Date(),
    };
    dataStore.users[userId] = {
        id: userId,
        email: 'user.mono@example.com',
        password_hash: await bcrypt.hash('!Password456', 12),
        role: 'USER',
        is_active: true,
        created_at: new Date(),
    };
    const postId = uuidv4();
    dataStore.posts[postId] = {
        id: postId,
        user_id: userId,
        title: 'A Post in a Monolith',
        content: 'Some text content.',
        status: 'DRAFT',
    };
};

// --- Main Application Logic ---
const start = async () => {
    await seedData();

    // 1. Register Plugins
    fastify.register(require('@fastify/jwt'), { secret: JWT_SECRET });
    fastify.register(require('@fastify/cookie'));
    fastify.register(require('@fastify/session'), { secret: SESSION_SECRET, cookie: { secure: false } });
    fastify.register(require('@fastify/oauth2'), {
        name: 'facebookOAuth',
        scope: ['email'],
        credentials: {
            client: { id: 'fb-client-id', secret: 'fb-client-secret' },
            auth: require('@fastify/oauth2').FACEBOOK_CONFIGURATION,
        },
        startRedirectPath: '/auth/facebook',
        callbackUri: `http://localhost:${PORT}/auth/facebook/callback`,
    });

    // 2. Define Auth Hooks
    const verifyJwtAndUser = async (request, reply) => {
        try {
            await request.jwtVerify();
            const userExists = dataStore.users[request.user.id];
            if (!userExists || !userExists.is_active) {
                return reply.code(401).send({ message: 'Unauthorized: Invalid or inactive user.' });
            }
        } catch (err) {
            reply.code(401).send({ message: 'Unauthorized: Invalid token.' });
        }
    };

    const ensureAdmin = async (request, reply) => {
        if (request.user.role !== 'ADMIN') {
            return reply.code(403).send({ message: 'Forbidden: Admin role required.' });
        }
    };

    // 3. Define Routes
    // --- Auth Routes ---
    fastify.post('/login', async (request, reply) => {
        const { email, password } = request.body;
        const user = Object.values(dataStore.users).find(u => u.email === email);

        if (!user) {
            return reply.code(401).send({ message: 'Login failed.' });
        }

        const isPasswordCorrect = await bcrypt.compare(password, user.password_hash);
        if (!isPasswordCorrect) {
            return reply.code(401).send({ message: 'Login failed.' });
        }

        const token = fastify.jwt.sign({ id: user.id, role: user.role, email: user.email });
        return { access_token: token };
    });

    fastify.get('/auth/facebook/callback', async (request, reply) => {
        const { token } = await fastify.facebookOAuth.getAccessTokenFromAuthorizationCodeFlow(request);
        // Logic to handle user provisioning based on FB profile would go here.
        reply.send({ message: 'Facebook OAuth successful.', token: token.access_token });
    });

    // --- User Routes ---
    fastify.get('/me', { preHandler: [verifyJwtAndUser] }, (request, reply) => {
        const { password_hash, ...userProfile } = dataStore.users[request.user.id];
        reply.send(userProfile);
    });

    // --- Post Routes ---
    fastify.get('/posts', { preHandler: [verifyJwtAndUser] }, (request, reply) => {
        reply.send(Object.values(dataStore.posts));
    });

    fastify.put('/posts/:id/publish', { preHandler: [verifyJwtAndUser, ensureAdmin] }, (request, reply) => {
        const post = dataStore.posts[request.params.id];
        if (!post) {
            return reply.code(404).send({ message: 'Post not found.' });
        }
        post.status = 'PUBLISHED';
        reply.send(post);
    });

    // 4. Start Server
    try {
        await fastify.listen({ port: PORT });
    } catch (err) {
        fastify.log.error(err);
        process.exit(1);
    }
};

if (require.main === module) {
    start();
}