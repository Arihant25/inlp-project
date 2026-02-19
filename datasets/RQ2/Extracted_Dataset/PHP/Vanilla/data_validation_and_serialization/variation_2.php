<?php

// Variation 2: Classic OOP with DTOs and Service Classes
// Developer Style: Prefers clear separation of concerns, uses static helper classes, and data transfer objects.
// Naming Convention: PascalCase for classes, camelCase for methods/variables.

// --- DOMAIN MODEL (Enums & DTOs) ---

final class UserRole
{
    const ADMIN = 'ADMIN';
    const USER = 'USER';
    public static function getValues(): array { return [self::ADMIN, self::USER]; }
}

final class PostStatus
{
    const DRAFT = 'DRAFT';
    const PUBLISHED = 'PUBLISHED';
    public static function getValues(): array { return [self::DRAFT, self::PUBLISHED]; }
}

class UserDTO
{
    public ?string $id = null;
    public ?string $email = null;
    public ?string $password_hash = null;
    public ?string $role = null;
    public ?bool $is_active = null;
    public ?string $created_at = null;
}

class PostDTO
{
    public ?string $id = null;
    public ?string $user_id = null;
    public ?string $title = null;
    public ?string $content = null;
    public ?string $status = null;
}

// --- VALIDATOR SERVICE ---

class Validator
{
    private array $errors = [];
    private array $data;

    public function __construct(array $data)
    {
        $this->data = $data;
    }

    public function getErrors(): array
    {
        return $this->errors;
    }

    public function passes(): bool
    {
        return empty($this->errors);
    }

    public function validate(array $rules): self
    {
        foreach ($rules as $field => $fieldRules) {
            $value = $this->data[$field] ?? null;
            foreach ($fieldRules as $rule) {
                $this->applyRule($field, $value, $rule);
            }
        }
        return $this;
    }

    private function applyRule(string $field, $value, string $rule): void
    {
        $params = [];
        if (strpos($rule, ':') !== false) {
            [$rule, $paramStr] = explode(':', $rule, 2);
            $params = explode(',', $paramStr);
        }

        $methodName = 'validate' . ucfirst($rule);
        if (method_exists($this, $methodName)) {
            $this->$methodName($field, $value, $params);
        }
    }

    private function addError(string $field, string $message): void
    {
        if (!isset($this->errors[$field])) {
            $this->errors[$field] = [];
        }
        $this->errors[$field][] = $message;
    }

    // --- Validation Rules ---
    private function validateRequired(string $field, $value): void
    {
        if ($value === null || $value === '') {
            $this->addError($field, "Field '{$field}' is required.");
        }
    }

    private function validateEmail(string $field, $value): void
    {
        if ($value && !filter_var($value, FILTER_VALIDATE_EMAIL)) {
            $this->addError($field, "Field '{$field}' must be a valid email.");
        }
    }

    // Custom validator
    private function validateUuid(string $field, $value): void
    {
        if ($value && !preg_match('/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i', $value)) {
            $this->addError($field, "Field '{$field}' must be a valid v4 UUID.");
        }
    }

    private function validateIn(string $field, $value, array $allowed): void
    {
        if ($value && !in_array($value, $allowed)) {
            $this->addError($field, "Field '{$field}' has an invalid value.");
        }
    }
}

// --- SERIALIZER SERVICE ---

class Serializer
{
    public static function toJson(object $dto): string
    {
        return json_encode(get_object_vars($dto), JSON_PRETTY_PRINT);
    }

    public static function fromJson(string $json, string $className): object
    {
        $data = json_decode($json, true);
        $dto = new $className();
        foreach ($data as $key => $value) {
            if (property_exists($dto, $key)) {
                $dto->$key = $value;
            }
        }
        return $dto;
    }

    public static function toXml(object $dto, string $rootNodeName): string
    {
        $dom = new DOMDocument('1.0', 'UTF-8');
        $dom->formatOutput = true;
        $root = $dom->createElement($rootNodeName);
        $dom->appendChild($root);

        foreach (get_object_vars($dto) as $key => $value) {
            if ($value !== null) {
                $child = $dom->createElement($key);
                $child->appendChild($dom->createTextNode(self::coerceToString($value)));
                $root->appendChild($child);
            }
        }
        return $dom->saveXML();
    }

    public static function fromXml(string $xml, string $className): object
    {
        $data = (array) simplexml_load_string($xml);
        $dto = new $className();
        foreach ($data as $key => $value) {
            if (property_exists($dto, $key)) {
                // Type coercion from string
                $reflectionProperty = new ReflectionProperty($className, $key);
                $type = $reflectionProperty->getType()->getName();
                $dto->$key = self::coerceType($value, $type);
            }
        }
        return $dto;
    }
    
    private static function coerceToString($value): string {
        if (is_bool($value)) return $value ? 'true' : 'false';
        return (string) $value;
    }

    private static function coerceType($value, string $type) {
        switch ($type) {
            case 'bool': return filter_var($value, FILTER_VALIDATE_BOOLEAN);
            case 'int': return (int) $value;
            case 'float': return (float) $value;
            default: return (string) $value;
        }
    }
}

// --- UTILITY ---
function generateUuidV4() {
    return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x',
        mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff),
        mt_rand(0, 0x0fff) | 0x4000, mt_rand(0, 0x3fff) | 0x8000,
        mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)
    );
}

// --- EXECUTION ---

echo "--- VARIATION 2: CLASSIC OOP --- \n\n";

$postInput = [
    'user_id' => generateUuidV4(),
    'title' => 'My First Post',
    'content' => '', // Invalid, should not be empty
    'status' => 'PENDING' // Invalid status
];

$postRules = [
    'user_id' => ['required', 'uuid'],
    'title' => ['required'],
    'content' => ['required'],
    'status' => ['required', 'in:' . implode(',', PostStatus::getValues())]
];

// 1. Validation
echo "--- Validating Post Data ---\n";
$validator = new Validator($postInput);
$validator->validate($postRules);

if (!$validator->passes()) {
    echo "Validation Failed:\n";
    print_r($validator->getErrors());
}
echo "\n";

// 2. Create and populate a valid DTO
$postDto = new PostDTO();
$postDto->id = generateUuidV4();
$postDto->user_id = generateUuidV4();
$postDto->title = 'A Valid Post Title';
$postDto->content = 'This is the content of the post.';
$postDto->status = PostStatus::PUBLISHED;

echo "--- Valid Post DTO ---\n";
print_r($postDto);
echo "\n";

// 3. JSON Serialization / Deserialization
echo "--- JSON Serialization ---\n";
$jsonString = Serializer::toJson($postDto);
echo $jsonString . "\n\n";

echo "--- JSON Deserialization ---\n";
$postFromJson = Serializer::fromJson($jsonString, PostDTO::class);
print_r($postFromJson);
echo "\n";

// 4. XML Serialization / Deserialization
echo "--- XML Serialization ---\n";
$xmlString = Serializer::toXml($postDto, 'post');
echo $xmlString . "\n";

echo "--- XML Deserialization ---\n";
$postFromXml = Serializer::fromXml($xmlString, PostDTO::class);
print_r($postFromXml);

?>