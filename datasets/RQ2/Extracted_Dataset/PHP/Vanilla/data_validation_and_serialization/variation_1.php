<?php

// Variation 1: Procedural / Functional Approach
// Developer Style: Prefers simple functions, associative arrays, and a direct, script-like flow.
// Naming Convention: snake_case

// --- MOCK DATA & DOMAIN CONSTANTS ---

abstract class UserRole {
    const ADMIN = 'ADMIN';
    const USER = 'USER';
    public static function get_values() { return [self::ADMIN, self::USER]; }
}

abstract class PostStatus {
    const DRAFT = 'DRAFT';
    const PUBLISHED = 'PUBLISHED';
    public static function get_values() { return [self::DRAFT, self::PUBLISHED]; }
}

function generate_uuid_v4() {
    return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x',
        mt_rand(0, 0xffff), mt_rand(0, 0xffff),
        mt_rand(0, 0xffff),
        mt_rand(0, 0x0fff) | 0x4000,
        mt_rand(0, 0x3fff) | 0x8000,
        mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)
    );
}

$user_data_valid = [
    'email' => 'test.user@example.com',
    'phone' => '+1 (555) 123-4567',
    'password_hash' => 'hashed_password_string',
    'role' => 'USER',
    'is_active' => 'true',
];

$user_data_invalid = [
    'email' => 'invalid-email',
    'phone' => '123',
    'role' => 'GUEST',
    'is_active' => 'yes',
];


// --- VALIDATION FUNCTIONS ---

function validate_data(array $data, array $rules): array {
    $errors = [];
    foreach ($rules as $field => $rule_string) {
        $rule_list = explode('|', $rule_string);
        $value = isset($data[$field]) ? $data[$field] : null;

        foreach ($rule_list as $rule) {
            $params = [];
            if (strpos($rule, ':') !== false) {
                list($rule, $param_str) = explode(':', $rule, 2);
                $params = explode(',', $param_str);
            }

            $is_valid = true;
            $error_message = '';

            switch ($rule) {
                case 'required':
                    if ($value === null || $value === '') {
                        $is_valid = false;
                        $error_message = "The {$field} field is required.";
                    }
                    break;
                case 'email':
                    if (!filter_var($value, FILTER_VALIDATE_EMAIL)) {
                        $is_valid = false;
                        $error_message = "The {$field} must be a valid email address.";
                    }
                    break;
                case 'phone':
                    // Custom validator: North American-like phone number
                    if (!preg_match('/^\+?1?[\s.-]?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}$/', $value)) {
                        $is_valid = false;
                        $error_message = "The {$field} must be a valid phone number.";
                    }
                    break;
                case 'in':
                    if (!in_array($value, $params)) {
                        $is_valid = false;
                        $error_message = "The selected {$field} is invalid. Must be one of: " . implode(', ', $params);
                    }
                    break;
                case 'boolean':
                    if (!in_array(strtolower((string)$value), ['true', 'false', '1', '0'])) {
                        $is_valid = false;
                        $error_message = "The {$field} field must be true or false.";
                    }
                    break;
            }

            if (!$is_valid) {
                if (!isset($errors[$field])) {
                    $errors[$field] = [];
                }
                $errors[$field][] = $error_message;
            }
        }
    }
    return $errors;
}

// --- TYPE CONVERSION / COERCION ---

function coerce_user_types(array $data): array {
    $coerced = $data;
    if (isset($coerced['is_active'])) {
        $coerced['is_active'] = filter_var($coerced['is_active'], FILTER_VALIDATE_BOOLEAN);
    }
    return $coerced;
}

// --- SERIALIZATION FUNCTIONS ---

function serialize_to_json(array $data): string {
    return json_encode($data, JSON_PRETTY_PRINT);
}

function deserialize_from_json(string $json_string): ?array {
    return json_decode($json_string, true);
}

function serialize_to_xml(array $data, string $root_node_name = 'data'): string {
    $xml = new SimpleXMLElement("<{$root_node_name}/>");
    array_to_xml($data, $xml);
    return $xml->asXML();
}

function array_to_xml(array $data, SimpleXMLElement &$xml) {
    foreach ($data as $key => $value) {
        if (is_array($value)) {
            if (!is_numeric($key)) {
                $subnode = $xml->addChild("$key");
                array_to_xml($value, $subnode);
            } else {
                $subnode = $xml->addChild("item$key");
                array_to_xml($value, $subnode);
            }
        } else {
            $xml->addChild("$key", htmlspecialchars("$value"));
        }
    }
}

function deserialize_from_xml(string $xml_string): ?array {
    libxml_use_internal_errors(true);
    $xml = simplexml_load_string($xml_string);
    if ($xml === false) {
        return null;
    }
    $json = json_encode($xml);
    return json_decode($json, true);
}

// --- EXECUTION ---

echo "--- VARIATION 1: PROCEDURAL/FUNCTIONAL --- \n\n";

// 1. Define validation rules for a User
$user_rules = [
    'email' => 'required|email',
    'phone' => 'required|phone',
    'password_hash' => 'required',
    'role' => 'required|in:' . implode(',', UserRole::get_values()),
    'is_active' => 'required|boolean',
];

// 2. Validate invalid data
echo "--- Validating INVALID data ---\n";
$errors = validate_data($user_data_invalid, $user_rules);
if (!empty($errors)) {
    echo "Validation failed:\n";
    print_r($errors);
} else {
    echo "Validation passed (this should not happen).\n";
}
echo "\n";

// 3. Validate valid data
echo "--- Validating VALID data ---\n";
$errors = validate_data($user_data_valid, $user_rules);
if (!empty($errors)) {
    echo "Validation failed (this should not happen).\n";
    print_r($errors);
} else {
    echo "Validation passed!\n";
    
    // 4. Coerce types
    $user_record = coerce_user_types($user_data_valid);
    
    // 5. Add domain model fields
    $user_record['id'] = generate_uuid_v4();
    $user_record['created_at'] = date('Y-m-d H:i:s');
    
    echo "Created User Record:\n";
    print_r($user_record);
    echo "\n";

    // 6. JSON Serialization / Deserialization
    echo "--- JSON Serialization ---\n";
    $json_output = serialize_to_json($user_record);
    echo $json_output . "\n\n";
    
    echo "--- JSON Deserialization ---\n";
    $user_from_json = deserialize_from_json($json_output);
    print_r($user_from_json);
    echo "\n";

    // 7. XML Serialization / Deserialization
    echo "--- XML Serialization ---\n";
    $xml_output = serialize_to_xml($user_record, 'user');
    echo $xml_output . "\n";

    echo "--- XML Deserialization ---\n";
    $user_from_xml = deserialize_from_xml($xml_output);
    print_r($user_from_xml);
}

?>