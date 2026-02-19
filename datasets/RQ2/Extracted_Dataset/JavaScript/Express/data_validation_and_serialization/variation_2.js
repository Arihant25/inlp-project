<script>
/*
 * Variation 2: The "Service Layer" / Modular Approach
 *
 * Style: Structured with clear separation of concerns.
 * - Logic is split into "layers": routes, controllers, validators, services, and utils.
 * - This is simulated in a single file for self-containment.
 * - Promotes reusability and testability.
 * - Naming is more descriptive (e.g., `UserValidation.createRules`).
 *
 * To run this code:
 * 1. `npm init -y`
 * 2. `npm install express express-validator uuid js2xmlparser`
 * 3. `node <filename>.js`
 */

const express = require('express');
const { body, validationResult } = require('express-validator');
const { v4: uuidv4 } = require('uuid');
const js2xmlparser = require('js2xmlparser');

// --- Mock "Database" Layer ---
const MockDB = {
    users: [],
    posts: [],
};

// --- (Simulated) services/user.service.js ---
const UserService = {
    create: async ({ email, password, role, is_active }) => {
        if (MockDB.users.some(u => u.email === email)) {
            const err = new Error('Email already in use');
            err.status = 409;
            throw err;
        }
        const newUser = {
            id: uuidv4(),
            email,
            password_hash: `hashed_${password}`,
            role: role || 'USER',
            is_active: is_active === undefined ? true : is_active,
            created_at: new Date().toISOString(),
        };
        MockDB.users.push(newUser);
        return newUser;
    },
    findById: async (id) => MockDB.users.find(u => u.id === id),
};

// --- (Simulated) services/post.service.js ---
const PostService = {
    create: async ({ user_id, title, content, status }) => {
        const userExists = await UserService.findById(user_id);
        if (!userExists) {
            const err = new Error('User not found');
            err.status = 404;
            throw err;
        }
        const newPost = {
            id: uuidv4(),
            user_id,
            title,
            content,
            status: status || 'DRAFT',
        };
        MockDB.posts.push(newPost);
        return newPost;
    },
};

// --- (Simulated) validators/user.validator.js ---
const UserValidation = {
    createRules: () => [
        body('email').isEmail().withMessage('A valid email is required').normalizeEmail(),
        body('password').isStrongPassword().withMessage('Password must be stronger'),
        body('role').optional().isIn(['ADMIN', 'USER']),
        body('is_active').optional().toBoolean(),
    ],
};

// --- (Simulated) validators/post.validator.js ---
const PostValidation = {
    createRules: () => [
        body('user_id').isUUID(4).withMessage('A valid user_id is required'),
        body('title').notEmpty().trim().escape().withMessage('Title cannot be empty'),
        body('content').isLength({ min: 10 }).withMessage('Content must be at least 10 characters'),
        body('status').optional().isIn(['DRAFT', 'PUBLISHED']),
    ],
};

// --- (Simulated) middleware/validation.handler.js ---
const validateRequest = (req, res, next) => {
    const errors = validationResult(req);
    if (!errors.isEmpty()) {
        return res.status(422).json({
            error: {
                message: "Input validation failed",
                details: errors.formatWith(err => ({
                    field: err.param,
                    issue: err.msg,
                })).array(),
            }
        });
    }
    next();
};

// --- (Simulated) utils/response.serializer.js ---
const serializeResponse = (req, res, data, statusCode = 200) => {
    // Exclude sensitive fields from user objects before sending
    const sanitize = (obj) => {
        if (obj && typeof obj === 'object') {
            delete obj.password_hash;
        }
        return obj;
    };

    const sanitizedData = Array.isArray(data) ? data.map(sanitize) : sanitize(data);
    const rootElementName = req.path.includes('/users') ? 'user' : 'post';

    res.format({
        'application/json': () => {
            res.status(statusCode).json(sanitizedData);
        },
        'application/xml': () => {
            res.status(statusCode).type('application/xml').send(js2xmlparser.parse(rootElementName, sanitizedData));
        },
        default: () => {
            res.status(406).send('Unsupported format requested');
        }
    });
};

// --- (Simulated) controllers/user.controller.js ---
const UserController = {
    createUser: async (req, res, next) => {
        try {
            const user = await UserService.create(req.body);
            serializeResponse(req, res, user, 201);
        } catch (error) {
            next(error);
        }
    },
};

// --- (Simulated) controllers/post.controller.js ---
const PostController = {
    createPost: async (req, res, next) => {
        try {
            const post = await PostService.create(req.body);
            serializeResponse(req, res, post, 201);
        } catch (error) {
            next(error);
        }
    },
};

// --- (Simulated) app.js ---
const app = express();
app.use(express.json());

// --- (Simulated) routes/user.routes.js ---
const userRouter = express.Router();
userRouter.post('/', UserValidation.createRules(), validateRequest, UserController.createUser);
app.use('/users', userRouter);

// --- (Simulated) routes/post.routes.js ---
const postRouter = express.Router();
postRouter.post('/', PostValidation.createRules(), validateRequest, PostController.createPost);
app.use('/posts', postRouter);

// --- Global Error Handler ---
app.use((err, req, res, next) => {
    console.error(err.stack);
    const status = err.status || 500;
    res.status(status).json({
        error: {
            message: err.message || 'An unexpected error occurred.',
        }
    });
});

const PORT = 3002;
app.listen(PORT, () => {
    console.log(`Variation 2 server running on http://localhost:${PORT}`);
    console.log('Try: curl -X POST -H "Content-Type: application/json" -H "Accept: application/json" -d \'{"email":"dev@example.com", "password":"Password123!"}\' http://localhost:3002/users');
});
</script>