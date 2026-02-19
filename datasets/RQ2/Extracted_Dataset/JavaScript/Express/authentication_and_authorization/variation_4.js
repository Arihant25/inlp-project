<pre>
/*
Variation 4: The "All-in-One / Monolithic Router" Approach
- Structure: A single file contains all logic, including data, middleware, and routes.
- Style: Procedural, with logic often defined directly in route handler callbacks.
- Naming: Direct and less abstract (e.g., checkAdmin, validateJwt).
- This approach is common in smaller projects, tutorials, or for rapid prototyping.
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

const app = express();
const PORT = 3004;
const JWT_SUPER_SECRET = 'a_very_obvious_secret_key_4';

// --- In-memory Data Store ---
let MOCK_USERS = [];
let MOCK_POSTS = [];

// --- Initialization Block ---
(async () => {
    const userPassHash = await bcrypt.hash('password123', 10);
    const adminPassHash = await bcrypt.hash('adminpass', 10);
    const userId = uuidv4();
    const adminId = uuidv4();

    MOCK_USERS = [
        { id: userId, email: 'user@example.com', password_hash: userPassHash, role: 'USER', is_active: true, created_at: new Date() },
        { id: adminId, email: 'admin@example.com', password_hash: adminPassHash, role: 'ADMIN', is_active: true, created_at: new Date() }
    ];
    MOCK_POSTS = [
        { id: uuidv4(), user_id: userId, title: 'User Post', content: 'Content here.', status: 'PUBLISHED' },
        { id: uuidv4(), user_id: adminId, title: 'Admin Post', content: 'Admin content.', status: 'PUBLISHED' }
    ];
    console.log('Mock data initialized.');
})();

// --- Middleware Setup ---
app.use(express.json());
app.use(session({ secret: 'some_session_secret_4', resave: false, saveUninitialized: true }));
app.use(passport.initialize());
app.use(passport.session());

// --- Helper Functions & Middleware Definitions ---
const validateJwt = (req, res, next) => {
    const token = req.headers['x-access-token'];
    if (!token) return res.status(401).json({ message: "No token provided." });

    jwt.verify(token, JWT_SUPER_SECRET, (err, decoded) => {
        if (err) return res.status(403).json({ message: "Failed to authenticate token." });
        req.userId = decoded.id;
        req.userRole = decoded.role;
        next();
    });
};

const checkAdmin = (req, res, next) => {
    if (req.userRole !== 'ADMIN') {
        return res.status(403).json({ message: "Requires ADMIN role." });
    }
    next();
};

// --- Passport (OAuth2) Configuration ---
passport.use(new GoogleStrategy({
    clientID: 'MOCK_GOOGLE_CLIENT_ID_4',
    clientSecret: 'MOCK_GOOGLE_CLIENT_SECRET_4',
    callbackURL: "/auth/google/callback"
}, (accessToken, refreshToken, profile, done) => {
    const user = MOCK_USERS.find(u => u.email === profile.emails[0].value) || MOCK_USERS[0];
    return done(null, user);
}));
passport.serializeUser((user, done) => done(null, user.id));
passport.deserializeUser((id, done) => {
    const user = MOCK_USERS.find(u => u.id === id);
    done(null, user);
});

// --- Route Definitions ---
app.get('/', (req, res) => {
    res.send('Variation 4: All-in-One / Monolithic Router Approach');
});

// --- Authentication Routes ---
app.post('/login', async (req, res) => {
    const user = MOCK_USERS.find(u => u.email === req.body.email);
    if (!user) return res.status(404).send('User not found.');

    const passwordIsValid = await bcrypt.compare(req.body.password, user.password_hash);
    if (!passwordIsValid) return res.status(401).send({ auth: false, token: null });

    const token = jwt.sign({ id: user.id, role: user.role }, JWT_SUPER_SECRET, { expiresIn: 86400 }); // 24 hours
    res.status(200).send({ auth: true, token: token });
});

app.get('/auth/google', passport.authenticate('google', { scope: ['profile', 'email'] }));

app.get('/auth/google/callback',
    passport.authenticate('google', { failureRedirect: '/login-failed', session: false }),
    (req, res) => {
        const token = jwt.sign({ id: req.user.id, role: req.user.role }, JWT_SUPER_SECRET, { expiresIn: 86400 });
        res.status(200).json({ message: "Google OAuth successful", token });
    }
);

// --- Protected API Routes ---
app.get('/api/posts', [validateJwt], (req, res) => {
    res.status(200).json(MOCK_POSTS);
});

app.post('/api/posts', [validateJwt], (req, res) => {
    const newPost = {
        id: uuidv4(),
        user_id: req.userId,
        title: req.body.title,
        content: req.body.content,
        status: 'DRAFT'
    };
    MOCK_POSTS.push(newPost);
    res.status(201).json(newPost);
});

app.delete('/api/posts/:id', [validateJwt, checkAdmin], (req, res) => {
    const postId = req.params.id;
    const originalLength = MOCK_POSTS.length;
    MOCK_POSTS = MOCK_POSTS.filter(p => p.id !== postId);

    if (MOCK_POSTS.length < originalLength) {
        res.status(200).json({ message: `Post ${postId} deleted successfully.` });
    } else {
        res.status(404).json({ message: "Post not found." });
    }
});

// --- Server Start ---
app.listen(PORT, () => {
    console.log(`Variation 4 server running on http://localhost:${PORT}`);
});
</pre>