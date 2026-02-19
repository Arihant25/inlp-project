// Variation 4: Minimalist & High-Cohesion Approach
// All logic is contained within a single file, suitable for a small microservice.
// The LRU cache is a simple, self-contained implementation using a Map.
// To run: npm install express uuid

const express = require('express');
const { v4: uuidv4 } = require('uuid');

const app = express();
const PORT = 3004;
app.use(express.json());

// --- In-memory "Database" ---
const db = new Map();
const userId = uuidv4();
db.set(userId, {
    id: userId,
    email: 'minimal@example.com',
    password_hash: 'hash_browns',
    role: 'USER',
    is_active: true,
    created_at: new Date(),
});

const dbActions = {
    find: async (collection, id) => {
        console.log(`[DB] Simulating find for id: ${id}`);
        await new Promise(res => setTimeout(res, 50));
        return db.get(id); // Simplified for single collection
    },
    update: async (collection, id, data) => {
        console.log(`[DB] Simulating update for id: ${id}`);
        await new Promise(res => setTimeout(res, 50));
        const record = db.get(id);
        if (record) {
            const updatedRecord = { ...record, ...data };
            db.set(id, updatedRecord);
            return updatedRecord;
        }
        return null;
    }
};

// --- Self-Contained LRU Cache with TTL ---
const cache = {
    data: new Map(),
    maxSize: 50,

    get(key) {
        const entry = this.data.get(key);
        if (!entry) return null;

        // Passive expiration check
        if (entry.expiry < Date.now()) {
            this.data.delete(key);
            console.log(`[CACHE] Expired key: ${key}`);
            return null;
        }

        // Refresh for LRU
        this.data.delete(key);
        this.data.set(key, entry);
        return entry.value;
    },

    set(key, value, ttl = 60000) {
        if (this.data.size >= this.maxSize) {
            // Evict LRU (first key in map iterator)
            const lruKey = this.data.keys().next().value;
            this.data.delete(lruKey);
            console.log(`[CACHE] Evicted LRU key: ${lruKey}`);
        }
        const entry = {
            value,
            expiry: Date.now() + ttl,
        };
        this.data.set(key, entry);
    },

    del(key) {
        this.data.delete(key);
    }
};

// --- Routes with Integrated Cache-Aside Logic ---

// GET /users/:id - Implements Cache-Aside
app.get('/users/:id', async (req, res) => {
    const { id } = req.params;
    const key = `user::${id}`;

    // 1. Check cache
    let user = cache.get(key);
    if (user) {
        console.log(`[CACHE] HIT on key: ${key}`);
        return res.json(user);
    }

    console.log(`[CACHE] MISS on key: ${key}`);
    
    // 2. On miss, get from "DB"
    user = await dbActions.find('users', id);
    if (!user) {
        return res.status(404).json({ error: 'Not Found' });
    }

    // 3. Populate cache
    cache.set(key, user, 30000); // 30s TTL
    console.log(`[CACHE] SET key: ${key}`);
    
    res.json(user);
});

// PUT /users/:id - Implements Cache Invalidation
app.put('/users/:id', async (req, res) => {
    const { id } = req.params;
    const { email } = req.body;
    const key = `user::${id}`;

    // 1. Update DB
    const updatedUser = await dbActions.update('users', id, { email });
    if (!updatedUser) {
        return res.status(404).json({ error: 'Not Found' });
    }

    // 2. Invalidate cache
    cache.del(key);
    console.log(`[CACHE] INVALIDATED key: ${key}`);

    res.json(updatedUser);
});

app.listen(PORT, () => {
    console.log(`Variation 4 (Minimalist) server running on http://localhost:${PORT}`);
});