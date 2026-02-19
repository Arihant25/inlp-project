/*
Variation 4: The Modern Async/Await with Joi Validation
- Style: Focuses on modern JS (async/await) and robust, schema-based validation.
- Structure: Feature-based folder structure (`api/users`), with separate files for router, controller, service, and validation schemas.
- Naming: Standard camelCase.
- Features: Uses async/await, Joi for validation, and a centralized error handler.
*/

const express = require('express');
const { v4: uuidv4 } = require('uuid');
const Joi = require('joi');

// --- Mock Data Store & Service (simulating async DB calls) ---
const db = {
    users: [
        { id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', email: 'admin@example.com', password_hash: '$2b$10$...', role: 'ADMIN', is_active: true, created_at: new Date('2023-01-01T10:00:00Z') },
        { id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', email: 'user1@example.com', password_hash: '$2b$10$...', role: 'USER', is_active: true, created_at: new Date('2023-01-15T12:30:00Z') },
        { id: 'c3d4e5f6-a7b8-9012-3456-7890abcdef01', email: 'user2@example.com', password_hash: '$2b$10$...', role: 'USER', is_active: false, created_at: new Date('2023-02-20T15:00:00Z') }
    ]
};

const userService = {
    // Simulating async operations with Promises
    find: async ({ page, limit, role, isActive }) => new Promise(resolve => {
        setTimeout(() => {
            let results = db.users.filter(u => 
                (role ? u.role === role : true) && 
                (isActive !== undefined ? u.is_active === isActive : true)
            );
            const paginated = results.slice((page - 1) * limit, page * limit);
            resolve({ total: results.length, users: paginated });
        }, 50);
    }),
    findById: async (id) => new Promise(resolve => setTimeout(() => resolve(db.users.find(u => u.id === id)), 50)),
    create: async (data) => new Promise(resolve => {
        setTimeout(() => {
            const newUser = {
                id: uuidv4(),
                email: data.email,
                password_hash: `hashed_${data.password}`,
                role: data.role,
                is_active: data.is_active ?? true,
                created_at: new Date()
            };
            db.users.push(newUser);
            resolve(newUser);
        }, 50);
    }),
    update: async (id, data) => new Promise(resolve => {
        setTimeout(() => {
            const index = db.users.findIndex(u => u.id === id);
            if (index === -1) return resolve(null);
            db.users[index] = { ...db.users[index], ...data };
            resolve(db.users[index]);
        }, 50);
    }),
    delete: async (id) => new Promise(resolve => {
        setTimeout(() => {
            const initialLength = db.users.length;
            db.users = db.users.filter(u => u.id !== id);
            resolve(db.users.length < initialLength);
        }, 50);
    })
};

// --- Validation Schemas (using Joi) ---
const userSchemas = {
    createUser: Joi.object({
        email: Joi.string().email().required(),
        password: Joi.string().min(8).required(),
        role: Joi.string().valid('ADMIN', 'USER').required(),
        is_active: Joi.boolean().optional()
    }),
    updateUser: Joi.object({
        email: Joi.string().email(),
        role: Joi.string().valid('ADMIN', 'USER'),
        is_active: Joi.boolean()
    }).min(1), // At least one field must be provided
    listUsers: Joi.object({
        page: Joi.number().integer().min(1).default(1),
        limit: Joi.number().integer().min(1).max(100).default(10),
        role: Joi.string().valid('ADMIN', 'USER'),
        is_active: Joi.boolean()
    })
};

// --- Generic Validation Middleware ---
const validate = (schema, property) => (req, res, next) => {
    const { error, value } = schema.validate(req[property], { abortEarly: false, stripUnknown: true });
    if (error) {
        const errors = error.details.map(detail => detail.message);
        return res.status(400).json({ errors });
    }
    req[property] = value; // Use validated and sanitized value
    next();
};

// --- Controller ---
const userController = {
    create: async (req, res, next) => {
        try {
            const newUser = await userService.create(req.body);
            res.status(201).json(newUser);
        } catch (err) { next(err); }
    },
    list: async (req, res, next) => {
        try {
            const { page, limit, role, is_active: isActive } = req.query;
            const result = await userService.find({ page, limit, role, isActive });
            res.status(200).json({
                currentPage: page,
                totalPages: Math.ceil(result.total / limit),
                ...result
            });
        } catch (err) { next(err); }
    },
    getById: async (req, res, next) => {
        try {
            const user = await userService.findById(req.params.id);
            if (!user) return res.status(404).json({ message: 'User not found' });
            res.status(200).json(user);
        } catch (err) { next(err); }
    },
    update: async (req, res, next) => {
        try {
            const updatedUser = await userService.update(req.params.id, req.body);
            if (!updatedUser) return res.status(404).json({ message: 'User not found' });
            res.status(200).json(updatedUser);
        } catch (err) { next(err); }
    },
    delete: async (req, res, next) => {
        try {
            const success = await userService.delete(req.params.id);
            if (!success) return res.status(404).json({ message: 'User not found' });
            res.status(204).send();
        } catch (err) { next(err); }
    }
};

// --- Router ---
const userRouter = express.Router();
userRouter.post('/', validate(userSchemas.createUser, 'body'), userController.create);
userRouter.get('/', validate(userSchemas.listUsers, 'query'), userController.list);
userRouter.get('/:id', userController.getById);
userRouter.put('/:id', validate(userSchemas.updateUser, 'body'), userController.update);
userRouter.delete('/:id', userController.delete);

// --- App Setup ---
const app = express();
const PORT = process.env.PORT || 3000;

app.use(express.json());
app.use('/users', userRouter);

// Centralized Error Handler
app.use((err, req, res, next) => {
    console.error(err.stack);
    res.status(500).json({ message: 'An internal server error occurred' });
});

app.listen(PORT, () => {
    console.log(`Variation 4 server running on http://localhost:${PORT}`);
});

// To run this:
// 1. npm install express uuid joi
// 2. node <filename>.js