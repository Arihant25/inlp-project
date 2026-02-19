<pre>
/*
Variation 3: The "Functional & Composable Middleware" Approach
- Structure: Flatter, with a focus on small, reusable, and chainable middleware functions.
- Style: Highly functional, using higher-order functions to create configurable middleware.
- Naming: Descriptive functional names (e.g., createLoginHandler, requireRole).
- This pattern emphasizes composition over inheritance or large service objects.
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

// --- Mock Data Store ---
const createDataStore = async () => {
    const userPassword = await bcrypt.hash('password123', 10);
    const adminPassword = await bcrypt.hash('adminpass', 10);
    const userId = uuidv4();
    const adminId = uuidv4();

    const users = [
        { id: userId, email: 'user@example.com', password_hash: userPassword, role: 'USER', is_active: true, created_at: new Date() },
        { id: adminId, email: 'admin@example.com', password_hash: adminPassword, role: 'ADMIN', is_active: true, created_at: new Date() }
    ];
    const posts = [
        { id: uuidv4(), user_id: userId, title: 'User Post', content: 'This is a post by a regular user.', status: 'PUBLISHED' }
    ];
    
    return {
        users,
        posts,
        findUser: (prop, value) => users.find(u => u[prop] === value),
        deletePost: (id) => {
            const index = posts.findIndex(p => p.id === id);
            if (index > -1) posts.splice(index, 1);
            return index > -1;
        }
    };
};

// --- Core Auth Utilities ---
const JWT_SECRET = 'SECRET_KEY_3';
const signToken = (payload) => jwt.sign(payload, JWT_SECRET, { expiresIn: '1h' });
const verifyToken = (token) => {
    try {
        return { payload: jwt.verify(token, JWT_SECRET) };
    } catch (e) {
        return { error: e };
    }
};

// --- Composable Middleware ---
const attachDataStore = (dataStore) => (req, res, next) => {
    req.dataStore = dataStore;
    next();
};

const jwtAuthenticator = (req, res, next) => {
    const authHeader = req.headers['authorization'];
    const token = authHeader?.startsWith('Bearer ') ? authHeader.substring(7) : null;
    if (!token) return res.status(401).send('Access token is required.');

    const { payload, error } = verifyToken(token);
    if (error) return res.status(403).send('Invalid or expired token.');
    
    req.userContext = { id: payload.id, role: payload.role };
    next();
};

// Higher-order function for role checking
const requireRole = (...roles) => (req, res, next) => {
    const { role } = req.userContext || {};
    if (!role || !roles.includes(role)) {
        return res.status(403).send('Insufficient permissions.');
    }
    next();
};

// --- Route Handlers ---
const createLoginHandler = (req, res) => {
    const { email, password } = req.body;
    const user = req.dataStore.findUser('email', email);
    if (!user || !user.is_active) return res.status(401).send('Authentication failed.');

    bcrypt.compare(password, user.password_hash, (err, result) => {
        if (err || !result) return res.status(401).send('Authentication failed.');
        const token = signToken({ id: user.id, role: user.role });
        res.json({ jwt: token });
    });
};

const createOAuthCallbackHandler = (req, res) => {
    const token = signToken({ id: req.user.id, role: req.user.role });
    res.json({ message: "OAuth successful", jwt: token });
};

const getProfileHandler = (req, res) => {
    const user = req.dataStore.findUser('id', req.userContext.id);
    const { password_hash, ...profile } = user;
    res.json(profile);
};

const deletePostHandler = (req, res) => {
    const wasDeleted = req.dataStore.deletePost(req.params.id);
    if (wasDeleted) {
        res.status(204).end();
    } else {
        res.status(404).send('Post not found.');
    }
};

// --- App Initialization ---
const main = async () => {
    const app = express();
    const PORT = 3003;
    const dataStore = await createDataStore();

    app.use(express.json());
    app.use(session({ secret: 'oauth_session_secret_3', resave: false, saveUninitialized: true }));
    app.use(passport.initialize());
    app.use(passport.session());
    app.use(attachDataStore(dataStore));

    passport.use(new GoogleStrategy({
        clientID: 'MOCK_GOOGLE_CLIENT_ID_3',
        clientSecret: 'MOCK_GOOGLE_CLIENT_SECRET_3',
        callbackURL: "/auth/google/callback"
    }, (accessToken, refreshToken, profile, done) => {
        const user = dataStore.findUser('email', profile.emails[0].value) || dataStore.users[0];
        return done(null, user);
    }));
    passport.serializeUser((user, done) => done(null, user.id));
    passport.deserializeUser((id, done) => done(null, dataStore.findUser('id', id)));

    // --- Route Definitions ---
    app.post('/auth/login', createLoginHandler);
    app.get('/auth/google', passport.authenticate('google', { scope: ['profile', 'email'] }));
    app.get('/auth/google/callback', passport.authenticate('google', { session: false }), createOAuthCallbackHandler);

    app.get('/api/profile', jwtAuthenticator, getProfileHandler);
    app.delete('/api/posts/:id', jwtAuthenticator, requireRole('ADMIN'), deletePostHandler);
    
    app.get('/', (req, res) => res.send('Variation 3: Functional & Composable Middleware Approach'));

    app.listen(PORT, () => console.log(`Variation 3 server running on http://localhost:${PORT}`));
};

main();
</pre>