/*
 * VARIATION 3: The "Functional & Modular" Developer (using Knex.js)
 *
 * This developer prefers a functional approach, avoiding classes and `this`.
 * They use a query builder like Knex.js for more direct SQL control.
 * The code is organized by feature/domain (e.g., a `users` module).
 *
 * Key characteristics:
 * - Use of pure functions and modules.
 * - Prefers query builders (Knex.js) over full ORMs for flexibility.
 * - Code is co-located by feature, not by layer type.
 * - Lean and direct style.
 *
 * To run this code:
 * 1. npm install express knex sqlite3 uuid
 * 2. node <filename>.js
 */

const express = require('express');
const knex = require('knex');
const { v4: uuidv4 } = require('uuid');

// --- DATABASE SETUP (db.js) ---
const db = knex({
    client: 'sqlite3',
    connection: {
        filename: ':memory:',
    },
    useNullAsDefault: true,
});

// --- MIGRATIONS (migrations/create_tables.js) ---
const runMigrations = async () => {
    console.log('Running migrations...');
    await db.schema
        .createTable('users', (table) => {
            table.uuid('id').primary();
            table.string('email').notNullable().unique();
            table.string('password_hash').notNullable();
            table.boolean('is_active').defaultTo(true);
            table.timestamp('created_at').defaultTo(db.fn.now());
        })
        .createTable('posts', (table) => {
            table.uuid('id').primary();
            table.uuid('user_id').references('id').inTable('users').onDelete('CASCADE');
            table.string('title').notNullable();
            table.text('content');
            table.enum('status', ['DRAFT', 'PUBLISHED']).defaultTo('DRAFT');
        })
        .createTable('roles', (table) => {
            table.increments('id').primary();
            table.string('name').notNullable().unique();
        })
        .createTable('user_roles', (table) => {
            table.uuid('user_id').references('id').inTable('users').onDelete('CASCADE');
            table.integer('role_id').references('id').inTable('roles').onDelete('CASCADE');
            table.primary(['user_id', 'role_id']);
        });
    console.log('Migrations complete.');
};

// --- DATA ACCESS & LOGIC (modules/users.js) ---

// Query building with filters
const findUsers = (filter) => {
    const query = db('users').select('id', 'email', 'is_active');
    if (filter.isActive === 'true') {
        query.where('is_active', true);
    }
    if (filter.isActive === 'false') {
        query.where('is_active', false);
    }
    return query;
};

const findUserById = (id) => {
    return db('users').where({ id }).first();
};

// One-to-many relationship
const findPostsByUserId = (userId) => {
    return db('posts').where({ user_id: userId });
};

// Many-to-many relationship
const findRolesByUserId = (userId) => {
    return db('user_roles')
        .join('roles', 'user_roles.role_id', '=', 'roles.id')
        .where('user_roles.user_id', userId)
        .select('roles.name');
};

// Transaction and rollback
const createUserWithRoles = async (email, password, roleNames) => {
    const newUser = {
        id: uuidv4(),
        email,
        password_hash: `hashed_${password}`,
    };

    return db.transaction(async (trx) => {
        const [insertedUser] = await trx('users').insert(newUser).returning('*');

        const roles = await trx('roles').whereIn('name', roleNames);
        if (roles.length !== roleNames.length) {
            throw new Error('One or more roles not found.');
        }

        const userRoleLinks = roles.map(role => ({
            user_id: insertedUser.id,
            role_id: role.id,
        }));

        await trx('user_roles').insert(userRoleLinks);

        return insertedUser;
    });
};

// --- ROUTES (routes/api.js) ---
const apiRouter = express.Router();

// User Routes
apiRouter.get('/users', async (req, res) => {
    try {
        const users = await findUsers(req.query);
        res.json(users);
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
});

apiRouter.post('/users', async (req, res) => {
    try {
        const { email, password, roles } = req.body;
        const user = await createUserWithRoles(email, password, roles);
        res.status(201).json(user);
    } catch (err) {
        res.status(400).json({ error: err.message });
    }
});

apiRouter.get('/users/:id', async (req, res) => {
    try {
        const user = await findUserById(req.params.id);
        if (!user) return res.status(404).send();
        const posts = await findPostsByUserId(req.params.id);
        const roles = await findRolesByUserId(req.params.id);
        res.json({ ...user, posts, roles: roles.map(r => r.name) });
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
});

// Post Routes
apiRouter.post('/posts', async (req, res) => {
    try {
        const { user_id, title, content } = req.body;
        const newPost = { id: uuidv4(), user_id, title, content };
        const [post] = await db('posts').insert(newPost).returning('*');
        res.status(201).json(post);
    } catch (err) {
        res.status(400).json({ error: err.message });
    }
});

// --- APP SETUP (server.js) ---
const app = express();
app.use(express.json());
app.use('/api', apiRouter);
app.get('/', (req, res) => res.send('Variation 3: Functional & Modular'));

const startServer = async () => {
    try {
        await runMigrations();
        // Seed data
        await db('roles').insert([{ name: 'ADMIN' }, { name: 'USER' }]);
        console.log('Roles seeded.');

        const PORT = 3003;
        app.listen(PORT, () => {
            console.log(`Server running on http://localhost:${PORT}`);
        });
    } catch (err) {
        console.error('Failed to start server:', err);
    }
};

startServer();