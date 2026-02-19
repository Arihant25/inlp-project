<script>
// Variation 4: Module Pattern (Revealing Module Pattern)
// Style: IIFE for encapsulation, private/public members, classic JS style.
// This approach avoids `this` and `class` keywords, favoring closures for state.

const crypto = require('crypto');

// --- Mock Database Driver ---
// A factory function returns a DB driver object.
const createDbDriver = () => {
    const _data = {
        users: {},
        posts: {},
        roles: {},
        user_roles: []
    };
    let _inTransaction = false;

    const query = async (sql, params = []) => {
        console.log(`[SQL] ${sql.replace(/\s+/g, ' ').trim()}`, params);
        // Simplified simulation
        if (sql.startsWith('SELECT')) {
            if (sql.includes('FROM users')) return { rows: Object.values(_data.users) };
            if (sql.includes('FROM posts')) return { rows: Object.values(_data.posts) };
        } else if (sql.startsWith('INSERT INTO users')) {
            const user = { id: params[0], email: params[1], password_hash: params[2], is_active: true, created_at: new Date().toISOString() };
            _data.users[user.id] = user;
            return { rows: [user] };
        } else if (sql.startsWith('INSERT INTO posts')) {
            const post = { id: params[0], user_id: params[1], title: params[2], content: params[3], status: 'DRAFT', created_at: new Date().toISOString() };
            _data.posts[post.id] = post;
            return { rows: [post] };
        } else if (sql.startsWith('INSERT INTO roles')) {
            const role = { id: params[0], name: params[1] };
            _data.roles[role.id] = role;
            return { rows: [role] };
        } else if (sql.startsWith('INSERT INTO user_roles')) {
            _data.user_roles.push({ user_id: params[0], role_id: params[1] });
        } else if (sql.startsWith('BEGIN')) _inTransaction = true;
        else if (sql.startsWith('COMMIT')) _inTransaction = false;
        else if (sql.startsWith('ROLLBACK')) _inTransaction = false;
        
        return { rows: [] };
    };

    const getStore = () => JSON.parse(JSON.stringify(_data));

    return { query, getStore };
};

// --- Migration Module ---
const migrationModule = (function(dbDriver) {
    const _migrationSql = [
        `CREATE TABLE IF NOT EXISTS roles (id UUID PRIMARY KEY, name TEXT UNIQUE NOT NULL);`,
        `CREATE TABLE IF NOT EXISTS users (id UUID PRIMARY KEY, email TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL, is_active BOOLEAN DEFAULT TRUE, created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP);`,
        `CREATE TABLE IF NOT EXISTS user_roles (user_id UUID REFERENCES users(id) ON DELETE CASCADE, role_id UUID REFERENCES roles(id) ON DELETE CASCADE, PRIMARY KEY (user_id, role_id));`,
        `CREATE TABLE IF NOT EXISTS posts (id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, title TEXT NOT NULL, content TEXT, status TEXT DEFAULT 'DRAFT', created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP);`
    ];

    async function run() {
        console.log('Running migrations...');
        for (const sql of _migrationSql) {
            await dbDriver.query(sql);
        }
        console.log('Migrations completed.');
    }

    return { run };
});

// --- Database Operations Module ---
const dbOpsModule = (function(dbDriver) {
    // --- Private Helper Functions ---
    const _buildWhereClause = (filters) => {
        const clauses = [];
        const params = [];
        let i = 1;
        for (const key in filters) {
            if (filters[key] !== undefined) {
                clauses.push(`${key} = $${i++}`);
                params.push(filters[key]);
            }
        }
        return {
            clause: clauses.length ? `WHERE ${clauses.join(' AND ')}` : '',
            params
        };
    };

    // --- Public API ---
    const createUser = async (email, passwordHash, client = dbDriver) => {
        const sql = 'INSERT INTO users (id, email, password_hash) VALUES ($1, $2, $3) RETURNING *;';
        const { rows } = await client.query(sql, [crypto.randomUUID(), email, passwordHash]);
        return rows[0];
    };

    const createPost = async (userId, title, content, client = dbDriver) => {
        const sql = 'INSERT INTO posts (id, user_id, title, content) VALUES ($1, $2, $3, $4) RETURNING *;';
        const { rows } = await client.query(sql, [crypto.randomUUID(), userId, title, content]);
        return rows[0];
    };
    
    const createRole = async (name, client = dbDriver) => {
        const sql = 'INSERT INTO roles (id, name) VALUES ($1, $2) RETURNING *;';
        const { rows } = await client.query(sql, [crypto.randomUUID(), name]);
        return rows[0];
    };
    
    const assignRole = async (userId, roleId, client = dbDriver) => {
        const sql = 'INSERT INTO user_roles (user_id, role_id) VALUES ($1, $2);';
        await client.query(sql, [userId, roleId]);
    };

    const findUsers = async (filters = {}) => {
        const { clause, params } = _buildWhereClause(filters);
        const sql = `SELECT * FROM users ${clause};`;
        const { rows } = await dbDriver.query(sql, params);
        return rows;
    };

    const runInTransaction = async (callback) => {
        console.log('\n--- Starting Transaction ---');
        try {
            await dbDriver.query('BEGIN');
            const result = await callback(dbDriver);
            await dbDriver.query('COMMIT');
            console.log('--- Transaction Committed ---');
            return result;
        } catch (error) {
            await dbDriver.query('ROLLBACK');
            console.error('--- Transaction Rolled Back ---');
            console.error(error.message);
            return null;
        }
    };

    return {
        createUser,
        createPost,
        createRole,
        assignRole,
        findUsers,
        runInTransaction
    };
});

// --- Main Execution ---
(async () => {
    const dbDriver = createDbDriver();
    const migrations = migrationModule(dbDriver);
    const db = dbOpsModule(dbDriver);

    await migrations.run();

    // Setup roles
    const adminRole = await db.createRole('ADMIN');
    const userRole = await db.createRole('USER');

    // Successful transaction
    await db.runInTransaction(async (client) => {
        const user = await db.createUser('grace@example.com', 'hash7', client);
        await db.createPost(user.id, 'Module Pattern Post', 'Content from a module.', client);
        await db.assignRole(user.id, userRole.id, client);
    });

    // Failing transaction
    await db.runInTransaction(async (client) => {
        const user = await db.createUser('heidi-fail@example.com', 'hash8', client);
        await db.assignRole(user.id, adminRole.id, client);
        // Simulate a failure condition
        throw new Error("Intentional failure inside transaction.");
    });

    console.log('\n--- Querying Users with filter ---');
    const activeUsers = await db.findUsers({ is_active: true });
    console.log('Active Users:', activeUsers);

    console.log('\n--- Final DB State ---');
    console.log(JSON.stringify(dbDriver.getStore(), null, 2));
})();
</script>