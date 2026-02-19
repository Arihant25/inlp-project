// Variation 3: Modern ES Modules & Service Layer
// Uses ESM syntax, a dedicated service layer for business logic, and a popular caching library.
// To run: npm install express uuid lru-cache
// Create a package.json with "type": "module" or use .mjs extension.

import express from 'express';
import { v4 as uuidv4 } from 'uuid';
import { LRUCache } from 'lru-cache';

// --- Mock DB Data Module (data/mock.db.mjs) ---
const mockDb = {
    users: new Map(),
    posts: new Map(),
};

const userId = uuidv4();
mockDb.users.set(userId, {
    id: userId,
    email: 'modern.user@example.com',
    password_hash: 'some_secure_hash',
    role: 'USER',
    is_active: true,
    created_at: new Date(),
});

const dbAdapter = {
    fetchUser: async (id) => {
        console.log(`[DB] Fetching user: ${id}`);
        await new Promise(r => setTimeout(r, 50)); // Simulate I/O
        return mockDb.users.get(id);
    },
    persistUserUpdate: async (id, data) => {
        console.log(`[DB] Persisting update for user: ${id}`);
        await new Promise(r => setTimeout(r, 50));
        const user = mockDb.users.get(id);
        if (user) {
            const updatedUser = { ...user, ...data };
            mockDb.users.set(id, updatedUser);
            return updatedUser;
        }
        return null;
    },
};

// --- Cache Service (services/cache.service.mjs) ---
// A wrapper around a library like lru-cache
const cacheOptions = {
    max: 100, // Max number of items
    ttl: 1000 * 60 * 5, // Default TTL: 5 minutes
};
const cacheService = new LRUCache(cacheOptions);

// --- User Service (services/user.service.mjs) ---
// This layer contains the core business logic, including the cache-aside pattern.
const userService = {
    getUserById: async (id) => {
        const cacheKey = `user:${id}`;

        // 1. Check cache
        if (cacheService.has(cacheKey)) {
            console.log(`[CACHE] HIT for key: ${cacheKey}`);
            return cacheService.get(cacheKey);
        }

        console.log(`[CACHE] MISS for key: ${cacheKey}`);
        
        // 2. On miss, query DB
        const user = await dbAdapter.fetchUser(id);

        // 3. Populate cache if user found
        if (user) {
            cacheService.set(cacheKey, user, { ttl: 1000 * 30 }); // Override TTL to 30s
            console.log(`[CACHE] SET for key: ${cacheKey}`);
        }

        return user;
    },

    updateUserEmail: async (id, newEmail) => {
        const cacheKey = `user:${id}`;

        // 1. Update the primary data source
        const updatedUser = await dbAdapter.persistUserUpdate(id, { email: newEmail });

        // 2. Invalidate the cache
        if (updatedUser) {
            if (cacheService.has(cacheKey)) {
                cacheService.delete(cacheKey);
                console.log(`[CACHE] INVALIDATED key: ${cacheKey}`);
            }
        }

        return updatedUser;
    },
};

// --- API Router (api/user.router.mjs) ---
const userRouter = express.Router();

userRouter.get('/:id', async (req, res) => {
    try {
        const user = await userService.getUserById(req.params.id);
        if (!user) {
            return res.status(404).send({ message: 'User not found' });
        }
        res.json(user);
    } catch (e) {
        res.status(500).send({ message: 'An error occurred' });
    }
});

userRouter.put('/:id', async (req, res) => {
    try {
        const { email } = req.body;
        if (!email) {
            return res.status(400).send({ message: 'Email is required' });
        }
        const updatedUser = await userService.updateUserEmail(req.params.id, email);
        if (!updatedUser) {
            return res.status(404).send({ message: 'User not found' });
        }
        res.json(updatedUser);
    } catch (e) {
        res.status(500).send({ message: 'An error occurred' });
    }
});

// --- Main Server File (index.mjs) ---
const app = express();
const PORT = 3003;

app.use(express.json());
app.use('/users', userRouter);

app.listen(PORT, () => {
    console.log(`Variation 3 (Modern ESM) server running on http://localhost:${PORT}`);
});