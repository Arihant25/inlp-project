/*
 * VARIATION 4: The "OOP Purist" with Repository Pattern
 *
 * This developer strictly adheres to Object-Oriented principles, using the
 * Repository pattern to abstract data access logic.
 * - Models: Sequelize models, treated as data structures.
 * - Repositories: Classes that encapsulate all database queries for a model.
 * - Controllers: Use repositories to perform actions, unaware of the underlying ORM (Sequelize).
 * - Dependency Injection: Repositories are injected into controllers.
 *
 * Key characteristics:
 * - Strong abstraction; controllers are decoupled from the data source.
 * - Highly testable; repositories can be easily mocked.
 * - Follows SOLID principles (e.g., Single Responsibility, Dependency Inversion).
 * - Code can be more verbose but is very structured and scalable.
 *
 * To run this code:
 * 1. npm install express sequelize sqlite3 uuid
 * 2. node <filename>.js
 */

const express = require('express');
const { Sequelize, DataTypes, Model, Op } = require('sequelize');
const { v4: uuidv4 } = require('uuid');

// --- DATABASE & MODELS ---
const sequelize = new Sequelize('sqlite::memory:');

class User extends Model {}
User.init({
    id: { type: DataTypes.UUID, primaryKey: true, defaultValue: DataTypes.UUIDV4 },
    email: { type: DataTypes.STRING, allowNull: false, unique: true },
    password_hash: { type: DataTypes.STRING, allowNull: false },
    is_active: { type: DataTypes.BOOLEAN, defaultValue: true },
}, { sequelize, modelName: 'User', timestamps: true });

class Post extends Model {}
Post.init({
    id: { type: DataTypes.UUID, primaryKey: true, defaultValue: DataTypes.UUIDV4 },
    title: { type: DataTypes.STRING, allowNull: false },
    content: { type: DataTypes.TEXT },
    status: { type: DataTypes.ENUM('DRAFT', 'PUBLISHED'), defaultValue: 'DRAFT' },
}, { sequelize, modelName: 'Post', timestamps: true });

class Role extends Model {}
Role.init({
    name: { type: DataTypes.STRING, allowNull: false, unique: true },
}, { sequelize, modelName: 'Role', timestamps: false });

User.hasMany(Post, { foreignKey: 'userId', as: 'posts' });
Post.belongsTo(User, { foreignKey: 'userId', as: 'user' });
User.belongsToMany(Role, { through: 'UserRoles', as: 'roles', foreignKey: 'userId' });
Role.belongsToMany(User, { through: 'UserRoles', as: 'users', foreignKey: 'roleId' });

// --- REPOSITORY LAYER ---

class BaseRepository {
    constructor(model) {
        this.model = model;
    }
    findById(id, options = {}) {
        return this.model.findByPk(id, options);
    }
    findAll(options = {}) {
        return this.model.findAll(options);
    }
    create(data, options = {}) {
        return this.model.create(data, options);
    }
}

class UserRepository extends BaseRepository {
    constructor(userModel, roleModel) {
        super(userModel);
        this.roleModel = roleModel;
    }

    // Query building with filters
    findWithFilter(filter) {
        const options = { include: { model: this.roleModel, as: 'roles' } };
        if (filter.isActive !== undefined) {
            options.where = { is_active: filter.isActive };
        }
        return this.findAll(options);
    }

    findByIdWithRelations(id) {
        return this.findById(id, { include: ['posts', 'roles'] });
    }

    // Transaction is managed by the caller (controller/service)
    async createWithRoles(userData, roleNames, transaction) {
        const user = await this.create(userData, { transaction });
        const roles = await this.roleModel.findAll({
            where: { name: { [Op.in]: roleNames } },
            transaction,
        });
        if (roles.length !== roleNames.length) {
            throw new Error('One or more roles not found.');
        }
        await user.setRoles(roles, { transaction });
        return user;
    }
}

class PostRepository extends BaseRepository {
    constructor(postModel) {
        super(postModel);
    }
}

// --- CONTROLLER LAYER ---

class UserController {
    constructor(userRepository) {
        this.userRepository = userRepository;
    }

    getUsers = async (req, res) => {
        try {
            const filter = {};
            if (req.query.active) {
                filter.isActive = req.query.active === 'true';
            }
            const users = await this.userRepository.findWithFilter(filter);
            res.json(users);
        } catch (error) {
            res.status(500).json({ error: error.message });
        }
    };

    getUser = async (req, res) => {
        try {
            const user = await this.userRepository.findByIdWithRelations(req.params.id);
            if (!user) return res.status(404).json({ message: 'User not found' });
            res.json(user);
        } catch (error) {
            res.status(500).json({ error: error.message });
        }
    };

    // Controller manages the transaction and passes it to the repository
    createUser = async (req, res) => {
        const { email, password, roles } = req.body;
        try {
            const result = await sequelize.transaction(async (t) => {
                const userData = { email, password_hash: `hashed_${password}` };
                return this.userRepository.createWithRoles(userData, roles, t);
            });
            const fullUser = await this.userRepository.findByIdWithRelations(result.id);
            res.status(201).json(fullUser);
        } catch (error) {
            res.status(400).json({ error: error.message });
        }
    };
}

// --- APPLICATION SETUP ---

const app = express();
app.use(express.json());

// Dependency Injection Setup
const userRepository = new UserRepository(User, Role);
const postRepository = new PostRepository(Post);
const userController = new UserController(userRepository);

// Routing
app.get('/users', userController.getUsers);
app.post('/users', userController.createUser);
app.get('/users/:id', userController.getUser);
app.post('/posts', async (req, res) => {
    try {
        const { userId, title, content } = req.body;
        const post = await postRepository.create({ userId, title, content });
        res.status(201).json(post);
    } catch (error) {
        res.status(400).json({ error: error.message });
    }
});
app.get('/', (req, res) => res.send('Variation 4: OOP with Repository Pattern'));

// --- MIGRATION & SERVER START ---
const initializeDatabase = async () => {
    // In production, use a migration tool like `sequelize-cli`.
    // `sync({ force: true })` is for development and testing.
    await sequelize.sync({ force: true });
    console.log('Database schema created.');

    // Seed data
    await Role.bulkCreate([{ name: 'ADMIN' }, { name: 'USER' }]);
    console.log('Initial roles seeded.');
};

const startApp = async () => {
    try {
        await initializeDatabase();
        const PORT = 3004;
        app.listen(PORT, () => {
            console.log(`Server is running on http://localhost:${PORT}`);
        });
    } catch (error) {
        console.error('Failed to start application:', error);
        process.exit(1);
    }
};

startApp();