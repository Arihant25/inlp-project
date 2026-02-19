/*
 * VARIATION 3: The "Enterprise Traditionalist"
 * Style: Classic OOP/ActiveRecord, MVC-like structure.
 * Tech: Fastify, Sequelize, SQLite (in-memory)
 * Organization:
 *   - config/ (database connection)
 *   - models/ (model definitions and associations)
 *   - controllers/ (request/response logic)
 *   - routes/ (route wiring)
 *   - server.js (entry point)
 */

// --- package.json ---
/*
{
  "name": "variation-3-sequelize",
  "version": "1.0.0",
  "main": "server.js",
  "dependencies": {
    "fastify": "^4.26.2",
    "sequelize": "^6.37.3",
    "sqlite3": "^5.1.7",
    "uuid": "^4.0.0"
  }
}
*/

const Fastify = require('fastify');
const { Sequelize, DataTypes, Model, Op } = require('sequelize');
const { v4: uuidv4 } = require('uuid');

// --- 1. Database Config (config/database.js) ---
const sequelize = new Sequelize('sqlite::memory:', { logging: false });

// --- 2. Model Definitions (models/index.js) ---
class User extends Model {}
User.init({
  id: { type: DataTypes.UUID, defaultValue: DataTypes.UUIDV4, primaryKey: true },
  email: { type: DataTypes.STRING, unique: true, allowNull: false },
  password_hash: { type: DataTypes.STRING, allowNull: false },
  is_active: { type: DataTypes.BOOLEAN, defaultValue: true },
}, { sequelize, modelName: 'User', underscored: true });

class Post extends Model {}
Post.init({
  id: { type: DataTypes.UUID, defaultValue: DataTypes.UUIDV4, primaryKey: true },
  title: { type: DataTypes.STRING, allowNull: false },
  content: { type: DataTypes.TEXT },
  status: { type: DataTypes.ENUM('DRAFT', 'PUBLISHED'), defaultValue: 'DRAFT' },
}, { sequelize, modelName: 'Post', underscored: true });

class Role extends Model {}
Role.init({
  id: { type: DataTypes.UUID, defaultValue: DataTypes.UUIDV4, primaryKey: true },
  name: { type: DataTypes.STRING, unique: true, allowNull: false },
}, { sequelize, modelName: 'Role', underscored: true });

// Define relationships
User.hasMany(Post, { foreignKey: 'user_id' });
Post.belongsTo(User, { foreignKey: 'user_id' });

const UserRoles = sequelize.define('UserRoles', {}, { timestamps: false, underscored: true });
User.belongsToMany(Role, { through: UserRoles });
Role.belongsToMany(User, { through: UserRoles });

// --- 3. Controller Logic (controllers/userController.js) ---
const userController = {
  // CREATE with Transaction
  create: async (request, reply) => {
    const { email, password_hash, roleNames } = request.body;
    const t = await sequelize.transaction();
    try {
      const user = await User.create({ email, password_hash }, { transaction: t });

      if (roleNames && roleNames.length) {
        const roles = await Role.findAll({ where: { name: { [Op.in]: roleNames } }, transaction: t });
        await user.setRoles(roles, { transaction: t });
      }

      await t.commit();
      const result = await User.findByPk(user.id, { include: Role });
      reply.code(201).send(result);
    } catch (error) {
      await t.rollback();
      reply.code(500).send({ message: "Failed to create user", error: error.message });
    }
  },

  // READ with Query Building
  list: async (request, reply) => {
    const { is_active, with_posts } = request.query;
    const options = { where: {}, include: [] };

    if (is_active !== undefined) {
      options.where.is_active = is_active === 'true';
    }
    if (with_posts === 'true') {
      options.include.push(Post);
    }
    
    const users = await User.findAll(options);
    reply.send(users);
  },

  getById: async (request, reply) => {
    const user = await User.findByPk(request.params.id, {
      include: [Post, Role]
    });
    if (!user) {
      return reply.code(404).send({ message: 'User not found' });
    }
    reply.send(user);
  },
};

// --- 4. Route Definitions (routes/index.js) ---
function apiRoutes(fastify, options, done) {
  fastify.post('/users', userController.create);
  fastify.get('/users', userController.list);
  fastify.get('/users/:id', userController.getById);
  done();
}

// --- 5. Server Entry Point (server.js) ---
const server = Fastify({ logger: true });

const initializeDatabase = async () => {
  try {
    // This is a representation of migrations for a self-contained example.
    // In production, you'd use Sequelize-CLI.
    await sequelize.sync({ force: true });
    server.log.info('Database synchronized.');

    // Seed data
    const [adminRole, userRole] = await Role.bulkCreate([
      { name: 'ADMIN' },
      { name: 'USER' }
    ]);
    const user1 = await User.create({ email: 'test@example.com', password_hash: '...' });
    await Post.create({ user_id: user1.id, title: 'My First Sequelize Post', content: 'Hello!' });
    await user1.addRole(userRole);
    server.log.info('Database seeded.');
  } catch (error) {
    server.log.error('Failed to initialize database:', error);
    throw error;
  }
};

const start = async () => {
  try {
    await initializeDatabase();
    server.register(apiRoutes, { prefix: '/api' });
    await server.listen({ port: 3002 });
  } catch (err) {
    server.log.error(err);
    process.exit(1);
  }
};

if (require.main === module) {
  start();
} else {
  module.exports = { server, sequelize, User, Post, Role };
}