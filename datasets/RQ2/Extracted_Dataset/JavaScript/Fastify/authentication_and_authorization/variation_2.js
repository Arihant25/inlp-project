/*
 * VARIATION 2: The "OOP/Class-based" Developer
 *
 * STYLE:
 * - Encapsulates logic within classes (e.g., AuthService, AuthController, PostController).
 * - Routes are defined as methods on controller classes.
 * - Promotes a structured, object-oriented approach, common in larger applications or teams
 *   with backgrounds in languages like Java or C#.
 *
 * STRUCTURE:
 * 1. Mock Database and Dependencies.
 * 2. `AuthService`: A class handling business logic for hashing and JWTs.
 * 3. `AuthController`: A class defining routes related to authentication (/login, /profile, /oauth).
 * 4. `PostController`: A class defining routes for posts, including protected ones.
 * 5. `bootstrap`: The main function that instantiates services and controllers,
 *    registers plugins, and wires up the routes.
 */
const Fastify = require('fastify');
const bcrypt = require('bcrypt');
const { v4: uuidv4 } = require('uuid');

// --- Mock Database ---
const mockDB = {
    users: new Map(),
    posts: new Map(),
};

async function setupMockData() {
    const adminId = uuidv4();
    const userId = uuidv4();
    const adminPasswordHash = await bcrypt.hash('adminPass123', 10);
    const userPasswordHash = await bcrypt.hash('userPass123', 10);

    mockDB.users.set(adminId, { id: adminId, email: 'admin@corp.com', password_hash: adminPasswordHash, role: 'ADMIN', is_active: true, created_at: new Date() });
    mockDB.users.set(userId, { id: userId, email: 'user@corp.com', password_hash: userPasswordHash, role: 'USER', is_active: true, created_at: new Date() });
    const postId = uuidv4();
    mockDB.posts.set(postId, { id: postId, user_id: userId, title: 'My First Post', content: 'Content here.', status: 'DRAFT' });
}

// --- Service Layer ---
class AuthService {
    constructor(jwt) {
        this.jwt = jwt;
    }

    async hashPassword(password) {
        return bcrypt.hash(password, 10);
    }

    async verifyPassword(password, hash) {
        return bcrypt.compare(password, hash);
    }

    generateToken(user) {
        return this.jwt.sign({
            id: user.id,
            email: user.email,
            role: user.role,
        });
    }
}

// --- Controller Layer ---
class AuthController {
    constructor(fastify, authService) {
        this.fastify = fastify;
        this.authService = authService;
    }

    async login(request, reply) {
        const { email, password } = request.body;
        const user = Array.from(mockDB.users.values()).find(u => u.email === email);

        if (!user || !user.is_active || !(await this.authService.verifyPassword(password, user.password_hash))) {
            return reply.code(401).send({ message: 'Authentication failed.' });
        }

        const token = this.authService.generateToken(user);
        return { token };
    }

    async getProfile(request, reply) {
        const user = mockDB.users.get(request.user.id);
        if (!user) {
            return reply.code(404).send({ message: 'User not found' });
        }
        const { password_hash, ...profile } = user;
        return profile;
    }

    async handleOAuthCallback(request, reply) {
        const { token } = await this.fastify.googleOAuth2.getAccessTokenFromAuthorizationCodeFlow(request);
        // In a real app, find/create user based on Google profile, then issue JWT
        return { message: "OAuth successful, user session would be created here.", access_token: token.access_token };
    }
}

class PostController {
    constructor(fastify) {
        this.fastify = fastify;
    }

    async getAllPosts(request, reply) {
        return Array.from(mockDB.posts.values());
    }

    async publishPost(request, reply) {
        const post = mockDB.posts.get(request.params.id);
        if (!post) {
            return reply.code(404).send({ message: 'Post not found' });
        }
        post.status = 'PUBLISHED';
        return post;
    }
}

// --- Main Application Bootstrap ---
async function bootstrap() {
    const fastify = Fastify({ logger: { level: 'info' } });
    const PORT = 3001;

    await setupMockData();

    // Register core plugins
    fastify.register(require('@fastify/jwt'), { secret: 'a-very-secret-and-secure-jwt-key-for-variation-2' });
    fastify.register(require('@fastify/cookie'));
    fastify.register(require('@fastify/session'), { secret: 'a-long-session-secret-key-for-variation-2', cookie: { secure: false } });
    fastify.register(require('@fastify/oauth2'), {
        name: 'googleOAuth2',
        scope: ['profile', 'email'],
        credentials: {
            client: { id: 'google-client-id', secret: 'google-client-secret' },
            auth: require('@fastify/oauth2').GOOGLE_CONFIGURATION,
        },
        startRedirectPath: '/auth/google',
        callbackUri: `http://localhost:${PORT}/auth/google/callback`,
    });

    // Define global auth hooks
    const authenticate = async (request, reply) => {
        try {
            await request.jwtVerify();
        } catch (err) {
            reply.send(err);
        }
    };
    const requireAdmin = async (request, reply) => {
        if (request.user.role !== 'ADMIN') {
            reply.code(403).send({ message: 'Administrator privileges required.' });
        }
    };

    // Instantiate services and controllers
    const authService = new AuthService(fastify.jwt);
    const authController = new AuthController(fastify, authService);
    const postController = new PostController(fastify);

    // Register routes
    fastify.post('/login', authController.login.bind(authController));
    fastify.get('/auth/google/callback', authController.handleOAuthCallback.bind(authController));
    fastify.get('/profile', { preHandler: [authenticate] }, authController.getProfile.bind(authController));
    fastify.get('/posts', { preHandler: [authenticate] }, postController.getAllPosts.bind(postController));
    fastify.put('/posts/:id/publish', { preHandler: [authenticate, requireAdmin] }, postController.publishPost.bind(postController));

    // Start server
    try {
        await fastify.listen({ port: PORT });
    } catch (err) {
        fastify.log.error(err);
        process.exit(1);
    }
}

if (require.main === module) {
    bootstrap();
}