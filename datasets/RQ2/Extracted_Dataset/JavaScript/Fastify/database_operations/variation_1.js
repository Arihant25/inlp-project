/*
 * VARIATION 1: The "Modern Classic"
 * Style: OOP with ActiveRecord-like models, clear separation of concerns.
 * Tech: Fastify, Objection.js, Knex.js, SQLite (in-memory)
 * Organization:
 *   - db/ (setup and migrations)
 *   - models/ (Objection.js models)
 *   - routes/ (Fastify plugins for each resource)
 *   - server.js (entry point)
 */

// --- package.json ---
/*
{
  "name": "variation-1-objection",
  "version": "1.0.0",
  "main": "server.js",
  "dependencies": {
    "fastify": "^4.26.2",
    "knex": "^3.1.0",
    "objection": "^3.1.4",
    "sqlite3": "^5.1.7",
    "uuid": "^9.0.1"
  }
}
*/

const Fastify = require('fastify');
const Knex = require('knex');
const { Model, transaction } = require('objection');
const { v4: uuidv4 } = require('uuid');

// --- 1. Database Setup (db/knex.js) ---
const knexConfig = {
  client: 'sqlite3',
  connection: {
    filename: ':memory:',
  },
  useNullAsDefault: true,
};
const knex = Knex(knexConfig);
Model.knex(knex); // Bind all Objection.js models to this Knex instance

// --- 2. Model Definitions (models/) ---

// Base model for common functionality like UUIDs
class BaseModel extends Model {
  $beforeInsert() {
    if (!this.id) {
      this.id = uuidv4();
    }
    this.created_at = new Date().toISOString();
  }
}

class User extends BaseModel {
  static get tableName() {
    return 'users';
  }

  static get relationMappings() {
    return {
      posts: {
        relation: Model.HasManyRelation,
        modelClass: Post,
        join: {
          from: 'users.id',
          to: 'posts.user_id',
        },
      },
      roles: {
        relation: Model.ManyToManyRelation,
        modelClass: Role,
        join: {
          from: 'users.id',
          through: {
            from: 'user_roles.user_id',
            to: 'user_roles.role_id',
          },
          to: 'roles.id',
        },
      },
    };
  }
}

class Post extends BaseModel {
  static get tableName() {
    return 'posts';
  }
}

class Role extends BaseModel {
  static get tableName() {
    return 'roles';
  }
}

// --- 3. Route Definitions (routes/userRoutes.js) ---
async function userRoutes(fastify, options) {
  // CREATE User with Roles (Transaction Example)
  fastify.post('/users', async (request, reply) => {
    const { email, password_hash, role_names } = request.body;
    try {
      const newUser = await transaction(User.knex(), async (trx) => {
        const user = await User.query(trx).insertAndFetch({
          email,
          password_hash,
          is_active: true,
        });

        if (role_names && role_names.length > 0) {
          const roles = await Role.query(trx).whereIn('name', role_names);
          for (const role of roles) {
            await user.$relatedQuery('roles', trx).relate(role.id);
          }
        }
        return user;
      });
      // Refetch to get relations
      const userWithRoles = await User.query().findById(newUser.id).withGraphFetched('roles');
      return reply.code(201).send(userWithRoles);
    } catch (err) {
      fastify.log.error(err);
      return reply.code(500).send({ error: 'Transaction failed', details: err.message });
    }
  });

  // READ Users with Filters and Relations (Query Building Example)
  fastify.get('/users', async (request, reply) => {
    const { is_active, with_posts } = request.query;
    const query = User.query();

    if (is_active !== undefined) {
      query.where('is_active', is_active === 'true');
    }

    if (with_posts === 'true') {
      query.withGraphFetched('posts');
    }

    return query;
  });

  // READ one User
  fastify.get('/users/:id', async (request, reply) => {
    const user = await User.query().findById(request.params.id).withGraphFetched('[posts, roles]');
    if (!user) {
      return reply.code(404).send({ error: 'User not found' });
    }
    return user;
  });
}

// --- 4. Server Entry Point (server.js) ---
const server = Fastify({ logger: true });

// Database Migration Function
async function runMigrations() {
  await knex.schema.createTable('users', (table) => {
    table.uuid('id').primary();
    table.string('email').unique().notNullable();
    table.string('password_hash').notNullable();
    table.boolean('is_active').defaultTo(true);
    table.timestamp('created_at').defaultTo(knex.fn.now());
  });
  server.log.info('Table "users" created.');

  await knex.schema.createTable('posts', (table) => {
    table.uuid('id').primary();
    table.uuid('user_id').references('id').inTable('users').onDelete('CASCADE');
    table.string('title').notNullable();
    table.text('content');
    table.enum('status', ['DRAFT', 'PUBLISHED']).defaultTo('DRAFT');
    table.timestamp('created_at').defaultTo(knex.fn.now());
  });
  server.log.info('Table "posts" created.');

  await knex.schema.createTable('roles', (table) => {
    table.uuid('id').primary();
    table.string('name').unique().notNullable(); // e.g., 'ADMIN', 'USER'
    table.timestamp('created_at').defaultTo(knex.fn.now());
  });
  server.log.info('Table "roles" created.');

  await knex.schema.createTable('user_roles', (table) => {
    table.primary(['user_id', 'role_id']);
    table.uuid('user_id').references('id').inTable('users').onDelete('CASCADE');
    table.uuid('role_id').references('id').inTable('roles').onDelete('CASCADE');
  });
  server.log.info('Table "user_roles" created.');

  // Seed data
  const adminRole = await Role.query().insertAndFetch({ name: 'ADMIN' });
  const userRole = await Role.query().insertAndFetch({ name: 'USER' });
  const user1 = await User.query().insertAndFetch({ email: 'test@example.com', password_hash: '...' });
  await Post.query().insert({ user_id: user1.id, title: 'My First Post', content: 'Hello World!' });
  await user1.$relatedQuery('roles').relate(userRole);
}

const start = async () => {
  try {
    await runMigrations();
    server.register(userRoutes);
    await server.listen({ port: 3000 });
  } catch (err) {
    server.log.error(err);
    process.exit(1);
  }
};

// To make this snippet runnable, we check if it's the main module
if (require.main === module) {
  start();
} else {
  // For testing purposes
  module.exports = { server, knex, User, Post, Role };
}