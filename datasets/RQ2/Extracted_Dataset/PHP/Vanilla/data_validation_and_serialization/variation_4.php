<?php

// Variation 4: Trait-based / Composition Approach
// Developer Style: Modern PHP developer who favors composition over inheritance, using traits for reusable behavior.
// Naming Convention: camelCase

// --- DOMAIN CONSTANTS ---
interface Role { const ADMIN = 'ADMIN'; const USER = 'USER'; }
interface Status { const DRAFT = 'DRAFT'; const PUBLISHED = 'PUBLISHED'; }

// --- REUSABLE TRAITS ---

trait UuidGenerator
{
    public static function newUuid(): string
    {
        // A simple v4 UUID generator
        return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x',
            mt_rand(0, 0xffff), mt_rand(0, 0xffff),
            mt_rand(0, 0xffff),
            mt_rand(0, 0x0fff) | 0x4000,
            mt_rand(0, 0x3fff) | 0x8000,
            mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)
        );
    }
}

trait Validatable
{
    protected array $validationErrors = [];

    public function validate(): bool
    {
        $this->validationErrors = [];
        if (!isset($this->validationRules)) {
            return true; // No rules to validate against
        }

        foreach ($this->validationRules as $property => $rules) {
            foreach ($rules as $rule) {
                $this->runValidationRule($property, $rule);
            }
        }
        return empty($this->validationErrors);
    }

    public function getErrors(): array
    {
        return $this->validationErrors;
    }
    
    public function getFormattedErrors(): string
    {
        $output = "Validation Errors:\n";
        foreach($this->validationErrors as $field => $messages) {
            $output .= "  - " . ucfirst($field) . ": " . implode(', ', $messages) . "\n";
        }
        return $output;
    }

    private function runValidationRule(string $property, string $rule): void
    {
        $value = $this->{$property} ?? null;
        
        if ($rule === 'required' && ($value === null || $value === '')) {
            $this->addError($property, 'is required');
        }
        if ($rule === 'email' && $value && !filter_var($value, FILTER_VALIDATE_EMAIL)) {
            $this->addError($property, 'is not a valid email');
        }
        if (str_starts_with($rule, 'enum:')) {
            $enumClass = substr($rule, 5);
            $reflection = new ReflectionClass($enumClass);
            if (!in_array($value, $reflection->getConstants())) {
                $this->addError($property, 'has an invalid value');
            }
        }
        // Custom validator for password strength
        if ($rule === 'strong_password' && $value && !preg_match('/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/', $value)) {
            $this->addError($property, 'must be at least 8 chars with uppercase, lowercase, and a number');
        }
    }

    private function addError(string $property, string $message): void
    {
        $this->validationErrors[$property][] = $message;
    }
}

trait JsonSerializableTrait
{
    public function toJson(): string
    {
        return json_encode($this, JSON_PRETTY_PRINT);
    }

    public static function fromJson(string $json): self
    {
        $data = json_decode($json, true);
        $instance = new self();
        foreach ($data as $key => $value) {
            if (property_exists($instance, $key)) {
                $instance->{$key} = $value;
            }
        }
        return $instance;
    }
}

trait XmlSerializableTrait
{
    public function toXml(string $rootNode): string
    {
        $xml = new SimpleXMLElement("<{$rootNode}/>");
        foreach (get_object_vars($this) as $key => $value) {
            if (is_object($value) || is_array($value) || $key === 'validationRules') continue;
            $xml->addChild($key, htmlspecialchars($value));
        }
        return $xml->asXML();
    }

    public static function fromXml(string $xml): self
    {
        $data = simplexml_load_string($xml);
        $instance = new self();
        foreach ($data as $key => $value) {
            if (property_exists($instance, $key)) {
                // Basic type coercion from XML string
                if (is_numeric((string)$value) && strpos((string)$value, '.') !== false) {
                    $instance->{$key} = (float)$value;
                } elseif (is_numeric((string)$value)) {
                    $instance->{$key} = (int)$value;
                } elseif (in_array(strtolower((string)$value), ['true', 'false'])) {
                    $instance->{$key} = strtolower((string)$value) === 'true';
                } else {
                    $instance->{$key} = (string)$value;
                }
            }
        }
        return $instance;
    }
}

// --- DOMAIN ENTITIES USING TRAITS ---

class User
{
    use UuidGenerator, Validatable, JsonSerializableTrait, XmlSerializableTrait;

    public string $id;
    public string $email;
    public string $password_hash;
    public string $role;
    public bool $is_active;
    public string $created_at;

    protected array $validationRules = [
        'email' => ['required', 'email'],
        'password_hash' => ['required', 'strong_password'],
        'role' => ['required', 'enum:Role'],
    ];

    public function __construct()
    {
        $this->id = self::newUuid();
        $this->created_at = (new DateTime())->format('c'); // ISO 8601 format
        $this->is_active = false;
    }
}

// --- EXECUTION ---

echo "--- VARIATION 4: TRAIT-BASED --- \n\n";

// 1. Create an invalid User instance
$invalidUser = new User();
$invalidUser->email = "bad-email";
$invalidUser->password_hash = "weak";
$invalidUser->role = "GUEST";

echo "--- Validating an INVALID User ---\n";
if (!$invalidUser->validate()) {
    echo $invalidUser->getFormattedErrors();
}
echo "\n";

// 2. Create a valid User instance
$validUser = new User();
$validUser->email = "jane.doe@example.com";
$validUser->password_hash = "StrongPass123";
$validUser->role = Role::USER;
$validUser->is_active = true;

echo "--- Validating a VALID User ---\n";
if ($validUser->validate()) {
    echo "User is valid!\n";
    print_r($validUser);
    echo "\n";

    // 3. JSON Serialization
    echo "--- JSON Serialization ---\n";
    $json = $validUser->toJson();
    echo $json . "\n";

    // 4. JSON Deserialization
    echo "--- JSON Deserialization ---\n";
    $userFromJson = User::fromJson($json);
    echo "Deserialized user email: " . $userFromJson->email . "\n\n";

    // 5. XML Serialization
    echo "--- XML Serialization ---\n";
    $xml = $validUser->toXml('user');
    echo $xml . "\n";

    // 6. XML Deserialization
    echo "--- XML Deserialization ---\n";
    $userFromXml = User::fromXml($xml);
    echo "Deserialized user role: " . $userFromXml->role . "\n";
    echo "Is active (coerced type): " . ($userFromXml->is_active ? 'true' : 'false') . "\n";

} else {
    echo "Validation failed unexpectedly.\n";
    echo $validUser->getFormattedErrors();
}

?>