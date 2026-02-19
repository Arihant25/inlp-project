<script>
// Variation 1: Functional & Procedural Approach
// Developer "Dave" prefers clear, separate functions for each task.
// This style is straightforward, easy to test, and focuses on data transformation.

const DataValidatorAndSerializer = (() => {
    'use strict';

    // --- Constants and Enums ---
    const USER_ROLES = { ADMIN: 'ADMIN', USER: 'USER' };
    const POST_STATUSES = { DRAFT: 'DRAFT', PUBLISHED: 'PUBLISHED' };

    // --- Regular Expressions ---
    const REGEX = {
        EMAIL: /^[^\s@]+@[^\s@]+\.[^\s@]+$/,
        UUID: /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
        PHONE: /^\+?[1-9]\d{1,14}$/ // E.164 format
    };

    // --- Utility Functions ---
    const generateUUID = () => {
        // Uses the standard Web Crypto API
        return crypto.randomUUID();
    };

    const formatErrors = (errors) => {
        if (Object.keys(errors).length === 0) return "No errors.";
        return Object.entries(errors)
            .map(([field, message]) => `Error in field '${field}': ${message}`)
            .join('\n');
    };

    // --- Validation Functions ---
    const validateRequired = (value) => value !== null && value !== undefined && value !== '';
    const validateEmail = (email) => REGEX.EMAIL.test(String(email).toLowerCase());
    const validateUUID = (id) => REGEX.UUID.test(String(id));
    const validatePhone = (phone) => REGEX.PHONE.test(String(phone));
    const validateEnum = (value, enumObject) => Object.values(enumObject).includes(value);

    function validateUser(userData) {
        const errors = {};

        if (!validateRequired(userData.email)) errors.email = "Email is required.";
        else if (!validateEmail(userData.email)) errors.email = "Invalid email format.";

        if (!validateRequired(userData.password_hash)) errors.password_hash = "Password hash is required.";
        else if (userData.password_hash.length < 8) errors.password_hash = "Password hash is too short.";

        if (!validateRequired(userData.role)) errors.role = "Role is required.";
        else if (!validateEnum(userData.role, USER_ROLES)) errors.role = "Invalid role specified.";

        if (userData.is_active !== undefined && typeof userData.is_active !== 'boolean') {
            errors.is_active = "is_active must be a boolean.";
        }

        return { isValid: Object.keys(errors).length === 0, errors };
    }

    function validatePost(postData) {
        const errors = {};

        if (!validateRequired(postData.user_id)) errors.user_id = "User ID is required.";
        else if (!validateUUID(postData.user_id)) errors.user_id = "Invalid User ID format.";

        if (!validateRequired(postData.title)) errors.title = "Title is required.";
        else if (postData.title.length > 255) errors.title = "Title is too long.";

        if (!validateRequired(postData.content)) errors.content = "Content is required.";

        if (!validateRequired(postData.status)) errors.status = "Status is required.";
        else if (!validateEnum(postData.status, POST_STATUSES)) errors.status = "Invalid status specified.";

        return { isValid: Object.keys(errors).length === 0, errors };
    }

    // --- Type Coercion ---
    function coerceUserData(data) {
        const coerced = { ...data };
        if (typeof coerced.is_active === 'string') {
            coerced.is_active = coerced.is_active.toLowerCase() === 'true';
        }
        if (coerced.created_at && !(coerced.created_at instanceof Date)) {
            const timestamp = Date.parse(coerced.created_at);
            coerced.created_at = isNaN(timestamp) ? null : new Date(timestamp);
        }
        return coerced;
    }

    // --- Serialization / Deserialization ---

    // JSON
    const serializeUserToJson = (user) => JSON.stringify(user, null, 2);
    const deserializeUserFromJson = (jsonString) => {
        try {
            return JSON.parse(jsonString);
        } catch (e) {
            return null;
        }
    };

    // XML
    const serializeUserToXml = (user) => {
        return `<user>
    <id>${user.id}</id>
    <email>${user.email}</email>
    <password_hash>${user.password_hash}</password_hash>
    <role>${user.role}</role>
    <is_active>${user.is_active}</is_active>
    <created_at>${user.created_at.toISOString()}</created_at>
</user>`;
    };

    const parseUserFromXml = (xmlString) => {
        try {
            const parser = new DOMParser();
            const xmlDoc = parser.parseFromString(xmlString, "application/xml");
            const userNode = xmlDoc.getElementsByTagName("user")[0];
            if (!userNode) return null;

            const getText = (tag) => userNode.getElementsByTagName(tag)[0]?.textContent || null;

            const rawUser = {
                id: getText("id"),
                email: getText("email"),
                password_hash: getText("password_hash"),
                role: getText("role"),
                is_active: getText("is_active"),
                created_at: getText("created_at"),
            };
            return coerceUserData(rawUser);
        } catch (e) {
            return null;
        }
    };

    // --- Public API ---
    return {
        validateUser,
        validatePost,
        validatePhone, // Custom validator example
        serializeUserToJson,
        deserializeUserFromJson,
        serializeUserToXml,
        parseUserFromXml,
        formatErrors,
        generateUUID,
        USER_ROLES,
        POST_STATUSES
    };
})();

// --- DEMONSTRATION ---
console.log("--- Variation 1: Functional & Procedural ---");

// 1. Create and Validate a User
const newUser = {
    email: 'dave@example.com',
    password_hash: 'a_very_secure_hash_string_123',
    role: DataValidatorAndSerializer.USER_ROLES.ADMIN,
    is_active: true
};

const validationResult = DataValidatorAndSerializer.validateUser(newUser);
console.log("User Validation Passed:", validationResult.isValid);

const invalidUser = { email: 'bad-email', role: 'GUEST' };
const invalidResult = DataValidatorAndSerializer.validateUser(invalidUser);
console.log("Invalid User Validation Passed:", invalidResult.isValid);
console.log("Formatted Errors:\n" + DataValidatorAndSerializer.formatErrors(invalidResult.errors));

// 2. Custom Validator (Phone)
console.log("Is valid phone? (+15551234567):", DataValidatorAndSerializer.validatePhone('+15551234567'));
console.log("Is valid phone? (555-1234):", DataValidatorAndSerializer.validatePhone('555-1234'));


// 3. Serialization
const userInstance = {
    id: DataValidatorAndSerializer.generateUUID(),
    created_at: new Date(),
    ...newUser
};

// JSON
const userJson = DataValidatorAndSerializer.serializeUserToJson(userInstance);
console.log("\nUser Serialized to JSON:\n", userJson);
const userFromJson = DataValidatorAndSerializer.deserializeUserFromJson(userJson);
console.log("User Deserialized from JSON:", userFromJson);

// XML
const userXml = DataValidatorAndSerializer.serializeUserToXml(userInstance);
console.log("\nUser Serialized to XML:\n", userXml);
const userFromXml = DataValidatorAndSerializer.parseUserFromXml(userXml);
console.log("User Deserialized and Coerced from XML:", userFromXml);
</script>