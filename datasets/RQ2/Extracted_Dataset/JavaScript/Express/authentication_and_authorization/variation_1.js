<pre>
/*
Variation 1: The "Classic" Modular Approach
- Structure: Separated files for routes, controllers, services, and middleware.
- Style: Functional, using module.exports.
- Naming: Standard camelCase (e.g., authController, verifyToken).
- This is a very common and scalable pattern in the Node.js/Express ecosystem.
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

// --- Mock Database (db.js) ---
const db = {};
(async () => {
    const userPassword = await bcrypt.hash('password123', 10);
    const adminPassword = await bcrypt.hash('adminpass', 10);
    const userId = uuidv4();
    const adminId = uuidv4();

    db.users = [
        { id: userId, email: 'user@example.com', password_hash: userPassword, role: 'USER', is_active: true, created_at: new Date() },
        { id: adminId, email: 'admin@example.com', password_hash: adminPassword, role: 'ADMIN', is_active: true, created_at: new Date() }
    ];

    db.posts = [
        { id: uuidv4(), user_id: userId, title: 'User Post', content: 'This is a post by a regular user.', status: 'PUBLISHED' },
        { id: uuidv4(), user_id: adminId, title: 'Admin Draft', content: 'This is a draft by an admin.', status: 'DRAFT' }
    ];
})();

// --- Services (auth.service.js) ---
const authService = {
    findUserByEmail: (email) => {
        return db.users.find(user => user.email === email && user.is_active);
    },
    comparePasswords: async (plainPassword, hashedPassword) => {
        return await bcrypt.compare(plainPassword, hashedPassword);
    },
    generateJwt: (user) => {
        const payload = { id: user.id, role: user.role };
        return jwt.sign(payload, 'SECRET_KEY_1', { expiresIn: '1h' });
    }
};

// --- Middleware (auth.middleware.js) ---
const authMiddleware = {
    verifyToken: (req, res, next) => {
        const authHeader = req.headers['authorization'];
        const token = authHeader && authHeader.split(' ')[1];
        if (!token) return res.sendStatus(401);

        jwt.verify(token, 'SECRET_KEY_1', (err, user) => {
            if (err) return res.sendStatus(403);
            req.user = user;
            next();
        });
    },
    requireRole: (role) => {
        return (req, res, next) => {
            if (!req.user || (Array.isArray(role) ? !role.includes(req.user.role) : req.user.role !== role)) {
                return res.status(403).json({ message: 'Forbidden: Insufficient role' });
            }
            next();
        };
    }
};

// --- Controllers (auth.controller.js) ---
const authController = {
    login: async (req, res) => {
        const { email, password } = req.body;
        if (!email || !password) {
            return res.status(400).json({ message: 'Email and password are required.' });
        }
        const user = authService.findUserByEmail(email);
        if (!user) {
            return res.status(401).json({ message: 'Invalid credentials.' });
        }
        const isMatch = await authService.comparePasswords(password, user.password_hash);
        if (!isMatch) {
            return res.status(401).json({ message: 'Invalid credentials.' });
        }
        const token = authService.generateJwt(user);
        res.json({ accessToken: token });
    },
    getProfile: (req, res) => {
        const user = db.users.find(u => u.id === req.user.id);
        if (!user) return res.sendStatus(404);
        const { password_hash, ...profile } = user;
        res.json(profile);
    }
};

// --- Controllers (post.controller.js) ---
const postController = {
    getAllPosts: (req, res) => {
        res.json(db.posts);
    },
    createPost: (req, res) => {
        const { title, content } = req.body;
        const newPost = {
            id: uuidv4(),
            user_id: req.user.id,
            title,
            content,
            status: 'DRAFT'
        };
        db.posts.push(newPost);
        res.status(201).json(newPost);
    },
    deletePost: (req, res) => {
        const postId = req.params.id;
        const postIndex = db.posts.findIndex(p => p.id === postId);
        if (postIndex === -1) {
            return res.status(404).json({ message: 'Post not found' });
        }
        db.posts.splice(postIndex, 1);
        res.status(204).send();
    }
};

// --- Routes (auth.routes.js & post.routes.js) ---
const authRouter = express.Router();
authRouter.post('/login', authController.login);
authRouter.get('/google', passport.authenticate('google', { scope: ['profile', 'email'] }));
authRouter.get('/google/callback',
    passport.authenticate('google', { failureRedirect: '/login', session: false }),
    (req, res) => {
        // On successful OAuth, generate a JWT for our API
        const token = authService.generateJwt(req.user);
        res.json({ accessToken: token });
    }
);

const apiRouter = express.Router();
apiRouter.use(authMiddleware.verifyToken); // Protect all API routes
apiRouter.get('/profile', authController.getProfile);
apiRouter.get('/posts', postController.getAllPosts);
apiRouter.post('/posts', authMiddleware.requireRole(['USER', 'ADMIN']), postController.createPost);
apiRouter.delete('/posts/:id', authMiddleware.requireRole('ADMIN'), postController.deletePost);

// --- Main App (app.js) ---
const app = express();
const PORT = 3001;

// Core Middleware
app.use(express.json());
app.use(session({ secret: 'oauth_session_secret', resave: false, saveUninitialized: true }));

// Passport (OAuth2) Configuration
app.use(passport.initialize());
app.use(passport.session());

passport.serializeUser((user, done) => done(null, user.id));
passport.deserializeUser((id, done) => {
    const user = db.users.find(u => u.id === id);
    done(null, user);
});

passport.use(new GoogleStrategy({
    clientID: 'MOCK_GOOGLE_CLIENT_ID',
    clientSecret: 'MOCK_GOOGLE_CLIENT_SECRET',
    callbackURL: "/auth/google/callback"
}, (accessToken, refreshToken, profile, done) => {
    // Find or create user
    let user = db.users.find(u => u.email === profile.emails[0].value);
    if (!user) {
        // In a real app, you'd create a new user here.
        // For this mock, we'll just find the first user to simulate a login.
        user = db.users[0];
        console.log(`Mock OAuth: User not found, logging in as ${user.email}`);
    }
    return done(null, user);
}));

// Register Routers
app.use('/auth', authRouter);
app.use('/api', apiRouter);

app.get('/', (req, res) => res.send('Variation 1: Classic Modular Approach'));

app.listen(PORT, () => {
    console.log(`Variation 1 server running on http://localhost:${PORT}`);
});
</pre>