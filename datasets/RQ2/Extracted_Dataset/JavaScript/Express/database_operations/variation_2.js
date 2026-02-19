/*
 * VARIATION 2: The "Service Layer" Architect
 *
 * This developer introduces a Service layer to encapsulate business logic,
 * separating it from the HTTP transport layer (Controllers).
 * - Models: Pure data representation and database interaction (Sequelize).
 * - Services: Contain business logic, orchestrate model operations, handle transactions.
 * - Controllers: Thin layer to handle HTTP requests/responses, calling services.
 * - Routes: Map endpoints to controllers.
 *
 * Key characteristics:
 * - Improved separation of concerns; business logic is not tied to HTTP.
 * - Services are reusable across different contexts (e.g., APIs, background jobs).
 * - Promotes easier testing of business logic in isolation.
 *
 * To run this code:
 * 1. npm install express sequelize sqlite3 uuid
 * 2. node <filename>.js
 */

const express = require('express');
const { Sequelize, DataTypes, Model, Op } = require('sequelize');
const { v4: uuidv4 } = require('uuid');

// --- Database Connection & Models ---
const sequelize = new Sequelize('sqlite::memory:');

class User extends Model {}
User.init({
    id: { type: DataTypes.UUID, primaryKey: true },
    email: { type: DataTypes.STRING, unique: true },
    password_hash: { type: DataTypes.STRING },
    is_active: { type: DataTypes.BOOLEAN, defaultValue: true },
}, { sequelize, modelName: 'User', timestamps: true, underscored: true });

class Post extends Model {}
Post.init({
    id: { type: DataTypes.UUID, primaryKey: true },
    title: { type: DataTypes.STRING },
    content: { type: DataTypes.TEXT },
    status: { type: DataTypes.ENUM('DRAFT', 'PUBLISHED'), defaultValue: 'DRAFT' },
}, { sequelize, modelName: 'Post', timestamps: true, underscored: true });

class Role extends Model {}
Role.init({
    name: { type: DataTypes.STRING, unique: true },
}, { sequelize, modelName: 'Role', timestamps: false });

// Associations
User.hasMany(Post, { foreignKey: 'userId' });
Post.belongsTo(User, { foreignKey: 'userId' });
User.belongsToMany(Role, { through: 'UserRoles', foreignKey: 'userId' });
Role.belongsToMany(User, { through: 'UserRoles', foreignKey: 'roleId' });

const db = { sequelize, User, Post, Role };

// --- SERVICE LAYER (services/userService.js) ---

class UserService {
    constructor(database) {
        this.db = database;
    }

    // Query building with filters
    async findUsers(filter = {}) {
        const whereClause = {};
        if (typeof filter.isActive !== 'undefined') {
            whereClause.is_active = filter.isActive;
        }
        return this.db.User.findAll({ where: whereClause, include: this.db.Role });
    }

    async findUserWithPosts(userId) {
        return this.db.User.findByPk(userId, { include: [this.db.Post, this.db.Role] });
    }

    // Transaction and many-to-many relationship handled in the service
    async createUserWithRoles(userData) {
        const { email, password, roleNames } = userData;
        if (!email || !password || !roleNames || !roleNames.length) {
            throw new Error('Missing required user data for creation.');
        }

        return this.db.sequelize.transaction(async (t) => {
            const newUser = await this.db.User.create({
                id: uuidv4(),
                email,
                password_hash: `hashed_${password}`,
            }, { transaction: t });

            const roles = await this.db.Role.findAll({
                where: { name: { [Op.in]: roleNames } },
                transaction: t,
            });

            if (roles.length !== roleNames.length) {
                throw new Error('Invalid role specified.');
            }

            await newUser.setRoles(roles, { transaction: t });
            return newUser;
        });
    }
}

class PostService {
    constructor(database) {
        this.db = database;
    }

    async createPost(postData) {
        const { userId, title, content } = postData;
        return this.db.Post.create({ id: uuidv4(), userId, title, content });
    }
}

// --- CONTROLLERS (controllers/userController.js) ---

class UserController {
    constructor(userService) {
        this.userService = userService;
    }

    getAllUsers = async (req, res) => {
        try {
            const filter = {};
            if (req.query.active) {
                filter.isActive = req.query.active === 'true';
            }
            const users = await this.userService.findUsers(filter);
            res.status(200).json(users);
        } catch (error) {
            res.status(500).json({ message: error.message });
        }
    };

    createUser = async (req, res) => {
        try {
            const user = await this.userService.createUserWithRoles(req.body);
            // We refetch to get associated roles in the response
            const result = await this.userService.findUserWithPosts(user.id);
            res.status(201).json(result);
        } catch (error) {
            res.status(400).json({ message: error.message });
        }
    };

    getUserById = async (req, res) => {
        try {
            const user = await this.userService.findUserWithPosts(req.params.id);
            if (!user) {
                return res.status(404).json({ message: 'User not found' });
            }
            res.status(200).json(user);
        } catch (error) {
            res.status(500).json({ message: error.message });
        }
    };
}

// --- APP SETUP & ROUTING ---

const app = express();
app.use(express.json());

// Dependency Injection
const userService = new UserService(db);
const postService = new PostService(db);
const userController = new UserController(userService);

// Routes
const router = express.Router();
router.get('/users', userController.getAllUsers);
router.post('/users', userController.createUser);
router.get('/users/:id', userController.getUserById);
router.post('/posts', async (req, res) => {
    try {
        const post = await postService.createPost(req.body);
        res.status(201).json(post);
    } catch (error) {
        res.status(400).json({ message: error.message });
    }
});

app.use('/api', router);
app.get('/', (req, res) => res.send('Variation 2: Service Layer'));

// --- MIGRATION & SERVER START ---
async function initialize() {
    try {
        // In a real app, migrations are run by a CLI tool.
        // This simulates the migration process.
        await db.sequelize.sync({ force: true }); // force:true drops tables if they exist
        console.log('Database schema synchronized.');

        // Seed initial data
        await db.Role.bulkCreate([{ name: 'ADMIN' }, { name: 'USER' }]);
        console.log('Roles seeded.');

        const PORT = 3002;
        app.listen(PORT, () => console.log(`Server running on http://localhost:${PORT}`));
    } catch (error) {
        console.error('Initialization failed:', error);
    }
}

initialize();