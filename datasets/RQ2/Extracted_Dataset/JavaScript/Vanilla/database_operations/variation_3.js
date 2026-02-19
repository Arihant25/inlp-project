<script>
// Variation 3: Service Layer with Data Access Objects (DAO)
// Style: Clear separation of concerns. Services handle business logic and transactions,
// DAOs handle raw database interaction. Verbose naming for clarity.

const crypto = require('crypto');

// --- Mock Database Connection ---
// Simulates a raw connection object.
function createMockDatabaseConnection() {
    const dataStore = {
        users: new Map(),
        posts: new Map(),
        roles: new Map(),
        user_roles: [],
    };
    let transactionDepth = 0;

    return {
        async executeQuery(sql, params = []) {
            console.log(`[SQL] ${sql.replace(/\s+/g, ' ').trim()}`, params);
            // Simulation logic
            if (sql.startsWith('SELECT')) {
                if (sql.includes('FROM users WHERE id')) return { rows: [dataStore.users.get(params[0])] };
                if (sql.includes('FROM users')) return { rows: Array.from(dataStore.users.values()) };
                if (sql.includes('FROM roles WHERE name')) return { rows: Array.from(dataStore.roles.values()).filter(r => r.name === params[0]) };
            } else if (sql.startsWith('INSERT INTO users')) {
                const user = { id: params[0], email: params[1], password_hash: params[2], is_active: true, created_at: new Date().toISOString() };
                dataStore.users.set(user.id, user);
                return { rows: [user] };
            } else if (sql.startsWith('INSERT INTO posts')) {
                const post = { id: params[0], user_id: params[1], title: params[2], content: params[3], status: 'DRAFT', created_at: new Date().toISOString() };
                dataStore.posts.set(post.id, post);
                return { rows: [post] };
            } else if (sql.startsWith('INSERT INTO roles')) {
                const role = { id: params[0], name: params[1] };
                dataStore.roles.set(role.id, role);
                return { rows: [role] };
            } else if (sql.startsWith('INSERT INTO user_roles')) {
                dataStore.user_roles.push({ user_id: params[0], role_id: params[1] });
            } else if (sql.startsWith('BEGIN')) transactionDepth++;
            else if (sql.startsWith('COMMIT')) transactionDepth--;
            else if (sql.startsWith('ROLLBACK')) transactionDepth = 0;
            
            return { rows: [] };
        },
        getStore: () => JSON.parse(JSON.stringify(dataStore, (k, v) => v instanceof Map ? Array.from(v.values()) : v))
    };
}

// --- Data Access Objects (DAOs) ---
// DAOs are responsible for direct data access, executing SQL.

class UserDAO {
    constructor(dbConnection) {
        this.dbConnection = dbConnection;
    }

    async insert(userData) {
        const { id, email, passwordHash } = userData;
        const sql = 'INSERT INTO users (id, email, password_hash) VALUES ($1, $2, $3) RETURNING *;';
        const { rows } = await this.dbConnection.executeQuery(sql, [id, email, passwordHash]);
        return rows[0];
    }

    async findBy(filters) {
        let baseSql = 'SELECT * FROM users';
        const whereClauses = [];
        const params = [];
        let paramIndex = 1;
        if (filters.isActive !== undefined) {
            whereClauses.push(`is_active = $${paramIndex++}`);
            params.push(filters.isActive);
        }
        if (whereClauses.length) {
            baseSql += ` WHERE ${whereClauses.join(' AND ')}`;
        }
        const { rows } = await this.dbConnection.executeQuery(baseSql, params);
        return rows;
    }
}

class PostDAO {
    constructor(dbConnection) {
        this.dbConnection = dbConnection;
    }

    async insert(postData) {
        const { id, userId, title, content } = postData;
        const sql = 'INSERT INTO posts (id, user_id, title, content) VALUES ($1, $2, $3, $4) RETURNING *;';
        const { rows } = await this.dbConnection.executeQuery(sql, [id, userId, title, content]);
        return rows[0];
    }
}

class RoleDAO {
    constructor(dbConnection) {
        this.dbConnection = dbConnection;
    }
    
    async findByName(name) {
        const sql = 'SELECT * FROM roles WHERE name = $1;';
        const { rows } = await this.dbConnection.executeQuery(sql, [name]);
        return rows[0];
    }

    async insert(roleData) {
        const { id, name } = roleData;
        const sql = 'INSERT INTO roles (id, name) VALUES ($1, $2) RETURNING *;';
        const { rows } = await this.dbConnection.executeQuery(sql, [id, name]);
        return rows[0];
    }

    async linkUserToRole(userId, roleId) {
        const sql = 'INSERT INTO user_roles (user_id, role_id) VALUES ($1, $2);';
        await this.dbConnection.executeQuery(sql, [userId, roleId]);
    }
}

// --- Service Layer ---
// Services contain business logic and orchestrate DAOs within transactions.

class UserService {
    constructor(dbConnection, userDAO, postDAO, roleDAO) {
        this.dbConnection = dbConnection;
        this.userDAO = userDAO;
        this.postDAO = postDAO;
        this.roleDAO = roleDAO;
    }

    async registerUserWithInitialPost(userDetails, postDetails, roleNames) {
        console.log('\n--- Starting Service Operation: registerUserWithInitialPost ---');
        await this.dbConnection.executeQuery('BEGIN');
        try {
            const newUser = await this.userDAO.insert({
                id: crypto.randomUUID(),
                email: userDetails.email,
                passwordHash: userDetails.passwordHash,
            });

            await this.postDAO.insert({
                id: crypto.randomUUID(),
                userId: newUser.id,
                title: postDetails.title,
                content: postDetails.content,
            });

            for (const roleName of roleNames) {
                let role = await this.roleDAO.findByName(roleName);
                if (!role) {
                    throw new Error(`Role '${roleName}' not found.`);
                }
                await this.roleDAO.linkUserToRole(newUser.id, role.id);
            }
            
            if (userDetails.email.includes('fail')) {
                throw new Error("Simulated business logic failure.");
            }

            await this.dbConnection.executeQuery('COMMIT');
            console.log('--- Service Operation Succeeded ---');
            return newUser;
        } catch (error) {
            await this.dbConnection.executeQuery('ROLLBACK');
            console.error('--- Service Operation Failed and Rolled Back ---');
            console.error(error.message);
            return null;
        }
    }

    async getActiveUsers() {
        return this.userDAO.findBy({ isActive: true });
    }
}

class MigrationManager {
    constructor(dbConnection) {
        this.dbConnection = dbConnection;
        this.migrationScripts = [
            `CREATE TABLE IF NOT EXISTS roles (id UUID PRIMARY KEY, name TEXT UNIQUE NOT NULL);`,
            `CREATE TABLE IF NOT EXISTS users (id UUID PRIMARY KEY, email TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL, is_active BOOLEAN DEFAULT TRUE, created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP);`,
            `CREATE TABLE IF NOT EXISTS user_roles (user_id UUID REFERENCES users(id) ON DELETE CASCADE, role_id UUID REFERENCES roles(id) ON DELETE CASCADE, PRIMARY KEY (user_id, role_id));`,
            `CREATE TABLE IF NOT EXISTS posts (id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, title TEXT NOT NULL, content TEXT, status TEXT DEFAULT 'DRAFT', created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP);`
        ];
    }
    async executeMigrations() {
        console.log('Executing migrations...');
        for (const script of this.migrationScripts) {
            await this.dbConnection.executeQuery(script);
        }
        console.log('Migrations finished.');
    }
}

// --- Main Execution ---
(async () => {
    const dbConnection = createMockDatabaseConnection();
    
    const migrationManager = new MigrationManager(dbConnection);
    await migrationManager.executeMigrations();

    const userDAO = new UserDAO(dbConnection);
    const postDAO = new PostDAO(dbConnection);
    const roleDAO = new RoleDAO(dbConnection);
    const userService = new UserService(dbConnection, userDAO, postDAO, roleDAO);

    // Setup initial data
    await roleDAO.insert({ id: crypto.randomUUID(), name: 'ADMIN' });
    await roleDAO.insert({ id: crypto.randomUUID(), name: 'USER' });

    // Successful service call
    await userService.registerUserWithInitialPost(
        { email: 'eva@example.com', passwordHash: 'hash5' },
        { title: 'Service Layer Post', content: 'This post was created via a service.' },
        ['USER']
    );

    // Failing service call
    await userService.registerUserWithInitialPost(
        { email: 'frank-fail@example.com', passwordHash: 'hash6' },
        { title: 'A Failing Post', content: 'This should not be in the DB.' },
        ['ADMIN', 'USER']
    );

    console.log('\n--- Querying Users via Service ---');
    const activeUsers = await userService.getActiveUsers();
    console.log('Active Users:', activeUsers);

    console.log('\n--- Final DB State ---');
    console.log(JSON.stringify(dbConnection.getStore(), null, 2));
})();
</script>