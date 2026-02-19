<script>
// Variation 2: The "Functional & Modular" Developer
// Style: Uses factory functions and closures, avoids `class` syntax.

// --- Mock Data Source Module ---
const createMockDataSource = () => {
    const users = new Map();
    const posts = new Map();

    const user1 = { id: 'user-111', email: 'admin@corp.net', password_hash: 'hash1', role: 'ADMIN', is_active: true, created_at: new Date().toISOString() };
    const user2 = { id: 'user-222', email: 'user@corp.net', password_hash: 'hash2', role: 'USER', is_active: false, created_at: new Date().toISOString() };
    users.set(user1.id, user1);
    users.set(user2.id, user2);

    const post1 = { id: 'post-aaa', user_id: 'user-222', title: 'Functional Post', content: 'Content here.', status: 'DRAFT' };
    posts.set(post1.id, post1);

    const simulateLatency = () => new Promise(res => setTimeout(res, 50));

    return {
        findUser: async (id) => {
            console.log(`[DB] Querying for user #${id}`);
            await simulateLatency();
            return users.get(id);
        },
        findPost: async (id) => {
            console.log(`[DB] Querying for post #${id}`);
            await simulateLatency();
            return posts.get(id);
        },
        saveUser: async (user) => {
            console.log(`[DB] Saving user #${user.id}`);
            await simulateLatency();
            users.set(user.id, { ...users.get(user.id), ...user });
            return users.get(user.id);
        }
    };
};

// --- LRU Cache Factory ---
const createLRUCache = (capacity) => {
    const cacheMap = new Map();
    let head = { next: null }; // Dummy head
    let tail = { prev: head }; // Dummy tail
    head.next = tail;

    const detachNode = (node) => {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    };

    const attachToHead = (node) => {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    };

    const get = (key) => {
        if (!cacheMap.has(key)) return undefined;
        const node = cacheMap.get(key);
        detachNode(node);
        attachToHead(node);
        return node.value;
    };

    const set = (key, value) => {
        let node;
        if (cacheMap.has(key)) {
            node = cacheMap.get(key);
            node.value = value;
            detachNode(node);
        } else {
            if (cacheMap.size >= capacity) {
                const lruNode = tail.prev;
                detachNode(lruNode);
                cacheMap.delete(lruNode.key);
            }
            node = { key, value, prev: null, next: null };
            cacheMap.set(key, node);
        }
        attachToHead(node);
    };

    const del = (key) => {
        if (cacheMap.has(key)) {
            const node = cacheMap.get(key);
            detachNode(node);
            cacheMap.delete(key);
        }
    };

    return { get, set, del };
};

// --- Cache Manager Factory ---
const createCacheManager = ({ cache, defaultTTL = 60000 }) => {
    const get = (key) => {
        const entry = cache.get(key);
        if (!entry) return undefined;

        if (Date.now() > entry.expiresAt) {
            console.log(`[CACHE] Stale data for ${key}. Deleting.`);
            cache.del(key);
            return undefined;
        }
        return entry.data;
    };

    const set = (key, data, ttl = defaultTTL) => {
        const expiresAt = Date.now() + ttl;
        cache.set(key, { data, expiresAt });
    };


    // Implements cache-aside pattern
    const fetch = async (key, retriever, ttl) => {
        const cachedData = get(key);
        if (cachedData !== undefined) {
            console.log(`[CACHE HIT] Found ${key}.`);
            return cachedData;
        }
        console.log(`[CACHE MISS] Did not find ${key}.`);

        const freshData = await retriever();
        if (freshData) {
            set(key, freshData, ttl);
        }
        return freshData;
    };

    const invalidate = (key) => {
        console.log(`[CACHE] Invalidating ${key}.`);
        cache.del(key);
    };

    return { fetch, invalidate };
};

// --- Repository Layer ---
const createUserRepository = (dataSource, cacheManager) => {
    const KEY_PREFIX = 'user:';
    return {
        findById: (id) => {
            const cacheKey = `${KEY_PREFIX}${id}`;
            const retriever = () => dataSource.findUser(id);
            return cacheManager.fetch(cacheKey, retriever, 5000); // 5s TTL
        },
        update: async (user) => {
            const updatedUser = await dataSource.saveUser(user);
            if (updatedUser) {
                const cacheKey = `${KEY_PREFIX}${user.id}`;
                cacheManager.invalidate(cacheKey);
            }
            return updatedUser;
        }
    };
};

// --- DEMONSTRATION ---
const runDemo = async () => {
    console.log("--- Variation 2: Functional & Modular Demo ---");

    const dataSource = createMockDataSource();
    const lruCache = createLRUCache(10);
    const cacheManager = createCacheManager({ cache: lruCache });
    const userRepository = createUserRepository(dataSource, cacheManager);

    const userId = 'user-111';

    console.log("\n1. First attempt to find user (cache miss):");
    let user = await userRepository.findById(userId);
    console.log("Result:", user.email);

    console.log("\n2. Second attempt to find user (cache hit):");
    user = await userRepository.findById(userId);
    console.log("Result:", user.email);

    console.log("\n3. Update user's active status (invalidates cache):");
    await userRepository.update({ id: userId, is_active: false });

    console.log("\n4. Third attempt to find user (cache miss again):");
    user = await userRepository.findById(userId);
    console.log("Result:", user.is_active);
    
    console.log("\n5. Fourth attempt to find user (cache hit):");
    user = await userRepository.findById(userId);
    console.log("Result:", user.is_active);
};

runDemo();
</script>