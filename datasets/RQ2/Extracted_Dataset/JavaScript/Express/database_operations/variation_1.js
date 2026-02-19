/*
 * VARIATION 1: The "Classic" MVC Developer
 *
 * This developer follows a traditional Model-View-Controller pattern.
 * - Models: Handle data and database logic (Sequelize models).
 * - Controllers: Handle request logic, interacting with models.
 * - Routes: Map HTTP endpoints to controller actions.
 *
 * Key characteristics:
 * - Clear separation of concerns into distinct layers.
 * - Direct interaction between controllers and models.
 * - File structure is typically organized by layer type (e.g., /controllers, /models, /routes).
 * - Uses async/await for clean asynchronous code.
 *
 * To run this code:
 * 1. npm install express sequelize sqlite3 uuid
 * 2. node <filename>.js
 */

const express = require('express');
const { Sequelize, DataTypes, Model, Op } = require('sequelize');
const { v4: uuidv4 } = require('uuid');

// --- In-memory Database Setup ---
const sequelize = new Sequelize('sqlite::memory:');

// --- MIGRATION SIMULATION ---
// In a real app, this would be a separate migration file run by a CLI (e.g., sequelize-cli)
async function runMigration() {
    console.log('Running migrations...');
    const queryInterface = sequelize.getQueryInterface();

    await queryInterface.createTable('Users', {
        id: { type: DataTypes.UUID, primaryKey: true },
        email: { type: DataTypes.STRING, allowNull: false, unique: true },
        password_hash: { type: DataTypes.STRING, allowNull: false },
        is_active: { type: DataTypes.BOOLEAN, defaultValue: true },
        created_at: { type: DataTypes.DATE, defaultValue: Sequelize.NOW },
    });

    await queryInterface.createTable('Posts', {
        id: { type: DataTypes.UUID, primaryKey: true },
        user_id: {
            type: DataTypes.UUID,
            allowNull: false,
            references: { model: 'Users', key: 'id' },
            onDelete: 'CASCADE',
        },
        title: { type: DataTypes.STRING, allowNull: false },
        content: { type: DataTypes.TEXT },
        status: { type: DataTypes.ENUM('DRAFT', 'PUBLISHED'), defaultValue: 'DRAFT' },
    });

    await queryInterface.createTable('Roles', {
        id: { type: DataTypes.INTEGER, primaryKey: true, autoIncrement: true },
        name: { type: DataTypes.STRING, allowNull: false, unique: true },
    });

    await queryInterface.createTable('UserRoles', {
        user_id: {
            type: DataTypes.UUID,
            references: { model: 'Users', key: 'id' },
            onDelete: 'CASCADE',
        },
        role_id: {
            type: DataTypes.INTEGER,
            references: { model: 'Roles', key: 'id' },
            onDelete: 'CASCADE',
        },
        createdAt: { allowNull: false, type: DataTypes.DATE },
        updatedAt: { allowNull: false, type: DataTypes.DATE },
    });
    console.log('Migrations complete.');
}


// --- MODELS (models/*.js) ---

class User extends Model {}
User.init({
    id: { type: DataTypes.UUID, primaryKey: true, defaultValue: DataTypes.UUIDV4 },
    email: { type: DataTypes.STRING, allowNull: false, unique: true },
    password_hash: { type: DataTypes.STRING, allowNull: false },
    is_active: { type: DataTypes.BOOLEAN, defaultValue: true },
    created_at: { type: DataTypes.DATE, field: 'created_at', defaultValue: Sequelize.NOW },
}, { sequelize, modelName: 'User', timestamps: false });

class Post extends Model {}
Post.init({
    id: { type: DataTypes.UUID, primaryKey: true, defaultValue: DataTypes.UUIDV4 },
    user_id: { type: DataTypes.UUID, allowNull: false },
    title: { type: DataTypes.STRING, allowNull: false },
    content: { type: DataTypes.TEXT },
    status: { type: DataTypes.ENUM('DRAFT', 'PUBLISHED'), defaultValue: 'DRAFT' },
}, { sequelize, modelName: 'Post', timestamps: false });

class Role extends Model {}
Role.init({
    id: { type: DataTypes.INTEGER, primaryKey: true, autoIncrement: true },
    name: { type: DataTypes.STRING, allowNull: false, unique: true },
}, { sequelize, modelName: 'Role' });

// --- Associations ---
User.hasMany(Post, { foreignKey: 'user_id' });
Post.belongsTo(User, { foreignKey: 'user_id' });

User.belongsToMany(Role, { through: 'UserRoles', foreignKey: 'user_id' });
Role.belongsToMany(User, { through: 'UserRoles', foreignKey: 'role_id' });


// --- CONTROLLERS (controllers/userController.js) ---

const userController = {
    // Query building with filters
    async getAllUsers(req, res) {
        try {
            const { active } = req.query;
            const whereClause = {};
            if (active === 'true') {
                whereClause.is_active = true;
            } else if (active === 'false') {
                whereClause.is_active = false;
            }
            const users = await User.findAll({ where: whereClause, include: Role });
            res.json(users);
        } catch (error) {
            res.status(500).json({ error: error.message });
        }
    },

    // Transaction and many-to-many relationship
    async createUser(req, res) {
        const { email, password, roleNames } = req.body; // roleNames is an array of strings e.g., ['USER', 'ADMIN']
        if (!email || !password || !roleNames) {
            return res.status(400).json({ error: 'Email, password, and roles are required.' });
        }

        const t = await sequelize.transaction();
        try {
            const newUser = await User.create({
                id: uuidv4(),
                email,
                password_hash: `hashed_${password}`,
            }, { transaction: t });

            const roles = await Role.findAll({ where: { name: { [Op.in]: roleNames } } });
            if (roles.length !== roleNames.length) {
                throw new Error('One or more roles not found.');
            }

            await newUser.setRoles(roles, { transaction: t });

            await t.commit();
            const result = await User.findByPk(newUser.id, { include: Role });
            res.status(201).json(result);
        } catch (error) {
            await t.rollback();
            res.status(500).json({ error: 'Transaction failed: ' + error.message });
        }
    },

    async getUserWithPosts(req, res) {
        try {
            const user = await User.findByPk(req.params.id, {
                include: [Post, Role] // One-to-many and Many-to-many
            });
            if (!user) return res.status(404).json({ error: 'User not found' });
            res.json(user);
        } catch (error) {
            res.status(500).json({ error: error.message });
        }
    },
};

const postController = {
    async createPost(req, res) {
        try {
            const { user_id, title, content } = req.body;
            const post = await Post.create({ id: uuidv4(), user_id, title, content });
            res.status(201).json(post);
        } catch (error) {
            res.status(500).json({ error: error.message });
        }
    },
    async getPosts(req, res) {
        try {
            const posts = await Post.findAll({ include: User });
            res.json(posts);
        } catch (error) {
            res.status(500).json({ error: error.message });
        }
    }
};


// --- ROUTES (routes/userRoutes.js, routes/postRoutes.js) ---

const userRouter = express.Router();
userRouter.get('/', userController.getAllUsers);
userRouter.post('/', userController.createUser);
userRouter.get('/:id', userController.getUserWithPosts);

const postRouter = express.Router();
postRouter.get('/', postController.getPosts);
postRouter.post('/', postController.createPost);


// --- APP SETUP (app.js) ---

const app = express();
app.use(express.json());

app.use('/users', userRouter);
app.use('/posts', postRouter);

app.get('/', (req, res) => res.send('Variation 1: Classic MVC'));

async function startServer() {
    try {
        await runMigration();
        await sequelize.sync(); // sync() is fine for dev, but migrations are for prod

        // Seed data
        await Role.bulkCreate([{ name: 'ADMIN' }, { name: 'USER' }]);

        console.log('Database synchronized.');
        const PORT = 3001;
        app.listen(PORT, () => {
            console.log(`Server is running on http://localhost:${PORT}`);
        });
    } catch (error) {
        console.error('Failed to start server:', error);
    }
}

startServer();