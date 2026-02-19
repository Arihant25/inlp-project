<pre>
/*
Variation 2: The "OOP / Class-based" Approach
- Structure: Logic is encapsulated within ES6 classes (e.g., AuthService, UserController).
- Style: Object-Oriented, using classes and static/instance methods.
- Naming: PascalCase for classes, camelCase for methods.
- This approach appeals to developers from backgrounds like Java or C#.
*/

// --- MOCK package.json dependencies ---
/*
{
  "dependencies": {
    "express": "^4.18.2",
    "bcrypt": "^5.1.0",
    "jsonwebtoken": "^9.0.0",
    "express-session": "^1.17.3",
    "passport": "^0.6.0",
    "passport-google-oauth20": "^2.0.0",
    "uuid": "^9.0.0"
  }
}
*/

const express = require('express');
const bcrypt = require('bcrypt');
const jwt = require('jsonwebtoken');
const { v4: uuidv4 } = require('uuid');
const session = require('express-session');
const passport = require('passport');
const GoogleStrategy = require('passport-google-oauth20').Strategy;

// --- Mock Database Class ---
class MockDatabase {
    constructor() {
        this.users = [];
        this.posts = [];
    }

    async initialize() {
        const userPassword = await bcrypt.hash('password123', 10);
        const adminPassword = await bcrypt.hash('adminpass', 10);
        const userId = uuidv4();
        const adminId = uuidv4();

        this.users.push(
            { id: userId, email: 'user@example.com', password_hash: userPassword, role: 'USER', is_active: true, created_at: new Date() },
            { id: adminId, email: 'admin@example.com', password_hash: adminPassword, role: 'ADMIN', is_active: true, created_at: new Date() }
        );

        this.posts.push(
            { id: uuidv4(), user_id: userId, title: 'User Post', content: 'This is a post by a regular user.', status: 'PUBLISHED' },
            { id: uuidv4(), user_id: adminId, title: 'Admin Draft', content: 'This is a draft by an admin.', status: 'DRAFT' }
        );
    }

    findUserByEmail(email) {
        return this.users.find(user => user.email === email && user.is_active);
    }
    
    findUserById(id) {
        return this.users.find(user => user.id === id);
    }
}
const db = new MockDatabase();
db.initialize();

// --- Service Class ---
class AuthService {
    static JWT_SECRET = 'SECRET_KEY_2';

    static async comparePasswords(plain, hashed) {
        return bcrypt.compare(plain, hashed);
    }

    static generateToken(user) {
        const payload = { sub: user.id, role: user.role };
        return jwt.sign(payload, this.JWT_SECRET, { expiresIn: '1h' });
    }
}

// --- Middleware Class ---
class AuthMiddleware {
    static authenticate(req, res, next) {
        const authHeader = req.headers.authorization;
        if (!authHeader || !authHeader.startsWith('Bearer ')) {
            return res.status(401).json({ error: 'Unauthorized: No token provided' });
        }
        const token = authHeader.substring(7);
        try {
            const decoded = jwt.verify(token, AuthService.JWT_SECRET);
            req.auth = { userId: decoded.sub, userRole: decoded.role };
            next();
        } catch (error) {
            return res.status(403).json({ error: 'Forbidden: Invalid token' });
        }
    }

    static authorize(requiredRole) {
        return (req, res, next) => {
            if (!req.auth || req.auth.userRole !== requiredRole) {
                return res.status(403).json({ error: 'Forbidden: Insufficient permissions' });
            }
            next();
        };
    }
}

// --- Controller Class ---
class AppController {
    constructor(database) {
        this.db = database;
        // Bind methods to ensure 'this' context is correct when used as route handlers
        this.login = this.login.bind(this);
        this.getProfile = this.getProfile.bind(this);
        this.deletePost = this.deletePost.bind(this);
    }

    async login(req, res) {
        const { email, password } = req.body;
        const user = this.db.findUserByEmail(email);
        if (!user || !(await AuthService.comparePasswords(password, user.password_hash))) {
            return res.status(401).json({ error: 'Authentication failed' });
        }
        const token = AuthService.generateToken(user);
        res.json({ token });
    }

    getProfile(req, res) {
        const user = this.db.findUserById(req.auth.userId);
        if (!user) return res.status(404).json({ error: 'User not found' });
        const { password_hash, ...profileData } = user;
        res.json(profileData);
    }
    
    deletePost(req, res) {
        const postId = req.params.id;
        const initialLength = this.db.posts.length;
        this.db.posts = this.db.posts.filter(p => p.id !== postId);
        if (this.db.posts.length === initialLength) {
            return res.status(404).json({ message: 'Post not found' });
        }
        res.status(204).send();
    }
}

// --- Server Setup ---
class Server {
    constructor() {
        this.app = express();
        this.port = 3002;
        this.appController = new AppController(db);
        this.configureMiddleware();
        this.configurePassport();
        this.configureRoutes();
    }

    configureMiddleware() {
        this.app.use(express.json());
        this.app.use(session({ secret: 'oauth_session_secret_2', resave: false, saveUninitialized: true }));
    }

    configurePassport() {
        this.app.use(passport.initialize());
        this.app.use(passport.session());
        passport.use(new GoogleStrategy({
            clientID: 'MOCK_GOOGLE_CLIENT_ID_2',
            clientSecret: 'MOCK_GOOGLE_CLIENT_SECRET_2',
            callbackURL: "/auth/google/callback"
        }, (accessToken, refreshToken, profile, done) => {
            const user = db.findUserByEmail(profile.emails[0].value) || db.users[0];
            return done(null, user);
        }));
        passport.serializeUser((user, done) => done(null, user.id));
        passport.deserializeUser((id, done) => done(null, db.findUserById(id)));
    }

    configureRoutes() {
        const router = express.Router();
        
        // Auth Routes
        router.post('/auth/login', this.appController.login);
        router.get('/auth/google', passport.authenticate('google', { scope: ['profile', 'email'] }));
        router.get('/auth/google/callback',
            passport.authenticate('google', { session: false }),
            (req, res) => {
                const token = AuthService.generateToken(req.user);
                res.json({ token });
            }
        );

        // API Routes
        router.get('/api/profile', AuthMiddleware.authenticate, this.appController.getProfile);
        router.delete('/api/posts/:id', AuthMiddleware.authenticate, AuthMiddleware.authorize('ADMIN'), this.appController.deletePost);
        
        this.app.use(router);
        this.app.get('/', (req, res) => res.send('Variation 2: OOP / Class-based Approach'));
    }

    start() {
        this.app.listen(this.port, () => {
            console.log(`Variation 2 server running on http://localhost:${this.port}`);
        });
    }
}

const server = new Server();
server.start();
</pre>