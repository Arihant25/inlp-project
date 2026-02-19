<script>
// Variation 2: Object-Oriented Programming / Repository Pattern
// Style: Classes for repositories and DB management, camelCase naming.
// This pattern is common in larger, more structured applications.

const crypto = require('crypto');

// --- Mock Database Driver ---
// Simulates a standard database driver to adhere to constraints.
class MockDbClient {
    constructor() {
        this.dataStore = {
            users: new Map(),
            posts: new Map(),
            roles: new Map(),
            user_roles: [],
        };
        this.inTransaction = false;
        console.log("Mock DB Client Instantiated.");
    }

    async query(sql, params = []) {
        console.log(`[SQL] ${sql.replace(/\s+/g, ' ').trim()}`, params);
        // Simplified simulation logic
        if (sql.startsWith('SELECT')) {
            if (sql.includes('FROM users')) return { rows: Array.from(this.dataStore.users.values()) };
            if (sql.includes('FROM posts')) return { rows: Array.from(this.dataStore.posts.values()) };
            if (sql.includes('FROM roles WHERE name')) return { rows: Array.from(this.dataStore.roles.values()).filter(r => r.name === params[0]) };
        } else if (sql.startsWith('INSERT INTO users')) {
            const user = { id: params[0], email: params[1], password_hash: params[2], is_active: true, created_at: new Date().toISOString() };
            this.dataStore.users.set(user.id, user);
            return { rows: [user], rowCount: 1 };
        } else if (sql.startsWith('INSERT INTO posts')) {
            const post = { id: params[0], user_id: params[1], title: params[2], content: params[3], status: 'DRAFT', created_at: new Date().toISOString() };
            this.dataStore.posts.set(post.id, post);
            return { rows: [post], rowCount: 1 };
        } else if (sql.startsWith('INSERT INTO roles')) {
            const role = { id: params[0], name: params[1] };
            this.dataStore.roles.set(role.id, role);
            return { rows: [role], rowCount: 1 };
        } else if (sql.startsWith('INSERT INTO user_roles')) {
            this.dataStore.user_roles.push({ user_id: params[0], role_id: params[1] });
            return { rowCount: 1 };
        } else if (sql.startsWith('BEGIN')) this.inTransaction = true;
        else if (sql.startsWith('COMMIT')) this.inTransaction = false;
        else if (sql.startsWith('ROLLBACK')) this.inTransaction = false;
        
        return { rows: [], rowCount: 0 };
    }

    getStore() {
        return JSON.parse(JSON.stringify(this.dataStore, (k, v) => v instanceof Map ? Array.from(v.values()) : v));
    }
}

class DatabaseManager {
    constructor() {
        this.client = new MockDbClient();
    }

    async getClient() {
        // In a real app, this would manage a connection pool.
        return this.client;
    }

    async executeTransaction(callback) {
        const client = await this.getClient();
        console.log('\n--- Starting Transaction ---');
        try {
            await client.query('BEGIN');
            const result = await callback(client);
            await client.query('COMMIT');
            console.log('--- Transaction Committed ---');
            return result;
        } catch (error) {
            await client.query('ROLLBACK');
            console.error('--- Transaction Rolled Back ---');
            console.error(error.message);
            throw error; // Re-throw the error after rollback
        }
    }
}

class BaseRepository {
    constructor(dbManager, tableName) {
        this.dbManager = dbManager;
        this.tableName = tableName;
    }

    _buildSelectQuery(filters = {}) {
        let baseSql = `SELECT * FROM ${this.tableName}`;
        const whereClauses = [];
        const params = [];
        let paramIndex = 1;

        for (const key in filters) {
            if (filters[key] !== undefined) {
                // Convert camelCase to snake_case for DB columns
                const snakeKey = key.replace(/[A-Z]/g, letter => `_${letter.toLowerCase()}`);
                whereClauses.push(`${snakeKey} = $${paramIndex++}`);
                params.push(filters[key]);
            }
        }

        if (whereClauses.length > 0) {
            baseSql += ` WHERE ${whereClauses.join(' AND ')}`;
        }
        return { sql: baseSql, params };
    }

    async find(filters) {
        const client = await this.dbManager.getClient();
        const { sql, params } = this._buildSelectQuery(filters);
        const result = await client.query(sql, params);
        return result.rows;
    }

    async findById(id) {
        const client = await this.dbManager.getClient();
        const sql = `SELECT * FROM ${this.tableName} WHERE id = $1`;
        const result = await client.query(sql, [id]);
        return result.rows[0] || null;
    }
}

class UserRepository extends BaseRepository {
    constructor(dbManager) {
        super(dbManager, 'users');
    }

    async create(userData, client) {
        const dbClient = client || await this.dbManager.getClient();
        const { email, passwordHash } = userData;
        const id = crypto.randomUUID();
        const sql = 'INSERT INTO users (id, email, password_hash) VALUES ($1, $2, $3) RETURNING *;';
        const result = await dbClient.query(sql, [id, email, passwordHash]);
        return result.rows[0];
    }
}

class PostRepository extends BaseRepository {
    constructor(dbManager) {
        super(dbManager, 'posts');
    }

    async create(postData, client) {
        const dbClient = client || await this.dbManager.getClient();
        const { userId, title, content } = postData;
        const id = crypto.randomUUID();
        const sql = 'INSERT INTO posts (id, user_id, title, content) VALUES ($1, $2, $3, $4) RETURNING *;';
        const result = await dbClient.query(sql, [id, userId, title, content]);
        return result.rows[0];
    }
}

class RoleRepository extends BaseRepository {
    constructor(dbManager) {
        super(dbManager, 'roles');
    }
    
    async findByName(name, client) {
        const dbClient = client || await this.dbManager.getClient();
        const sql = 'SELECT * FROM roles WHERE name = $1';
        const result = await dbClient.query(sql, [name]);
        return result.rows[0];
    }

    async create(roleData, client) {
        const dbClient = client || await this.dbManager.getClient();
        const { name } = roleData;
        const id = crypto.randomUUID();
        const sql = 'INSERT INTO roles (id, name) VALUES ($1, $2) RETURNING *;';
        const result = await dbClient.query(sql, [id, name]);
        return result.rows[0];
    }

    async assignToUser(userId, roleId, client) {
        const dbClient = client || await this.dbManager.getClient();
        const sql = 'INSERT INTO user_roles (user_id, role_id) VALUES ($1, $2);';
        await dbClient.query(sql, [userId, roleId]);
    }
}

class MigrationService {
    constructor(dbManager) {
        this.dbManager = dbManager;
        this.migrations = [
            `CREATE TABLE IF NOT EXISTS roles (id UUID PRIMARY KEY, name TEXT UNIQUE NOT NULL);`,
            `CREATE TABLE IF NOT EXISTS users (id UUID PRIMARY KEY, email TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL, is_active BOOLEAN DEFAULT TRUE, created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP);`,
            `CREATE TABLE IF NOT EXISTS user_roles (user_id UUID REFERENCES users(id) ON DELETE CASCADE, role_id UUID REFERENCES roles(id) ON DELETE CASCADE, PRIMARY KEY (user_id, role_id));`,
            `CREATE TABLE IF NOT EXISTS posts (id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, title TEXT NOT NULL, content TEXT, status TEXT DEFAULT 'DRAFT', created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP);`
        ];
    }

    async run() {
        console.log('Running migrations...');
        const client = await this.dbManager.getClient();
        for (const sql of this.migrations) {
            await client.query(sql);
        }
        console.log('Migrations completed.');
    }
}

// --- Main Execution ---
(async () => {
    const dbManager = new DatabaseManager();
    const migrationService = new MigrationService(dbManager);
    const userRepo = new UserRepository(dbManager);
    const postRepo = new PostRepository(dbManager);
    const roleRepo = new RoleRepository(dbManager);

    await migrationService.run();

    // Create base roles
    await roleRepo.create({ name: 'ADMIN' });
    await roleRepo.create({ name: 'USER' });

    // Successful transaction
    try {
        await dbManager.executeTransaction(async (client) => {
            const user = await userRepo.create({ email: 'charlie@example.com', passwordHash: 'hash3' }, client);
            await postRepo.create({ userId: user.id, title: 'OOP Post', content: 'Content from an OOP structure.' }, client);
            const userRole = await roleRepo.findByName('USER', client);
            await roleRepo.assignToUser(user.id, userRole.id, client);
        });
    } catch (e) { /* Handled by executeTransaction */ }

    // Failing transaction
    try {
        await dbManager.executeTransaction(async (client) => {
            const user = await userRepo.create({ email: 'dave-fail@example.com', passwordHash: 'hash4' }, client);
            await postRepo.create({ userId: user.id, title: 'This will fail', content: '...' }, client);
            throw new Error("Simulated failure in transaction callback.");
        });
    } catch (e) { /* Handled by executeTransaction */ }

    console.log('\n--- Querying Users with filter ---');
    const activeUsers = await userRepo.find({ isActive: true });
    console.log('Active Users:', activeUsers);

    console.log('\n--- Final DB State ---');
    console.log(JSON.stringify(dbManager.client.getStore(), null, 2));
})();
</script>