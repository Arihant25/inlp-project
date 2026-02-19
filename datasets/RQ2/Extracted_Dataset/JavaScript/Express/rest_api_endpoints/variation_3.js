/*
Variation 3: The Object-Oriented/Class-based Approach
- Style: Uses ES6 classes to structure components, appealing to OOP developers.
- Structure: Code is organized into a Repository (data access), Controller (handlers), and Router.
- Naming: PascalCase for classes, camelCase for methods and instances.
- Features: Encapsulates logic in classes, uses a form of dependency injection (passing repo to controller).
*/

const express = require('express');
const { v4: uuidv4 } = require('uuid');

// --- Data Layer: UserRepository Class ---
class UserRepository {
    constructor() {
        this.users = [
            { id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', email: 'admin@example.com', password_hash: '$2b$10$...', role: 'ADMIN', is_active: true, created_at: new Date('2023-01-01T10:00:00Z') },
            { id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', email: 'user1@example.com', password_hash: '$2b$10$...', role: 'USER', is_active: true, created_at: new Date('2023-01-15T12:30:00Z') },
            { id: 'c3d4e5f6-a7b8-9012-3456-7890abcdef01', email: 'user2@example.com', password_hash: '$2b$10$...', role: 'USER', is_active: false, created_at: new Date('2023-02-20T15:00:00Z') }
        ];
    }

    findAll(filters) {
        return this.users.filter(user => {
            let match = true;
            if (filters.role && user.role !== filters.role) match = false;
            if (filters.is_active !== undefined && user.is_active !== filters.is_active) match = false;
            return match;
        });
    }

    findById(id) {
        return this.users.find(user => user.id === id);
    }

    create(userData) {
        const newUser = {
            id: uuidv4(),
            email: userData.email,
            password_hash: `hashed_${userData.password}`,
            role: userData.role,
            is_active: userData.is_active !== false,
            created_at: new Date(),
        };
        this.users.push(newUser);
        return newUser;
    }

    update(id, updates) {
        const user = this.findById(id);
        if (!user) return null;
        Object.assign(user, updates);
        if (updates.password) {
            user.password_hash = `hashed_${updates.password}`;
            delete user.password;
        }
        return user;
    }

    delete(id) {
        const index = this.users.findIndex(user => user.id === id);
        if (index === -1) return false;
        this.users.splice(index, 1);
        return true;
    }
}

// --- Controller Layer: UserController Class ---
class UserController {
    constructor(userRepository) {
        this.userRepository = userRepository;
    }

    // Using arrow functions to automatically bind `this`
    createUser = (req, res) => {
        const { email, password, role } = req.body;
        if (!email || !password || !role) {
            return res.status(400).json({ message: 'Missing required fields' });
        }
        const newUser = this.userRepository.create(req.body);
        res.status(201).json(newUser);
    };

    getUsers = (req, res) => {
        const { page = 1, limit = 10, role, is_active } = req.query;
        const filters = {};
        if (role) filters.role = role.toUpperCase();
        if (is_active) filters.is_active = is_active === 'true';

        const allMatchingUsers = this.userRepository.findAll(filters);
        const paginatedUsers = allMatchingUsers.slice((page - 1) * limit, page * limit);

        res.status(200).json({
            total: allMatchingUsers.length,
            page: Number(page),
            data: paginatedUsers
        });
    };

    getUserById = (req, res) => {
        const user = this.userRepository.findById(req.params.id);
        if (!user) return res.status(404).json({ message: 'User not found' });
        res.status(200).json(user);
    };

    updateUser = (req, res) => {
        const user = this.userRepository.update(req.params.id, req.body);
        if (!user) return res.status(404).json({ message: 'User not found' });
        res.status(200).json(user);
    };

    deleteUser = (req, res) => {
        const success = this.userRepository.delete(req.params.id);
        if (!success) return res.status(404).json({ message: 'User not found' });
        res.status(204).send();
    };
}

// --- Router Setup ---
const configureUserRouter = (userController) => {
    const router = express.Router();
    router.post('/', userController.createUser);
    router.get('/', userController.getUsers);
    router.get('/:id', userController.getUserById);
    router.put('/:id', userController.updateUser);
    router.delete('/:id', userController.deleteUser);
    return router;
};

// --- Application Entry Point ---
const app = express();
const PORT = process.env.PORT || 3000;

const userRepository = new UserRepository();
const userController = new UserController(userRepository);
const userRouter = configureUserRouter(userController);

app.use(express.json());
app.use('/users', userRouter);

app.listen(PORT, () => {
    console.log(`Variation 3 server running on http://localhost:${PORT}`);
});

// To run this:
// 1. npm install express uuid
// 2. node <filename>.js