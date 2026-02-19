/*
Variation 2: The All-in-One Module
- Style: A condensed, single-file approach suitable for smaller services or prototypes.
- Structure: All logic (data, routing, handlers) is contained within one file.
- Naming: Mix of snake_case for variables and camelCase for functions.
- Features: Defines routes directly on the `app` object. Logic is inline within route handlers.
*/

const express = require('express');
const { v4: uuidv4 } = require('uuid');

// --- Express App Initialization ---
const app = express();
app.use(express.json()); // Middleware to parse JSON bodies

const PORT = process.env.PORT || 3000;

// --- In-Memory Database ---
let all_users = [
    { id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', email: 'admin@example.com', password_hash: '$2b$10$...', role: 'ADMIN', is_active: true, created_at: new Date('2023-01-01T10:00:00Z') },
    { id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', email: 'user1@example.com', password_hash: '$2b$10$...', role: 'USER', is_active: true, created_at: new Date('2023-01-15T12:30:00Z') },
    { id: 'c3d4e5f6-a7b8-9012-3456-7890abcdef01', email: 'user2@example.com', password_hash: '$2b$10$...', role: 'USER', is_active: false, created_at: new Date('2023-02-20T15:00:00Z') }
];

// --- API Endpoints ---

// [POST] /users - Create a new user
app.post('/users', (req, res) => {
    const { email, password, role, is_active } = req.body;

    if (!email || !password) {
        return res.status(400).json({ error: 'Email and password are required.' });
    }

    const new_user = {
        id: uuidv4(),
        email,
        password_hash: `hashed_${password}_${Date.now()}`,
        role: role || 'USER',
        is_active: is_active !== undefined ? is_active : true,
        created_at: new Date()
    };

    all_users.push(new_user);
    res.status(201).json(new_user);
});

// [GET] /users - List all users with pagination and filtering
app.get('/users', (req, res) => {
    const { page = 1, limit = 10, role, is_active } = req.query;

    let results = [...all_users];

    // Filtering
    if (role) {
        results = results.filter(u => u.role.toLowerCase() === role.toLowerCase());
    }
    if (is_active) {
        results = results.filter(u => u.is_active.toString() === is_active);
    }

    // Pagination
    const page_num = parseInt(page, 10);
    const limit_num = parseInt(limit, 10);
    const start_index = (page_num - 1) * limit_num;
    const end_index = page_num * limit_num;

    const paginated_results = results.slice(start_index, end_index);

    res.status(200).json({
        total: results.length,
        page: page_num,
        limit: limit_num,
        data: paginated_results
    });
});

// [GET] /users/:id - Get a single user by ID
app.get('/users/:id', (req, res) => {
    const { id } = req.params;
    const user = all_users.find(u => u.id === id);

    if (!user) {
        return res.status(404).json({ error: 'User not found' });
    }
    res.status(200).json(user);
});

// [PATCH] /users/:id - Update a user (partial update)
app.patch('/users/:id', (req, res) => {
    const { id } = req.params;
    const user_index = all_users.findIndex(u => u.id === id);

    if (user_index === -1) {
        return res.status(404).json({ error: 'User not found' });
    }

    const original_user = all_users[user_index];
    const updated_user = { ...original_user, ...req.body };

    // Ensure non-updatable fields are preserved
    updated_user.id = original_user.id;
    updated_user.created_at = original_user.created_at;

    if (req.body.password) {
        updated_user.password_hash = `hashed_${req.body.password}_${Date.now()}`;
        delete updated_user.password;
    }

    all_users[user_index] = updated_user;
    res.status(200).json(updated_user);
});

// [DELETE] /users/:id - Delete a user
app.delete('/users/:id', (req, res) => {
    const { id } = req.params;
    const initial_length = all_users.length;
    all_users = all_users.filter(u => u.id !== id);

    if (all_users.length === initial_length) {
        return res.status(404).json({ error: 'User not found' });
    }

    res.status(204).send(); // No Content
});


// --- Start Server ---
app.listen(PORT, () => {
    console.log(`Variation 2 server running on http://localhost:${PORT}`);
});

// To run this:
// 1. npm install express uuid
// 2. node <filename>.js