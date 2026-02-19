<script>
/*
 * Variation 4: The "Factory Function" / Decoupled Middleware Approach
 *
 * Style: Functional, compositional, and highly decoupled.
 * - Controller functions are "pure": they only contain business logic, take input,
 *   and return data or throw errors. They do not interact with `res`.
 * - A generic response middleware handles all serialization and HTTP responses.
 * - Validation is defined in a separate "schema" object and applied by a factory.
 * - This pattern maximizes testability and follows principles of functional programming.
 *
 * To run this code:
 * 1. `npm init -y`
 * 2. `npm install express express-validator uuid js2xmlparser`
 * 3. `node <filename>.js`
 */

const express = require('express');
const { checkSchema, validationResult } = require('express-validator');
const { v4: uuidv4 } = require('uuid');
const js2xmlparser = require('js2xmlparser');

// --- Mock Service Layer ---
const db = { users: [], posts: [] };
const UserService = {
    async findByEmail(email) { return db.users.find(u => u.email === email); },
    async create(data) {
        const user = { ...data, id: uuidv4(), created_at: new Date().toISOString() };
        db.users.push(user);
        return user;
    }
};
const PostService = {
    async create(data) {
        const post = { ...data, id: uuidv4() };
        db.posts.push(post);
        return post;
    }
};

// --- Validation Module ---
const ValidationSchemas = {
    createUser: {
        email: {
            isEmail: true,
            normalizeEmail: true,
            errorMessage: 'Must be a valid email',
            custom: {
                options: async (email) => {
                    if (await UserService.findByEmail(email)) {
                        return Promise.reject('Email already in use');
                    }
                }
            }
        },
        password: {
            isLength: { options: { min: 8 } },
            errorMessage: 'Password must be at least 8 characters'
        },
        is_active: {
            optional: true,
            isBoolean: true,
            toBoolean: true
        },
        role: {
            optional: true,
            isIn: { options: [['ADMIN', 'USER']] }
        }
    },
    createPost: {
        user_id: { isUUID: true, errorMessage: 'Invalid user_id' },
        title: { notEmpty: true, trim: true, errorMessage: 'Title is required' },
        content: { notEmpty: true, errorMessage: 'Content is required' },
        status: { optional: true, isIn: { options: [['DRAFT', 'PUBLISHED']] } }
    }
};

// --- Middleware Factory for Validation ---
const createValidationMiddleware = (schema) => [
    checkSchema(schema),
    (req, res, next) => {
        const errors = validationResult(req);
        if (!errors.isEmpty()) {
            return res.status(422).json({ message: 'Validation Error', details: errors.array() });
        }
        next();
    }
];

// --- Controller Module (Pure Business Logic) ---
// These handlers do not call res.send/json. They put data on res.locals.
const UserController = {
    async createUser(req, res, next) {
        try {
            const { email, password, role = 'USER', is_active = true } = req.body;
            const newUser = await UserService.create({
                email,
                password_hash: `hashed_${password}`,
                role,
                is_active
            });
            // Attach data for the response middleware
            res.locals.data = newUser;
            res.locals.statusCode = 201;
            res.locals.resourceName = 'user';
            next();
        } catch (err) {
            next(err);
        }
    }
};

const PostController = {
    async createPost(req, res, next) {
        try {
            const { user_id, title, content, status = 'DRAFT' } = req.body;
            const newPost = await PostService.create({ user_id, title, content, status });
            res.locals.data = newPost;
            res.locals.statusCode = 201;
            res.locals.resourceName = 'post';
            next();
        } catch (err) {
            next(err);
        }
    }
};

// --- Generic Response & Serialization Middleware ---
const responseMiddleware = (req, res, next) => {
    if (res.locals.data) {
        const { data, statusCode, resourceName } = res.locals;
        
        // Sanitize sensitive data before sending
        if (data.password_hash) {
            delete data.password_hash;
        }

        res.format({
            'application/json': () => {
                res.status(statusCode).json(data);
            },
            'application/xml': () => {
                res.status(statusCode).type('application/xml').send(js2xmlparser.parse(resourceName || 'data', data));
            },
            default: () => {
                res.status(406).send('Not Acceptable');
            }
        });
    } else {
        // If no data was attached, it might be an unhandled route
        next();
    }
};

// --- Generic Error Handler ---
const errorMiddleware = (err, req, res, next) => {
    console.error(err);
    res.status(err.status || 500).json({
        error: err.message || 'Internal Server Error'
    });
};

// --- Router Factory ---
const createResourceRouter = (config) => {
    const router = express.Router();
    router.post('/', ...config.create);
    // router.get('/', ...config.getAll); // etc.
    return router;
};

// --- Main Application Setup ---
const app = express();
app.use(express.json());

const userRouter = createResourceRouter({
    create: [
        createValidationMiddleware(ValidationSchemas.createUser),
        UserController.createUser
    ]
});

const postRouter = createResourceRouter({
    create: [
        createValidationMiddleware(ValidationSchemas.createPost),
        PostController.createPost
    ]
});

app.use('/users', userRouter);
app.use('/posts', postRouter);

// Attach final handlers
app.use(responseMiddleware);
app.use(errorMiddleware);

const PORT = 3004;
app.listen(PORT, () => {
    console.log(`Variation 4 server running on http://localhost:${PORT}`);
    console.log('Try: curl -X POST -H "Content-Type: application/json" -H "Accept: application/xml" -d \'{"email":"factory@example.com", "password":"a.strong.password"}\' http://localhost:3004/users');
});
</script>