<script>
// Variation 3: Schema-Driven & Declarative Approach
// Developer "Sam" prefers defining validation rules as data (a schema).
// This makes rules easy to read, modify, and reuse without changing the core validation logic.

const SchemaValidator = (() => {
    'use strict';

    // --- Core Validation Engine ---
    const validators = {
        required: (val) => val !== null && val !== undefined && val !== '',
        isEmail: (val) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(val),
        isUUID: (val) => /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(val),
        isPhone: (val) => /^\+(?:[0-9] ?){6,14}[0-9]$/.test(val), // Basic international format
        isBoolean: (val) => typeof val === 'boolean',
        minLength: (val, len) => String(val).length >= len,
        oneOf: (val, options) => options.includes(val),
    };

    function validate(data, schema) {
        const errors = {};
        for (const field in schema) {
            const rules = schema[field];
            const value = data[field];

            for (const rule of rules) {
                const validatorFn = typeof rule.validator === 'string' ? validators[rule.validator] : rule.validator;
                if (!validatorFn) {
                    console.warn(`Unknown validator: ${rule.validator}`);
                    continue;
                }

                const args = rule.args ? [value, ...rule.args] : [value];
                if (!validatorFn(...args)) {
                    errors[field] = rule.message;
                    break; // Stop on first error for a field
                }
            }
        }
        return {
            isValid: Object.keys(errors).length === 0,
            errors,
            getFormattedErrors: () => Object.entries(errors).map(([f, m]) => `${f}: ${m}`).join('\n')
        };
    }

    // --- Schemas ---
    const USER_ROLES = ['ADMIN', 'USER'];
    const POST_STATUSES = ['DRAFT', 'PUBLISHED'];

    const userSchema = {
        id: [{ validator: 'required', message: 'ID is required.' }, { validator: 'isUUID', message: 'Invalid ID format.' }],
        email: [{ validator: 'required', message: 'Email is required.' }, { validator: 'isEmail', message: 'Must be a valid email address.' }],
        password_hash: [{ validator: 'required', message: 'Password hash is required.' }, { validator: 'minLength', args: [8], message: 'Password hash must be at least 8 characters.' }],
        role: [{ validator: 'required', message: 'Role is required.' }, { validator: 'oneOf', args: [USER_ROLES], message: `Role must be one of: ${USER_ROLES.join(', ')}` }],
        is_active: [{ validator: 'isBoolean', message: 'is_active must be a boolean.' }],
        created_at: [{ validator: (val) => val instanceof Date && !isNaN(val), message: 'created_at must be a valid Date object.' }]
    };

    const postSchema = {
        id: [{ validator: 'required', message: 'ID is required.' }, { validator: 'isUUID', message: 'Invalid ID format.' }],
        user_id: [{ validator: 'required', message: 'User ID is required.' }, { validator: 'isUUID', message: 'Invalid User ID format.' }],
        title: [{ validator: 'required', message: 'Title cannot be empty.' }],
        content: [{ validator: 'required', message: 'Content is required.' }],
        status: [{ validator: 'required', message: 'Status is required.' }, { validator: 'oneOf', args: [POST_STATUSES], message: `Status must be one of: ${POST_STATUSES.join(', ')}` }]
    };

    // --- Serialization & Coercion ---
    const toJSON = (data) => JSON.stringify(data, null, 2);
    const fromJSON = (json) => JSON.parse(json);

    const toXML = (data, rootTag) => {
        let xml = `<${rootTag}>\n`;
        for (const key in data) {
            if (Object.prototype.hasOwnProperty.call(data, key)) {
                const value = data[key] instanceof Date ? data[key].toISOString() : data[key];
                xml += `  <${key}>${value}</${key}>\n`;
            }
        }
        xml += `</${rootTag}>`;
        return xml;
    };

    const fromXML = (xmlString) => {
        const parser = new DOMParser();
        const doc = parser.parseFromString(xmlString, 'application/xml');
        const root = doc.documentElement;
        const obj = {};
        
        // Basic coercion based on common field names
        for (const child of root.children) {
            let value = child.textContent;
            if (child.tagName.endsWith('_at')) {
                value = new Date(value);
            } else if (child.tagName.startsWith('is_')) {
                value = value.toLowerCase() === 'true';
            }
            obj[child.tagName] = value;
        }
        return obj;
    };

    return {
        validate,
        schemas: { user: userSchema, post: postSchema },
        customValidators: validators, // Expose for custom use
        toJSON,
        fromJSON,
        toXML,
        fromXML,
        generateUUID: () => crypto.randomUUID()
    };
})();

// --- DEMONSTRATION ---
console.log("--- Variation 3: Schema-Driven Approach ---");

// 1. Create a valid user object
const validUser = {
    id: SchemaValidator.generateUUID(),
    email: 'sam@example.com',
    password_hash: 'schemas_are_cool_123',
    role: 'USER',
    is_active: true,
    created_at: new Date()
};

const result1 = SchemaValidator.validate(validUser, SchemaValidator.schemas.user);
console.log("Valid user check:", result1.isValid);

// 2. Create an invalid user object
const invalidUser = {
    id: '12345',
    email: 'sam@',
    password_hash: 'short',
    role: 'JANITOR',
    is_active: 'yes',
    created_at: 'yesterday'
};

const result2 = SchemaValidator.validate(invalidUser, SchemaValidator.schemas.user);
console.log("\nInvalid user check:", result2.isValid);
console.log("Errors:\n" + result2.getFormattedErrors());

// 3. Use a custom validator directly
console.log("\nCustom phone validator check (+1234567890):", SchemaValidator.customValidators.isPhone('+1234567890'));

// 4. Serialization
const userJson = SchemaValidator.toJSON(validUser);
console.log("\nUser to JSON:\n", userJson);
const userXml = SchemaValidator.toXML(validUser, 'user');
console.log("\nUser to XML:\n", userXml);

// 5. Deserialization and Coercion
const deserializedUser = SchemaValidator.fromXML(userXml);
console.log("\nUser from XML (with coercion):\n", deserializedUser);
console.log("Is created_at a Date object?", deserializedUser.created_at instanceof Date);
console.log("Is is_active a boolean?", typeof deserializedUser.is_active === 'boolean');
</script>