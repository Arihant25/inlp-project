<script>
// Variation 3: The "Prototype-based & Minimalist" Developer
// Style: Uses constructor functions and prototypes, more compact code.

// --- Mock Data Store (Singleton-like Object) ---
var DataStore = {
    _users: new Map(),
    _posts: new Map(),
    init: function() {
        var user1 = { id: 'uuid-user-001', email: 'test1@mail.com', password_hash: 'abc', role: 'USER', is_active: true, created_at: new Date().toISOString() };
        var user2 = { id: 'uuid-user-002', email: 'test2@mail.com', password_hash: 'def', role: 'ADMIN', is_active: true, created_at: new Date().toISOString() };
        this._users.set(user1.id, user1);
        this._users.set(user2.id, user2);
        var post1 = { id: 'uuid-post-101', user_id: user1.id, title: 'A Post', content: 'Some text.', status: 'PUBLISHED' };
        this._posts.set(post1.id, post1);
    },
    fetchUser: function(id) {
        console.log('DB => Fetching user', id);
        return new Promise(res => setTimeout(() => res(this._users.get(id) || null), 40));
    },
    updateUser: function(user) {
        console.log('DB => Updating user', user.id);
        var existing = this._users.get(user.id);
        if (existing) {
            var updated = { ...existing, ...user };
            this._users.set(user.id, updated);
            return new Promise(res => setTimeout(() => res(updated), 40));
        }
        return Promise.resolve(null);
    }
};
DataStore.init();

// --- LRU Cache Implementation (Prototype-based) ---
function CacheNode(key, val) {
    this.key = key;
    this.val = val;
    this.prev = this.next = null;
}

function LruCache(capacity) {
    this.cap = capacity;
    this.map = new Map();
    this.head = new CacheNode();
    this.tail = new CacheNode();
    this.head.next = this.tail;
    this.tail.prev = this.head;
}

LruCache.prototype._remove = function(node) {
    node.prev.next = node.next;
    node.next.prev = node.prev;
};

LruCache.prototype._add = function(node) {
    node.next = this.head.next;
    node.prev = this.head;
    this.head.next.prev = node;
    this.head.next = node;
};

LruCache.prototype.get = function(key) {
    if (this.map.has(key)) {
        var node = this.map.get(key);
        this._remove(node);
        this._add(node);
        return node.val;
    }
    return null;
};

LruCache.prototype.set = function(key, val) {
    if (this.map.has(key)) {
        this._remove(this.map.get(key));
    }
    var node = new CacheNode(key, val);
    this._add(node);
    this.map.set(key, node);

    if (this.map.size > this.cap) {
        var lru = this.tail.prev;
        this._remove(lru);
        this.map.delete(lru.key);
    }
};

LruCache.prototype.del = function(key) {
    if (this.map.has(key)) {
        var node = this.map.get(key);
        this._remove(node);
        this.map.delete(key);
    }
};

// --- Application Cache Layer (Singleton-like Object) ---
var AppCache = {
    _cache: new LruCache(10),
    _ttl: 4000, // 4 seconds

    // Cache-Aside Pattern Implementation
    fetchUserWithCache: async function(userId) {
        var key = 'user:' + userId;
        var cached = this._cache.get(key);

        if (cached && cached.expires > Date.now()) {
            console.log('CACHE HIT for', key);
            return cached.data;
        }
        
        console.log(cached ? 'CACHE STALE for' : 'CACHE MISS for', key);
        var user = await DataStore.fetchUser(userId);
        if (user) {
            this._cache.set(key, {
                data: user,
                expires: Date.now() + this._ttl
            });
        }
        return user;
    },

    // Invalidation Strategy
    updateUserAndInvalidate: async function(user) {
        var updatedUser = await DataStore.updateUser(user);
        if (updatedUser) {
            var key = 'user:' + updatedUser.id;
            console.log('CACHE INVALIDATE for', key);
            this._cache.del(key);
        }
        return updatedUser;
    }
};

// --- DEMONSTRATION ---
async function run() {
    console.log("--- Variation 3: Prototype-based & Minimalist Demo ---");
    var userId = 'uuid-user-001';

    console.log("\n1. Fetching user for the first time (MISS):");
    var user = await AppCache.fetchUserWithCache(userId);
    console.log("Got user:", user.email);

    console.log("\n2. Fetching user again (HIT):");
    user = await AppCache.fetchUserWithCache(userId);
    console.log("Got user:", user.email);

    console.log("\n3. Updating user's email (INVALIDATE):");
    await AppCache.updateUserAndInvalidate({ id: userId, email: 'updated@mail.com' });

    console.log("\n4. Fetching user after update (MISS):");
    user = await AppCache.fetchUserWithCache(userId);
    console.log("Got user:", user.email);
    
    console.log("\n5. Waiting for TTL to expire...");
    await new Promise(res => setTimeout(res, 4100));
    
    console.log("\n6. Fetching user after TTL expiry (STALE/MISS):");
    user = await AppCache.fetchUserWithCache(userId);
    console.log("Got user:", user.email);
}

run();
</script>