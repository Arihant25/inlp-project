// Variation 1: Classic Functional & Middleware Approach
// A traditional Express structure using separate modules for concerns and middleware for caching logic.
// To run: npm install express uuid

const express = require('express');
const { v4: uuidv4 } = require('uuid');

// --- Mock Database Module (db.js) ---
const db = {
    users: {},
    posts: {}
};

// Seed data
(() => {
    const userId1 = uuidv4();
    const userId2 = uuidv4();
    db.users[userId1] = {
        id: userId1,
        email: 'admin@example.com',
        password_hash: 'hashed_password_1',
        role: 'ADMIN',
        is_active: true,
        created_at: new Date()
    };
    db.users[userId2] = {
        id: userId2,
        email: 'user@example.com',
        password_hash: 'hashed_password_2',
        role: 'USER',
        is_active: true,
        created_at: new Date()
    };
    const postId = uuidv4();
    db.posts[postId] = {
        id: postId,
        user_id: userId1,
        title: 'First Post',
        content: 'This is the content of the first post.',
        status: 'PUBLISHED'
    };
})();

const db_findUserById = async (id) => {
    console.log(`[DB] Querying for user with id: ${id}`);
    await new Promise(resolve => setTimeout(resolve, 50)); // Simulate DB latency
    return db.users[id] || null;
};

const db_updateUserEmail = async (id, email) => {
    console.log(`[DB] Updating email for user with id: ${id}`);
    await new Promise(resolve => setTimeout(resolve, 50));
    if (db.users[id]) {
        db.users[id].email = email;
        return db.users[id];
    }
    return null;
};


// --- In-Memory LRU Cache Module (cache.js) ---
const MAX_CACHE_SIZE = 100;
const cache = new Map();
const ttlMap = new Map();

const cache_get = (key) => {
    // Check for expiration
    if (ttlMap.has(key) && ttlMap.get(key) < Date.now()) {
        cache.delete(key);
        ttlMap.delete(key);
        console.log(`[CACHE] Key ${key} expired and removed.`);
        return undefined;
    }

    const value = cache.get(key);
    if (value) {
        // LRU logic: move to end to mark as recently used
        cache.delete(key);
        cache.set(key, value);
    }
    return value;
};

const cache_set = (key, value, ttl_ms = 60000) => {
    if (cache.size >= MAX_CACHE_SIZE) {
        // Evict least recently used item (the first item in map's iteration)
        const lruKey = cache.keys().next().value;
        cache.delete(lruKey);
        ttlMap.delete(lruKey);
        console.log(`[CACHE] Evicted LRU key: ${lruKey}`);
    }
    cache.set(key, value);
    ttlMap.set(key, Date.now() + ttl_ms);
};

const cache_del = (key) => {
    cache.delete(key);
    ttlMap.delete(key);
};


// --- Cache Middleware (middleware/cacheMiddleware.js) ---
const userCacheMiddleware = async (req, res, next) => {
    const { id } = req.params;
    const cacheKey = `user:${id}`;
    
    const cachedUser = cache_get(cacheKey);
    if (cachedUser) {
        console.log(`[CACHE] HIT for key: ${cacheKey}`);
        return res.status(200).json(cachedUser);
    }

    console.log(`[CACHE] MISS for key: ${cacheKey}`);
    // Store the key in the response object to be used by the route handler
    res.locals.cacheKey = cacheKey;
    next();
};


// --- User Routes (routes/userRoutes.js) ---
const userRouter = express.Router();

// GET User by ID (Cache-Aside)
userRouter.get('/:id', userCacheMiddleware, async (req, res) => {
    try {
        const { id } = req.params;
        const user = await db_findUserById(id);

        if (!user) {
            return res.status(404).json({ message: 'User not found' });
        }

        // Set data in cache after fetching from DB
        cache_set(res.locals.cacheKey, user, 30000); // 30-second TTL
        console.log(`[CACHE] SET for key: ${res.locals.cacheKey}`);

        return res.status(200).json(user);
    } catch (error) {
        return res.status(500).json({ message: 'Server error' });
    }
});

// PUT User (Cache Invalidation)
userRouter.put('/:id', async (req, res) => {
    try {
        const { id } = req.params;
        const { email } = req.body;
        const cacheKey = `user:${id}`;

        const updatedUser = await db_updateUserEmail(id, email);

        if (!updatedUser) {
            return res.status(404).json({ message: 'User not found' });
        }

        // Invalidate cache
        cache_del(cacheKey);
        console.log(`[CACHE] DELETED/INVALIDATED key: ${cacheKey}`);

        return res.status(200).json(updatedUser);
    } catch (error) {
        return res.status(500).json({ message: 'Server error' });
    }
});


// --- Main Server File (server.js) ---
const app = express();
const PORT = 3001;

app.use(express.json());
app.use('/users', userRouter);

app.listen(PORT, () => {
    console.log(`Variation 1 (Functional) server running on http://localhost:${PORT}`);
});