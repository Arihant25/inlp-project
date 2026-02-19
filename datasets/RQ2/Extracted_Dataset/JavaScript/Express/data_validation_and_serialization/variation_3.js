<script>
/*
 * Variation 3: The "Object-Oriented Controller" Approach
 *
 * Style: OOP, using ES6 classes.
 * - Controllers are classes that group related route-handling logic.
 * - A BaseController can be used to provide common functionality (e.g., response formatting).
 * - Validation rules are defined as static properties on the controller classes for co-location.
 * - This pattern is common in larger, more complex applications and frameworks like NestJS.
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

// --- Mock Data Store ---
const dataStore = {
    users: new Map(),
    posts: new Map(),
};

// --- Base Controller for common response logic ---
class BaseController {
    sendResponse(res, data, statusCode = 200) {
        res.status(statusCode).json(data);
    }

    sendError(res, message = 'An error occurred', statusCode = 500) {
        res.status(statusCode).json({ error: { message } });
    }

    handleValidation(req, res, next) {
        const errors = validationResult(req);
        if (!errors.isEmpty()) {
            return res.status(422).json({
                message: 'Validation Failed',
                errors: errors.mapped(),
            });
        }
        next();
    }
}

// --- User Controller ---
class UserController extends BaseController {
    // Co-locating validation rules with the controller
    static get validationRules() {
        return {
            create: [
                body('email').isEmail().withMessage('Invalid email format').normalizeEmail(),
                body('password').isLength({ min: 8 }).withMessage('Password too short'),
                body('role').optional().isIn(['ADMIN', 'USER']),
                body('is_active').optional().isBoolean().toBoolean(),
            ],
        };
    }

    constructor() {
        super();
        // Bind methods to ensure 'this' context is correct when used as route handlers
        this.create = this.create.bind(this);
    }

    create(req, res) {
        const { email, password, role, is_active } = req.body;

        if (Array.from(dataStore.users.values()).some(u => u.email === email)) {
            return this.sendError(res, 'Email is already registered', 409);
        }

        const newUser = {
            id: uuidv4(),
            email,
            password_hash: `hashed_${password}`,
            role: role || 'USER',
            is_active: is_active !== false,
            created_at: new Date(),
        };

        dataStore.users.set(newUser.id, newUser);

        const { password_hash, ...userResponse } = newUser;
        this.sendResponse(res, userResponse, 201);
    }
}

// --- Post Controller ---
class PostController extends BaseController {
    static get validationRules() {
        return {
            create: [
                body('user_id').isUUID().withMessage('user_id must be a valid UUID')
                    .custom(async (userId) => {
                        if (!dataStore.users.has(userId)) {
                            return Promise.reject('User with this ID does not exist.');
                        }
                    }),
                body('title').notEmpty().trim(),
                body('content').notEmpty(),
                body('status').optional().isIn(['DRAFT', 'PUBLISHED']),
            ],
        };
    }

    constructor() {
        super();
        this.create = this.create.bind(this);
    }

    create(req, res) {
        const { user_id, title, content, status } = req.body;

        const newPost = {
            id: uuidv4(),
            user_id,
            title,
            content,
            status: status || 'DRAFT',
        };

        dataStore.posts.set(newPost.id, newPost);

        // Demonstrate XML serialization
        res.format({
            'application/json': () => {
                this.sendResponse(res, newPost, 201);
            },
            'application/xml': () => {
                res.status(201).type('application/xml').send(js2xmlparser.parse('post', newPost));
            },
            default: () => {
                this.sendError(res, 'Not Acceptable', 406);
            }
        });
    }
}

// --- App and Route Setup ---
const app = express();
app.use(express.json());

const userController = new UserController();
const postController = new PostController();

const userRouter = express.Router();
userRouter.post(
    '/',
    UserController.validationRules.create,
    userController.handleValidation,
    userController.create
);

const postRouter = express.Router();
postRouter.post(
    '/',
    PostController.validationRules.create,
    postController.handleValidation,
    postController.create
);

app.use('/users', userRouter);
app.use('/posts', postRouter);

const PORT = 3003;
app.listen(PORT, () => {
    console.log(`Variation 3 server running on http://localhost:${PORT}`);
    console.log('Try: curl -X POST -H "Content-Type: application/json" -d \'{"email":"oop@example.com", "password":"longpassword"}\' http://localhost:3003/users');
});
</script>