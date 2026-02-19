/*
Variation 1: The Classic Functional Approach
- Style: A very common, straightforward approach with separation of concerns.
- Structure: Modularized into routes, controllers, and services.
- Naming: Standard camelCase.
- Features: Uses express.Router, clear separation of logic, simple custom validation middleware.
*/

const express = require('express');
const { v4: uuidv4 } = require('uuid');

// --- Mock Data Store (simulates a database) ---
let users = [
    { id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', email: 'admin@example.com', password_hash: '$2b$10$...', role: 'ADMIN', is_active: true, created_at: new Date('2023-01-01T10:00:00Z') },
    { id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', email: 'user1@example.com', password_hash: '$2b$10$...', role: 'USER', is_active: true, created_at: new Date('2023-01-15T12:30:00Z') },
    { id: 'c3d4e5f6-a7b8-9012-3456-7890abcdef01', email: 'user2@example.com', password_hash: '$2b$10$...', role: 'USER', is_active: false, created_at: new Date('2023-02-20T15:00:00Z') }
];

// --- Service Layer (data access logic) ---
const userService = {
    findAll: ({ page = 1, limit = 10, role, is_active }) => {
        let filteredUsers = [...users];

        if (role) {
            filteredUsers = filteredUsers.filter(u => u.role === role.toUpperCase());
        }
        if (is_active !== undefined) {
            filteredUsers = filteredUsers.filter(u => u.is_active === (is_active === 'true'));
        }

        const startIndex = (page - 1) * limit;
        const endIndex = page * limit;
        const paginatedUsers = filteredUsers.slice(startIndex, endIndex);

        return {
            totalItems: filteredUsers.length,
            totalPages: Math.ceil(filteredUsers.length / limit),
            currentPage: page,
            users: paginatedUsers
        };
    },
    findById: (id) => users.find(user => user.id === id),
    create: (userData) => {
        const newUser = {
            id: uuidv4(),
            ...userData,
            password_hash: `hashed_${userData.password}`, // Simulate hashing
            is_active: userData.is_active !== undefined ? userData.is_active : true,
            created_at: new Date()
        };
        delete newUser.password;
        users.push(newUser);
        return newUser;
    },
    update: (id, updates) => {
        const userIndex = users.findIndex(user => user.id === id);
        if (userIndex === -1) return null;

        users[userIndex] = { ...users[userIndex], ...updates };
        if (updates.password) {
            users[userIndex].password_hash = `hashed_${updates.password}`;
            delete users[userIndex].password;
        }
        return users[userIndex];
    },
    remove: (id) => {
        const userIndex = users.findIndex(user => user.id === id);
        if (userIndex === -1) return false;
        users.splice(userIndex, 1);
        return true;
    }
};

// --- Controller Layer (request handling logic) ---
const userController = {
    createUser: (req, res) => {
        const newUser = userService.create(req.body);
        res.status(201).json(newUser);
    },
    getAllUsers: (req, res) => {
        const { page, limit, role, is_active } = req.query;
        const result = userService.findAll({ 
            page: parseInt(page) || 1, 
            limit: parseInt(limit) || 10, 
            role, 
            is_active 
        });
        res.status(200).json(result);
    },
    getUserById: (req, res) => {
        const user = userService.findById(req.params.id);
        if (!user) {
            return res.status(404).json({ message: 'User not found' });
        }
        res.status(200).json(user);
    },
    updateUser: (req, res) => {
        const updatedUser = userService.update(req.params.id, req.body);
        if (!updatedUser) {
            return res.status(404).json({ message: 'User not found' });
        }
        res.status(200).json(updatedUser);
    },
    deleteUser: (req, res) => {
        const success = userService.remove(req.params.id);
        if (!success) {
            return res.status(404).json({ message: 'User not found' });
        }
        res.status(204).send();
    }
};

// --- Validation Middleware ---
const validateUserCreation = (req, res, next) => {
    const { email, password, role } = req.body;
    if (!email || !password || !role) {
        return res.status(400).json({ message: 'Email, password, and role are required' });
    }
    if (!['ADMIN', 'USER'].includes(role.toUpperCase())) {
        return res.status(400).json({ message: 'Invalid role specified' });
    }
    next();
};

// --- Router ---
const userRouter = express.Router();

userRouter.post('/', validateUserCreation, userController.createUser);
userRouter.get('/', userController.getAllUsers);
userRouter.get('/:id', userController.getUserById);
userRouter.put('/:id', userController.updateUser);
userRouter.delete('/:id', userController.deleteUser);


// --- Server Setup ---
const app = express();
const PORT = process.env.PORT || 3000;

app.use(express.json());
app.use('/users', userRouter);

app.listen(PORT, () => {
    console.log(`Variation 1 server running on http://localhost:${PORT}`);
});

// To run this:
// 1. npm install express uuid
// 2. node <filename>.js