/*
 * VARIATION 4: The "Pragmatic Minimalist"
 * Style: Functional, procedural, no ORM "magic". Explicit data access layer.
 * Tech: Fastify, Knex.js (as a query builder), SQLite (in-memory)
 * Organization:
 *   - db.js (Knex instance and migrations)
 *   - repository.js (Data access functions)
 *   - server.js (Main file with routes calling the repository)
 * Naming: snake_case for DB columns, camelCase for JS variables.
 */

// --- package.json ---
/*
{
  "name": "variation-4-knex-query-builder",
  "version": "1.0.0",
  "main": "server.js",
  "dependencies": {
    "fastify": "^4.26.2",
    "knex": "^3.1.0",
    "sqlite3": "^5.1.7",
    "uuid": "^9.0.1"
  }
}
*/

const Fastify = require('fastify');
const Knex = require('knex');
const { v4: uuidv4 } = require('uuid');

// --- 1. Database Setup (db.js) ---
const knex = Knex({
  client: 'sqlite3',
  connection: { filename: ':memory:' },
  useNullAsDefault: true,
});

// Migration logic
const runDbMigrations = async (log) => {
  await knex.schema.createTable('users', (table) => {
    table.uuid('id').primary();
    table.string('email').unique().notNullable();
    table.string('password_hash').notNullable();
    table.boolean('is_active').defaultTo(true);
    table.timestamp('created_at').defaultTo(knex.fn.now());
  });
  log.info('Table "users" created.');

  await knex.schema.createTable('posts', (table) => {
    table.uuid('id').primary();
    table.uuid('user_id').references('id').inTable('users').onDelete('CASCADE');
    table.string('title').notNullable();
    table.text('content');
    table.enum('status', ['DRAFT', 'PUBLISHED']).defaultTo('DRAFT');
    table.timestamp('created_at').defaultTo(knex.fn.now());
  });
  log.info('Table "posts" created.');

  await knex.schema.createTable('roles', (table) => {
    table.uuid('id').primary();
    table.string('name').unique().notNullable();
    table.timestamp('created_at').defaultTo(knex.fn.now());
  });
  log.info('Table "roles" created.');

  await knex.schema.createTable('user_roles', (table) => {
    table.primary(['user_id', 'role_id']);
    table.uuid('user_id').references('id').inTable('users').onDelete('CASCADE');
    table.uuid('role_id').references('id').inTable('roles').onDelete('CASCADE');
  });
  log.info('Table "user_roles" created.');

  // Seed data
  const [adminRoleId] = await knex('roles').insert({ id: uuidv4(), name: 'ADMIN' }).returning('id');
  const [userRoleId] = await knex('roles').insert({ id: uuidv4(), name: 'USER' }).returning('id');
  const [userId] = await knex('users').insert({ id: uuidv4(), email: 'test@example.com', password_hash: '...' }).returning('id');
  await knex('posts').insert({ id: uuidv4(), user_id: userId.id, title: 'Knex Post', content: 'Hello from Knex!' });
  await knex('user_roles').insert({ user_id: userId.id, role_id: userRoleId.id });
  log.info('Database seeded.');
};

// --- 2. Data Access Layer (repository.js) ---
const userRepository = {
  // Transaction Example
  createUserWithRoles: async (db, { email, password_hash, roleNames }) => {
    return db.transaction(async trx => {
      const user = { id: uuidv4(), email, password_hash };
      const [insertedUser] = await trx('users').insert(user).returning('*');

      if (roleNames && roleNames.length > 0) {
        const roles = await trx('roles').whereIn('name', roleNames).select('id');
        if (roles.length > 0) {
          const userRoles = roles.map(role => ({ user_id: insertedUser.id, role_id: role.id }));
          await trx('user_roles').insert(userRoles);
        }
      }
      return insertedUser;
    });
  },

  // Query Building Example
  findUsers: async (db, { isActive, withPosts }) => {
    let query = db('users').select('*');
    if (isActive !== undefined) {
      query = query.where('is_active', isActive);
    }
    const users = await query;

    if (withPosts && users.length > 0) {
      const userIds = users.map(u => u.id);
      const posts = await db('posts').whereIn('user_id', userIds);
      // Manually map posts to users
      users.forEach(user => {
        user.posts = posts.filter(p => p.user_id === user.id);
      });
    }
    return users;
  },

  // Manual Joins for M-M relationship
  findById: async (db, id) => {
    const user = await db('users').where({ id }).first();
    if (!user) return null;

    const posts = await db('posts').where({ user_id: id });
    const roles = await db('roles')
      .join('user_roles', 'roles.id', '=', 'user_roles.role_id')
      .where('user_roles.user_id', id)
      .select('roles.name');
      
    return { ...user, posts, roles };
  },
};

// --- 3. Server and Routes (server.js) ---
const server = Fastify({ logger: true });

// Decorate server with db instance for easy access in routes
server.decorate('db', knex);
server.decorate('repo', userRepository);

server.post('/users', async (request, reply) => {
  try {
    const user = await server.repo.createUserWithRoles(server.db, request.body);
    // We don't get roles back from the insert, so we'd need another query for that.
    // For simplicity, we return the created user object.
    reply.code(201).send(user);
  } catch (err) {
    server.log.error(err);
    reply.code(500).send({ error: 'Database transaction failed' });
  }
});

server.get('/users', async (request, reply) => {
  const filters = {
    isActive: request.query.is_active ? request.query.is_active === 'true' : undefined,
    withPosts: request.query.with_posts === 'true',
  };
  const users = await server.repo.findUsers(server.db, filters);
  return users;
});

server.get('/users/:id', async (request, reply) => {
  const user = await server.repo.findById(server.db, request.params.id);
  if (!user) {
    return reply.code(404).send({ error: 'User not found' });
  }
  return user;
});

const start = async () => {
  try {
    await runDbMigrations(server.log);
    await server.listen({ port: 3003 });
  } catch (err) {
    server.log.error(err);
    process.exit(1);
  }
};

if (require.main === module) {
  start();
} else {
  module.exports = { server, knex, userRepository };
}