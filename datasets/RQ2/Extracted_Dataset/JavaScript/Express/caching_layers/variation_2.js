// Variation 2: OOP / Class-based Approach
// This variation encapsulates logic into classes for services and controllers.
// To run: npm install express uuid

const express = require('express');
const { v4: uuidv4 } = require('uuid');

// --- Mock Database Service ---
class DatabaseService {
    constructor() {
        this.users = {};
        this.posts = {};
        this._seedData();
    }

    _seedData() {
        const userId1 = uuidv4();
        this.users[userId1] = {
            id: userId1, email: 'admin@example.com', password_hash: 'hash1',
            role: 'ADMIN', is_active: true, created_at: new Date()
        };
        const postId = uuidv4();
        this.posts[postId] = {
            id: postId, user_id: userId1, title: 'OOP Post',
            content: 'Content from the class-based approach.', status: 'PUBLISHED'
        };
    }

    async findUserById(id) {
        console.log(`[DB] Querying for user id: ${id}`);
        await new Promise(res => setTimeout(res, 50)); // Simulate latency
        return this.users[id] || null;
    }

    async updateUser(id, data) {
        console.log(`[DB] Updating user id: ${id}`);
        await new Promise(res => setTimeout(res, 50));
        if (this.users[id]) {
            this.users[id] = { ...this.users[id], ...data };
            return this.users[id];
        }
        return null;
    }
}

// --- LRU Cache Service ---
class CacheService {
    constructor(maxSize = 100) {
        this.maxSize = maxSize;
        this.cache = new Map(); // Stores { key: { value, expiry } }
    }

    get(key) {
        const entry = this.cache.get(key);
        if (!entry) return undefined;

        // Check for time-based expiration
        if (entry.expiry && entry.expiry < Date.now()) {
            this.delete(key);
            console.log(`[CACHE] Key ${key} expired.`);
            return undefined;
        }

        // LRU logic: re-insert to mark as recently used
        this.cache.delete(key);
        this.cache.set(key, entry);
        return entry.value;
    }

    set(key, value, ttlMs = 60000) {
        if (this.cache.size >= this.maxSize) {
            // Evict the least recently used item
            const lruKey = this.cache.keys().next().value;
            this.delete(lruKey);
            console.log(`[CACHE] Evicted LRU key: ${lruKey}`);
        }
        const expiry = ttlMs ? Date.now() + ttlMs : null;
        this.cache.set(key, { value, expiry });
    }

    delete(key) {
        return this.cache.delete(key);
    }
}

// --- User Controller ---
class UserController {
    constructor(dbService, cacheService) {
        this.dbService = dbService;
        this.cacheService = cacheService;
        this.getUserById = this.getUserById.bind(this);
        this.updateUser = this.updateUser.bind(this);
    }

    // Implements Cache-Aside pattern
    async getUserById(req, res) {
        const { id } = req.params;
        const cacheKey = `user:${id}`;

        try {
            // 1. Check cache
            let user = this.cacheService.get(cacheKey);
            if (user) {
                console.log(`[CACHE] HIT for key: ${cacheKey}`);
                return res.status(200).json(user);
            }

            console.log(`[CACHE] MISS for key: ${cacheKey}`);
            
            // 2. On miss, query database
            user = await this.dbService.findUserById(id);
            if (!user) {
                return res.status(404).json({ error: 'User not found' });
            }

            // 3. Populate cache
            this.cacheService.set(cacheKey, user, 30000); // 30 sec TTL
            console.log(`[CACHE] SET for key: ${cacheKey}`);

            return res.status(200).json(user);
        } catch (err) {
            return res.status(500).json({ error: 'Internal Server Error' });
        }
    }

    // Implements Cache Invalidation
    async updateUser(req, res) {
        const { id } = req.params;
        const { email } = req.body;
        const cacheKey = `user:${id}`;

        try {
            // 1. Update database
            const updatedUser = await this.dbService.updateUser(id, { email });
            if (!updatedUser) {
                return res.status(404).json({ error: 'User not found' });
            }

            // 2. Invalidate cache
            this.cacheService.delete(cacheKey);
            console.log(`[CACHE] INVALIDATED key: ${cacheKey}`);

            return res.status(200).json(updatedUser);
        } catch (err) {
            return res.status(500).json({ error: 'Internal Server Error' });
        }
    }
}

// --- Application Setup ---
const app = express();
const PORT = 3002;

// Dependency Injection
const dbService = new DatabaseService();
const cacheService = new CacheService();
const userController = new UserController(dbService, cacheService);

// Routing
const userRouter = express.Router();
userRouter.get('/:id', userController.getUserById);
userRouter.put('/:id', userController.updateUser);

app.use(express.json());
app.use('/users', userRouter);

app.listen(PORT, () => {
    console.log(`Variation 2 (OOP) server running on http://localhost:${PORT}`);
});