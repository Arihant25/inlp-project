<script>
// Variation 1: Procedural / Functional Approach
// Style: Standalone functions, snake_case naming, direct SQL execution.
// A simple, direct approach often seen in scripts or smaller projects.

const crypto = require('crypto');

// --- Mock Database Driver ---
// This simulates a standard database driver client (like 'pg' or 'mysql2')
// to adhere to the "no third-party libraries" constraint.
const create_mock_db_client = () => {
    const data_store = {
        users: new Map(),
        posts: new Map(),
        roles: new Map(),
        user_roles: [],
    };
    let in_transaction = false;
    console.log("Mock DB Initialized.");

    return {
        async query(sql, params = []) {
            // Log the query for demonstration
            console.log(`[SQL] ${sql.replace(/\s+/g, ' ').trim()}`, params);

            // Simulate query execution
            if (sql.includes('SELECT')) {
                if (sql.includes('FROM users')) return { rows: Array.from(data_store.users.values()) };
                if (sql.includes('FROM posts')) return { rows: Array.from(data_store.posts.values()) };
                if (sql.includes('FROM roles')) return { rows: Array.from(data_store.roles.values()) };
            }
            if (sql.includes('INSERT INTO users')) {
                const id = params[0];
                const user = { id, email: params[1], password_hash: params[2], is_active: true, created_at: new Date().toISOString() };
                data_store.users.set(id, user);
                return { rows: [user], rowCount: 1 };
            }
            if (sql.includes('INSERT INTO roles')) {
                const id = params[0];
                const role = { id, name: params[1] };
                data_store.roles.set(id, role);
                return { rows: [role], rowCount: 1 };
            }
            if (sql.includes('INSERT INTO user_roles')) {
                data_store.user_roles.push({ user_id: params[0], role_id: params[1] });
                return { rowCount: 1 };
            }
            if (sql.includes('INSERT INTO posts')) {
                const id = params[0];
                const post = { id, user_id: params[1], title: params[2], content: params[3], status: 'DRAFT', created_at: new Date().toISOString() };
                data_store.posts.set(id, post);
                return { rows: [post], rowCount: 1 };
            }
            if (sql.includes('BEGIN')) in_transaction = true;
            if (sql.includes('COMMIT')) in_transaction = false;
            if (sql.includes('ROLLBACK')) in_transaction = false;

            return { rows: [], rowCount: 0 };
        },
        get_store: () => JSON.parse(JSON.stringify(data_store, (k, v) => v instanceof Map ? Array.from(v.values()) : v))
    };
};

// --- Migration Functions ---
const MIGRATION_SQL = [
    `CREATE TABLE IF NOT EXISTS roles (
        id UUID PRIMARY KEY,
        name TEXT UNIQUE NOT NULL
    );`,
    `CREATE TABLE IF NOT EXISTS users (
        id UUID PRIMARY KEY,
        email TEXT UNIQUE NOT NULL,
        password_hash TEXT NOT NULL,
        is_active BOOLEAN DEFAULT TRUE,
        created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
    );`,
    `CREATE TABLE IF NOT EXISTS user_roles (
        user_id UUID REFERENCES users(id) ON DELETE CASCADE,
        role_id UUID REFERENCES roles(id) ON DELETE CASCADE,
        PRIMARY KEY (user_id, role_id)
    );`,
    `CREATE TABLE IF NOT EXISTS posts (
        id UUID PRIMARY KEY,
        user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        title TEXT NOT NULL,
        content TEXT,
        status TEXT CHECK (status IN ('DRAFT', 'PUBLISHED')) DEFAULT 'DRAFT',
        created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
    );`
];

async function run_migrations(db_client) {
    console.log('Running migrations...');
    for (const sql of MIGRATION_SQL) {
        await db_client.query(sql);
    }
    console.log('Migrations completed.');
}

// --- Query Builder ---
function build_query(base_sql, filters = {}) {
    const where_clauses = [];
    const params = [];
    let param_index = 1;

    for (const key in filters) {
        if (filters[key] !== undefined) {
            where_clauses.push(`${key} = $${param_index++}`);
            params.push(filters[key]);
        }
    }

    if (where_clauses.length > 0) {
        return {
            sql: `${base_sql} WHERE ${where_clauses.join(' AND ')}`,
            params
        };
    }
    return { sql: base_sql, params };
}

// --- User Operations ---
async function create_user(db_client, { email, password_hash }) {
    const sql = 'INSERT INTO users (id, email, password_hash) VALUES ($1, $2, $3) RETURNING *;';
    const id = crypto.randomUUID();
    const result = await db_client.query(sql, [id, email, password_hash]);
    return result.rows[0];
}

async function find_users(db_client, filters) {
    const { sql, params } = build_query('SELECT * FROM users', filters);
    const result = await db_client.query(sql, params);
    return result.rows;
}

// --- Post Operations ---
async function create_post(db_client, { user_id, title, content }) {
    const sql = 'INSERT INTO posts (id, user_id, title, content) VALUES ($1, $2, $3, $4) RETURNING *;';
    const id = crypto.randomUUID();
    const result = await db_client.query(sql, [id, user_id, title, content]);
    return result.rows[0];
}

// --- Role Operations ---
async function create_role(db_client, { name }) {
    const sql = 'INSERT INTO roles (id, name) VALUES ($1, $2) RETURNING *;';
    const id = crypto.randomUUID();
    const result = await db_client.query(sql, [id, name]);
    return result.rows[0];
}

async function assign_role_to_user(db_client, { user_id, role_id }) {
    const sql = 'INSERT INTO user_roles (user_id, role_id) VALUES ($1, $2);';
    await db_client.query(sql, [user_id, role_id]);
}


// --- Transaction Example ---
async function create_user_with_post_and_role_transaction(db_client, user_data, post_data, role_name) {
    console.log('\n--- Starting Transaction ---');
    try {
        await db_client.query('BEGIN');

        const user = await create_user(db_client, user_data);
        const post = await create_post(db_client, { ...post_data, user_id: user.id });
        
        // For simplicity, assume role exists or create it
        let role = (await db_client.query('SELECT * FROM roles WHERE name = $1', [role_name])).rows[0];
        if (!role) {
            role = await create_role(db_client, { name: role_name });
        }
        await assign_role_to_user(db_client, { user_id: user.id, role_id: role.id });

        // Simulate a failure
        if (user_data.email.includes('fail')) {
            throw new Error("Simulated failure during transaction.");
        }

        await db_client.query('COMMIT');
        console.log('--- Transaction Committed ---');
        return { user, post };
    } catch (error) {
        await db_client.query('ROLLBACK');
        console.error('--- Transaction Rolled Back ---');
        console.error(error.message);
        return null;
    }
}

// --- Main Execution ---
(async () => {
    const db_client = create_mock_db_client();
    await run_migrations(db_client);

    // Create roles
    await create_role(db_client, { name: 'ADMIN' });
    await create_role(db_client, { name: 'USER' });

    // Successful transaction
    await create_user_with_post_and_role_transaction(
        db_client,
        { email: 'alice@example.com', password_hash: 'hash1' },
        { title: 'Alice\'s First Post', content: 'Hello World!' },
        'USER'
    );

    // Failing transaction
    await create_user_with_post_and_role_transaction(
        db_client,
        { email: 'bob-fail@example.com', password_hash: 'hash2' },
        { title: 'Bob\'s Post', content: 'This will not be saved.' },
        'ADMIN'
    );

    // Querying data
    console.log('\n--- Querying Users with filter ---');
    const active_users = await find_users(db_client, { is_active: true });
    console.log('Active Users:', active_users);

    console.log('\n--- Final DB State ---');
    console.log(JSON.stringify(db_client.get_store(), null, 2));
})();
</script>