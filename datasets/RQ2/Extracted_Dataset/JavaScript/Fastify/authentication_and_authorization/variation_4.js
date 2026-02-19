/*
 * VARIATION 4: The "Modern/Module-based" Developer
 *
 * STYLE:
 * - Uses a modular approach where features (auth, users, posts) are encapsulated in their
 *   own plugins (simulated as factory functions).
 * - Employs modern JavaScript patterns like arrow functions and a more declarative style.
 * - Uses a factory function for RBAC (`requireRole`) to make route definitions cleaner
 *   and more readable.
 *
 * STRUCTURE:
 * 1. Mock Data and Dependencies.
 * 2. `createAuthPlugin`: A factory that returns a Fastify plugin for JWT and authentication hooks.
 * 3. `createApiPlugin`: A factory that returns a plugin containing all business logic routes.
 *    - It includes a nested RBAC hook factory (`requireRole`) for elegant authorization.
 * 4. `main`: The entry point that composes the application by registering the plugins
 *    created by the factories.
 */
import Fastify from 'fastify';
import fastifyJwt from '@fastify/jwt';
import fastifyCookie from '@fastify/cookie';
import fastifySession from '@fastify/session';
import fastifyOAuth2 from '@fastify/oauth2';
import bcrypt from 'bcrypt';
import { v4 as uuidv4 } from 'uuid';

// --- In-Memory Database ---
const db = {
    users: new Map(),
    posts: new Map(),
};

const initializeDatabase = async () => {
    const adminId = uuidv4();
    const userId = uuidv4();
    db.users.set(adminId, {
        id: adminId,
        email: 'admin@esnext.dev',
        password_hash: await bcrypt.hash('complex_password_admin', 10),
        role: 'ADMIN',
        is_active: true,
        created_at: new Date(),
    });
    db.users.set(userId, {
        id: userId,
        email: 'user@esnext.dev',
        password_hash: await bcrypt.hash('complex_password_user', 10),
        role: 'USER',
        is_active: true,
        created_at: new Date(),
    });
    const postId = uuidv4();
    db.posts.set(postId, {
        id: postId,
        user_id: userId,
        title: 'ESM Style Post',
        content: 'This is the content.',
        status: 'DRAFT',
    });
};

// --- Auth Module/Plugin Factory ---
const createAuthPlugin = (secret) => async (fastify, opts) => {
    fastify.register(fastifyJwt, { secret });

    fastify.decorate('authenticate', async (request, reply) => {
        try {
            await request.jwtVerify();
        } catch (err) {
            reply.code(401).send({ error: 'Authentication required' });
        }
    });
};

// --- API Routes Module/Plugin Factory ---
const createApiPlugin = () => async (fastify, opts) => {
    // RBAC Hook Factory: Creates a preHandler hook for a specific role.
    const requireRole = (role) => async (request, reply) => {
        if (request.user.role !== role) {
            reply.code(403).send({ error: `Forbidden: requires ${role} role.` });
        }
    };

    // Login Route
    fastify.post('/login', async (request, reply) => {
        const { email, password } = request.body;
        const user = [...db.users.values()].find(u => u.email === email);

        if (!user || !(await bcrypt.compare(password, user.password_hash))) {
            return reply.code(401).send({ error: 'Invalid email or password.' });
        }

        const token = fastify.jwt.sign({ id: user.id, email: user.email, role: user.role });
        return { token };
    });

    // OAuth Callback Route
    fastify.get('/auth/github/callback', async (request, reply) => {
        const { token } = await fastify.githubOAuth.getAccessTokenFromAuthorizationCodeFlow(request);
        // Real-world: Use token to get user profile, find/create user, issue JWT
        return { message: 'OAuth flow completed.', provider_token: token };
    });

    // Protected User Profile Route
    fastify.get('/profile', { preHandler: [fastify.authenticate] }, async (request, reply) => {
        const user = db.users.get(request.user.id);
        const { password_hash, ...profile } = user;
        return profile;
    });

    // Protected Route for all users
    fastify.get('/posts', { preHandler: [fastify.authenticate] }, async (request, reply) => {
        return Array.from(db.posts.values());
    });

    // Admin-only Route using the RBAC factory
    fastify.patch('/posts/:id/status', {
        preHandler: [fastify.authenticate, requireRole('ADMIN')]
    }, async (request, reply) => {
        const { status } = request.body;
        const post = db.posts.get(request.params.id);
        if (!post) return reply.code(404).send({ error: 'Post not found.' });
        
        post.status = status; // e.g., 'PUBLISHED'
        return post;
    });
};

// --- Application Entry Point ---
const main = async () => {
    const server = Fastify({ logger: true });
    const PORT = 3003;

    await initializeDatabase();

    // Register foundational plugins
    server.register(fastifyCookie);
    server.register(fastifySession, {
        secret: 'a-long-and-random-secret-for-the-session-cookie',
        cookie: { secure: false },
    });
    server.register(fastifyOAuth2, {
        name: 'githubOAuth',
        scope: ['user'],
        credentials: {
            client: { id: 'gh-client-id', secret: 'gh-client-secret' },
            auth: fastifyOAuth2.GITHUB_CONFIGURATION,
        },
        startRedirectPath: '/auth/github',
        callbackUri: `http://localhost:${PORT}/auth/github/callback`,
    });

    // Register feature plugins from factories
    server.register(createAuthPlugin('my-super-strong-jwt-secret-from-modern-dev'));
    server.register(createApiPlugin());

    try {
        await server.listen({ port: PORT });
    } catch (err) {
        server.log.error(err);
        process.exit(1);
    }
};

// This structure assumes an ES Module context, but is runnable with CommonJS.
main();