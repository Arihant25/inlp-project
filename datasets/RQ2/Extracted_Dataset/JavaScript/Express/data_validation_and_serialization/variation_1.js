<script>
/*
 * Variation 1: The "Classic" Middleware Chain Approach
 *
 * Style: Functional, procedural, and common in smaller Express applications.
 * - Validation rules are defined as an array of middleware.
 * - This array is passed directly into the route handler chain.
 * - A dedicated middleware handles formatting validation errors.
 * - Serialization logic is handled within the route handler itself.
 *
 * To run this code:
 * 1. `npm init -y`
 * 2. `npm install express express-validator uuid js2xmlparser xml2js`
 * 3. `node <filename>.js`
 */

const express = require('express');
const { body, validationResult } = require('express-validator');
const { v4: uuidv4 } = require('uuid');
const js2xmlparser = require('js2xmlparser');
const xml2js = require('xml2js');

// --- Mock Database ---
const db = {
    users: [],
    posts: [],
};

// --- Express App Setup ---
const app = express();
app.use(express.json()); // for parsing application/json

// Custom XML Body Parser Middleware
const xmlBodyParser = (req, res, next) => {
    if (req.is('application/xml') || req.is('text/xml')) {
        let data = '';
        req.on('data', (chunk) => { data += chunk; });
        req.on('end', () => {
            xml2js.parseString(data, { explicitArray: false }, (err, result) => {
                if (err) {
                    return res.status(400).send('Invalid XML');
                }
                // The parsed XML is often nested under a root element
                req.body = result[Object.keys(result)[0]];
                next();
            });
        });
    } else {
        next();
    }
};
app.use(xmlBodyParser);


// --- Custom Validator ---
const isPhoneNumber = (value) => {
    // Simple North American phone number regex
    const phoneRegex = /^\(?([0-9]{3})\)?[-. ]?([0-9]{3})[-. ]?([0-9]{4})$/;
    if (!phoneRegex.test(value)) {
        throw new Error('Phone number must be a valid 10-digit number.');
    }
    return true;
};

// --- Validation Rules ---
const createUserValidationRules = [
    body('email').isEmail().withMessage('Must be a valid email address').normalizeEmail(),
    body('password').isLength({ min: 8 }).withMessage('Password must be at least 8 characters long'),
    body('role').optional().isIn(['ADMIN', 'USER']).withMessage('Invalid role specified'),
    body('is_active').optional().isBoolean().withMessage('is_active must be a boolean').toBoolean(),
    // Field added to demonstrate custom validator
    body('phone').notEmpty().withMessage('Phone number is required').custom(isPhoneNumber),
];

const createPostValidationRules = [
    body('user_id').isUUID().withMessage('user_id must be a valid UUID'),
    body('title').notEmpty().trim().escape().withMessage('Title is required'),
    body('content').notEmpty().withMessage('Content is required'),
    body('status').optional().isIn(['DRAFT', 'PUBLISHED']).withMessage('Invalid status'),
];

// --- Error Handling Middleware ---
const handleValidationErrors = (req, res, next) => {
    const errors = validationResult(req);
    if (!errors.isEmpty()) {
        const formattedErrors = {
            message: 'Validation failed',
            errors: errors.array().map(err => ({
                field: err.param,
                message: err.msg,
                value: err.value,
            })),
        };
        return res.status(422).json(formattedErrors);
    }
    next();
};

// --- Routes ---

// 1. Create a User (Accepts JSON, Responds with JSON or XML)
app.post('/users', createUserValidationRules, handleValidationErrors, (req, res) => {
    const { email, password, role, is_active } = req.body;

    const newUser = {
        id: uuidv4(),
        email,
        password_hash: `hashed_${password}`, // In a real app, use bcrypt
        role: role || 'USER',
        is_active: is_active === undefined ? true : is_active,
        created_at: new Date().toISOString(),
    };

    db.users.push(newUser);

    // Serialization based on Accept header
    res.format({
        'application/json': () => {
            const { password_hash, ...userResponse } = newUser;
            res.status(201).json(userResponse);
        },
        'application/xml': () => {
            const { password_hash, ...userResponse } = newUser;
            res.status(201).type('application/xml').send(js2xmlparser.parse('user', userResponse));
        },
        default: () => {
            res.status(406).send('Not Acceptable');
        }
    });
});

// 2. Create a Post (Accepts XML, Responds with JSON)
app.post('/posts', createPostValidationRules, handleValidationErrors, (req, res) => {
    const { user_id, title, content, status } = req.body;

    // Check if user exists
    if (!db.users.find(u => u.id === user_id)) {
        return res.status(400).json({ message: 'Validation failed', errors: [{ field: 'user_id', message: 'User does not exist' }] });
    }

    const newPost = {
        id: uuidv4(),
        user_id,
        title,
        content,
        status: status || 'DRAFT',
    };

    db.posts.push(newPost);
    res.status(201).json(newPost);
});


// --- Server Start ---
const PORT = 3001;
app.listen(PORT, () => {
    console.log(`Variation 1 server running on http://localhost:${PORT}`);
    console.log('Try: curl -X POST -H "Content-Type: application/json" -H "Accept: application/xml" -d \'{"email":"test@example.com", "password":"password123", "phone":"123-456-7890"}\' http://localhost:3001/users');
    console.log('Try: curl -X POST -H "Content-Type: application/xml" -d \'<post><user_id>some-uuid</user_id><title>My XML Post</title><content>Content here</content></post>\' http://localhost:3001/posts');
});
</script>