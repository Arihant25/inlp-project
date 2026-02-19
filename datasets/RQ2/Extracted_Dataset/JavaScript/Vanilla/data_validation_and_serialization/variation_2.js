<script>
// Variation 2: Object-Oriented Programming (OOP) Approach
// Developer "Olivia" prefers to encapsulate logic within classes.
// This style promotes code organization, reusability, and a clear separation of concerns.

class ValidationError extends Error {
    constructor(message, errors) {
        super(message);
        this.name = 'ValidationError';
        this.errors = errors; // { field: message, ... }
    }

    getFormattedErrors() {
        return Object.entries(this.errors)
            .map(([field, msg]) => `[${field}] ${msg}`)
            .join('; ');
    }
}

class BaseValidator {
    constructor() {
        this.errors = {};
        this.ROLES = { ADMIN: 'ADMIN', USER: 'USER' };
        this.STATUSES = { DRAFT: 'DRAFT', PUBLISHED: 'PUBLISHED' };
    }

    _addError(field, message) {
        this.errors[field] = message;
    }

    _validateRequired(field, value) {
        if (value === null || value === undefined || value === '') {
            this._addError(field, 'This field is required.');
            return false;
        }
        return true;
    }

    _validateRegex(field, value, regex, message) {
        if (!regex.test(String(value))) {
            this._addError(field, message);
            return false;
        }
        return true;
    }

    _validateEnum(field, value, enumObject) {
        if (!Object.values(enumObject).includes(value)) {
            this._addError(field, `Invalid value. Must be one of: ${Object.values(enumObject).join(', ')}`);
            return false;
        }
        return true;
    }

    // Custom validator example
    validatePhoneNumber(phone) {
        const phoneRegex = /^\(?(\d{3})\)?[-.\s]?(\d{3})[-.\s]?(\d{4})$/;
        return this._validateRegex('phone', phone, phoneRegex, 'Invalid phone number format.');
    }

    getResult() {
        if (Object.keys(this.errors).length > 0) {
            throw new ValidationError('Validation failed', this.errors);
        }
        return true;
    }
}

class UserValidator extends BaseValidator {
    validate(user) {
        this.errors = {}; // Reset errors for new validation

        if (this._validateRequired('email', user.email)) {
            const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
            this._validateRegex('email', user.email, emailRegex, 'Invalid email address format.');
        }

        if (this._validateRequired('password_hash', user.password_hash)) {
            if (user.password_hash.length < 10) {
                this._addError('password_hash', 'Password hash must be at least 10 characters long.');
            }
        }

        if (this._validateRequired('role', user.role)) {
            this._validateEnum('role', user.role, this.ROLES);
        }

        if (user.is_active !== undefined && typeof user.is_active !== 'boolean') {
            this._addError('is_active', 'is_active must be a boolean value.');
        }

        return this.getResult();
    }
}

class PostValidator extends BaseValidator {
    validate(post) {
        this.errors = {};

        if (this._validateRequired('user_id', post.user_id)) {
            const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
            this._validateRegex('user_id', post.user_id, uuidRegex, 'Invalid user_id UUID format.');
        }

        this._validateRequired('title', post.title);
        this._validateRequired('content', post.content);

        if (this._validateRequired('status', post.status)) {
            this._validateEnum('status', post.status, this.STATUSES);
        }

        return this.getResult();
    }
}

class SerializerService {
    static #generateUUID() {
        return crypto.randomUUID();
    }

    static #coerceType(value, type) {
        if (value === null || value === undefined) return value;
        switch (type) {
            case 'boolean':
                return String(value).toLowerCase() === 'true';
            case 'date':
                const d = new Date(value);
                return isNaN(d.getTime()) ? null : d;
            default:
                return value;
        }
    }

    // JSON
    static toJSON(entity) {
        return JSON.stringify(entity, null, 2);
    }

    static fromJSON(jsonString) {
        return JSON.parse(jsonString);
    }

    // XML
    static toXML(entity, rootName) {
        const fields = Object.entries(entity)
            .map(([key, value]) => {
                const serializedValue = value instanceof Date ? value.toISOString() : value;
                return `    <${key}>${serializedValue}</${key}>`;
            })
            .join('\n');
        return `<${rootName}>\n${fields}\n</${rootName}>`;
    }

    static fromXML(xmlString, schema) {
        const parser = new DOMParser();
        const xmlDoc = parser.parseFromString(xmlString, "application/xml");
        const rootNode = xmlDoc.documentElement;
        const entity = {};

        for (const [field, type] of Object.entries(schema)) {
            const node = rootNode.querySelector(field);
            const value = node ? node.textContent : null;
            entity[field] = this.#coerceType(value, type);
        }
        return entity;
    }
    
    static generateUUID() {
        return this.#generateUUID();
    }
}

// --- DEMONSTRATION ---
console.log("--- Variation 2: OOP Approach ---");

// 1. Validation
const userValidator = new UserValidator();
const validUser = {
    email: 'olivia@example.com',
    password_hash: 'another_strong_and_long_hash',
    role: 'ADMIN',
    is_active: true
};

try {
    userValidator.validate(validUser);
    console.log("Valid user data passed validation.");
} catch (e) {
    if (e instanceof ValidationError) {
        console.error("Validation failed:", e.getFormattedErrors());
    }
}

const invalidUser = { email: 'invalid', role: 'SUPERUSER' };
try {
    userValidator.validate(invalidUser);
} catch (e) {
    if (e instanceof ValidationError) {
        console.error("Invalid user data failed validation as expected:", e.getFormattedErrors());
    }
}

// 2. Serialization
const userInstance = {
    id: SerializerService.generateUUID(),
    created_at: new Date(),
    ...validUser
};

// JSON
const userJson = SerializerService.toJSON(userInstance);
console.log("\nUser as JSON:\n", userJson);
const userFromJson = SerializerService.fromJSON(userJson);
console.log("User from JSON:", userFromJson);

// XML
const userXml = SerializerService.toXML(userInstance, 'user');
console.log("\nUser as XML:\n", userXml);

const userSchemaForParsing = {
    id: 'string',
    email: 'string',
    password_hash: 'string',
    role: 'string',
    is_active: 'boolean',
    created_at: 'date'
};
const userFromXml = SerializerService.fromXML(userXml, userSchemaForParsing);
console.log("User from XML (with type coercion):", userFromXml);
</script>