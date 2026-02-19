<script>
// Variation 4: Classic Module Pattern
// Developer "Mike" uses the IIFE (Immediately Invoked Function Expression) to create private,
// encapsulated modules. This is a robust pre-ES6 module pattern that avoids global scope pollution.
// Naming conventions are more verbose (e.g., `user_data`, `validation_errors`).

const UserValidationService = (function() {
    // --- Private Members ---
    const _private_config = {
        MIN_PASSWORD_HASH_LENGTH: 8,
        ROLES: ['ADMIN', 'USER'],
        REGEX_EMAIL: new RegExp('^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$'),
        REGEX_UUID: new RegExp('^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$', 'i')
    };

    function _validate_field(validation_errors, field_name, value, validation_fn, error_message) {
        if (!validation_fn(value)) {
            if (!validation_errors[field_name]) {
                validation_errors[field_name] = [];
            }
            validation_errors[field_name].push(error_message);
        }
    }

    // --- Public API ---
    const public_api = {};

    public_api.validate = function(user_data) {
        const validation_errors = {};

        // Email validation
        _validate_field(validation_errors, 'email', user_data.email, (v) => v && typeof v === 'string', 'Email is required.');
        if (!validation_errors.email) {
            _validate_field(validation_errors, 'email', user_data.email, (v) => _private_config.REGEX_EMAIL.test(v), 'Invalid email format.');
        }

        // Password hash validation
        _validate_field(validation_errors, 'password_hash', user_data.password_hash, (v) => v && typeof v === 'string', 'Password hash is required.');
        if (!validation_errors.password_hash) {
            _validate_field(validation_errors, 'password_hash', user_data.password_hash, (v) => v.length >= _private_config.MIN_PASSWORD_HASH_LENGTH, `Password hash must be at least ${_private_config.MIN_PASSWORD_HASH_LENGTH} characters.`);
        }

        // Role validation
        _validate_field(validation_errors, 'role', user_data.role, (v) => _private_config.ROLES.includes(v), `Role must be one of: ${_private_config.ROLES.join(', ')}.`);

        // is_active validation
        if (user_data.is_active !== undefined) {
            _validate_field(validation_errors, 'is_active', user_data.is_active, (v) => typeof v === 'boolean', 'is_active must be a boolean.');
        }

        return {
            is_valid: Object.keys(validation_errors).length === 0,
            errors: validation_errors
        };
    };

    // Custom validator example
    public_api.is_valid_phone = function(phone_number) {
        // A simple North American Numbering Plan check
        const phone_regex = /^\(?([2-9][0-8][0-9])\)?[-. ]?([2-9][0-9]{2})[-. ]?([0-9]{4})$/;
        return phone_regex.test(phone_number);
    };

    return public_api;
})();

const DataSerializationModule = (function() {
    // --- Private Helper ---
    function _escape_xml(unsafe_string) {
        return unsafe_string.replace(/[<>&'"]/g, function(c) {
            switch (c) {
                case '<': return '&lt;';
                case '>': return '&gt;';
                case '&': return '&amp;';
                case '\'': return '&apos;';
                case '"': return '&quot;';
            }
        });
    }

    // --- Public API ---
    const public_api = {};

    public_api.user_to_json = function(user_object) {
        return JSON.stringify(user_object);
    };

    public_api.json_to_user = function(json_string) {
        const parsed_data = JSON.parse(json_string);
        // Coercion
        if (parsed_data.created_at) {
            parsed_data.created_at = new Date(parsed_data.created_at);
        }
        if (typeof parsed_data.is_active === 'string') {
            parsed_data.is_active = parsed_data.is_active === 'true';
        }
        return parsed_data;
    };

    public_api.post_to_xml = function(post_object) {
        let xml_string = '<post>\n';
        xml_string += `    <id>${_escape_xml(String(post_object.id))}</id>\n`;
        xml_string += `    <user_id>${_escape_xml(String(post_object.user_id))}</user_id>\n`;
        xml_string += `    <title>${_escape_xml(String(post_object.title))}</title>\n`;
        xml_string += `    <content>${_escape_xml(String(post_object.content))}</content>\n`;
        xml_string += `    <status>${_escape_xml(String(post_object.status))}</status>\n`;
        xml_string += '</post>';
        return xml_string;
    };

    public_api.xml_to_post = function(xml_string) {
        const parser = new DOMParser();
        const xml_doc = parser.parseFromString(xml_string, "text/xml");
        const get_text = (tag) => xml_doc.querySelector(tag)?.textContent;

        return {
            id: get_text("id"),
            user_id: get_text("user_id"),
            title: get_text("title"),
            content: get_text("content"),
            status: get_text("status")
        };
    };
    
    public_api.generate_uuid = function() {
        return crypto.randomUUID();
    };

    return public_api;
})();

// --- DEMONSTRATION ---
console.log("--- Variation 4: Classic Module Pattern ---");

// 1. User Validation
const valid_user_data = {
    email: 'mike@module.dev',
    password_hash: 'a_very_long_and_secure_password_hash',
    role: 'ADMIN',
    is_active: false
};
const validation_result = UserValidationService.validate(valid_user_data);
console.log("Validation for valid user is_valid:", validation_result.is_valid);

const invalid_user_data = { email: 'mike', password_hash: '123', role: 'USER' };
const invalid_result = UserValidationService.validate(invalid_user_data);
console.log("Validation for invalid user is_valid:", invalid_result.is_valid);
console.log("Errors:", invalid_result.errors);

// 2. Custom Phone Validator
console.log("Is '555-123-4567' a valid phone?", UserValidationService.is_valid_phone('555-123-4567'));

// 3. Serialization / Deserialization
const post_instance = {
    id: DataSerializationModule.generate_uuid(),
    user_id: DataSerializationModule.generate_uuid(),
    title: 'Module Pattern & Data',
    content: 'This post is about the module pattern and contains special characters like < & >.',
    status: 'PUBLISHED'
};

// XML
const post_xml = DataSerializationModule.post_to_xml(post_instance);
console.log("\nPost serialized to XML:\n", post_xml);
const post_from_xml = DataSerializationModule.xml_to_post(post_xml);
console.log("Post deserialized from XML:", post_from_xml);

// JSON
const user_instance = { ...valid_user_data, id: DataSerializationModule.generate_uuid(), created_at: new Date() };
const user_json = DataSerializationModule.user_to_json(user_instance);
console.log("\nUser serialized to JSON:\n", user_json);
const user_from_json = DataSerializationModule.json_to_user(user_json);
console.log("User deserialized from JSON (with coercion):", user_from_json);
</script>