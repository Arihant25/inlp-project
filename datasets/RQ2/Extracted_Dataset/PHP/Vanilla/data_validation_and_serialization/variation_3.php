<?php

// Variation 3: Service-Oriented with Entities and Exceptions
// Developer Style: Encapsulates logic in service classes, uses rich domain models (Entities), and prefers exceptions for error handling.
// Naming Convention: camelCase

// --- DOMAIN ENTITIES ---

class UserEntity
{
    private string $id;
    private string $email;
    private string $password_hash;
    private string $role;
    private bool $is_active;
    private DateTimeImmutable $created_at;

    public function __construct(string $id, string $email, string $password_hash, string $role, bool $is_active, DateTimeImmutable $created_at)
    {
        $this->id = $id;
        $this->email = $email;
        $this->password_hash = $password_hash;
        $this->role = $role;
        $this->is_active = $is_active;
        $this->created_at = $created_at;
    }

    public function getId(): string { return $this->id; }
    public function getEmail(): string { return $this->email; }
    public function getRole(): string { return $this->role; }
    public function isActive(): bool { return $this->is_active; }
    public function getCreatedAt(): DateTimeImmutable { return $this->created_at; }

    public function toArray(): array
    {
        return [
            'id' => $this->id,
            'email' => $this->email,
            'password_hash' => $this->password_hash,
            'role' => $this->role,
            'is_active' => $this->is_active,
            'created_at' => $this->created_at->format(DateTime::ATOM),
        ];
    }
}

// --- CUSTOM EXCEPTION ---

class ValidationException extends Exception
{
    private array $errors;

    public function __construct(array $errors, string $message = "Validation failed", int $code = 422, ?Throwable $previous = null)
    {
        parent::__construct($message, $code, $previous);
        $this->errors = $errors;
    }

    public function getErrors(): array
    {
        return $this->errors;
    }
}

// --- SERVICE CLASS ---

class UserService
{
    // In a real app, this would be in a separate utility class
    private function generateUuid(): string
    {
        return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x',
            mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff),
            mt_rand(0, 0x0fff) | 0x4000, mt_rand(0, 0x3fff) | 0x8000,
            mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)
        );
    }

    public function createUser(array $data): UserEntity
    {
        $this->validate($data);

        // Type Coercion
        $isActive = filter_var($data['is_active'], FILTER_VALIDATE_BOOLEAN);

        return new UserEntity(
            $this->generateUuid(),
            $data['email'],
            password_hash($data['password'], PASSWORD_DEFAULT),
            $data['role'],
            $isActive,
            new DateTimeImmutable()
        );
    }

    private function validate(array $data): void
    {
        $errors = [];

        if (empty($data['email'])) {
            $errors['email'] = 'Email is required.';
        } elseif (!filter_var($data['email'], FILTER_VALIDATE_EMAIL)) {
            $errors['email'] = 'Invalid email format.';
        }

        if (empty($data['password'])) {
            $errors['password'] = 'Password is required.';
        } elseif (strlen($data['password']) < 8) {
            $errors['password'] = 'Password must be at least 8 characters.'; // Custom validator
        }

        if (empty($data['role']) || !in_array($data['role'], ['ADMIN', 'USER'])) {
            $errors['role'] = 'Invalid role specified.';
        }

        if (!isset($data['is_active'])) {
            $errors['is_active'] = 'Active status is required.';
        }

        if (!empty($errors)) {
            throw new ValidationException($errors);
        }
    }
}

// --- DATA MAPPERS (Serialization/Deserialization) ---

class UserMapper
{
    public static function toJSON(UserEntity $user): string
    {
        return json_encode($user->toArray(), JSON_PRETTY_PRINT);
    }

    public static function fromJSON(string $json): array
    {
        $data = json_decode($json, true);
        if (json_last_error() !== JSON_ERROR_NONE) {
            throw new InvalidArgumentException("Invalid JSON provided.");
        }
        return $data;
    }

    public static function toXML(UserEntity $user): string
    {
        $data = $user->toArray();
        $xml = new SimpleXMLElement('<user/>');
        foreach ($data as $key => $value) {
            $xml->addChild($key, is_bool($value) ? ($value ? 'true' : 'false') : $value);
        }
        return $xml->asXML();
    }

    public static function fromXML(string $xml): array
    {
        $obj = simplexml_load_string($xml);
        return (array) $obj;
    }
}

// --- EXECUTION ---

echo "--- VARIATION 3: SERVICE-ORIENTED --- \n\n";

$userService = new UserService();

// 1. Attempt to create a user with invalid data
$invalidUserData = [
    'email' => 'not-an-email',
    'password' => 'short',
    'role' => 'SUPERUSER',
    // 'is_active' is missing
];

echo "--- Attempting creation with INVALID data ---\n";
try {
    $user = $userService->createUser($invalidUserData);
} catch (ValidationException $e) {
    echo "Caught ValidationException: " . $e->getMessage() . "\n";
    echo "Errors:\n";
    // Error message formatting
    foreach ($e->getErrors() as $field => $message) {
        echo " - {$field}: {$message}\n";
    }
}
echo "\n";

// 2. Create a user with valid data
$validUserData = [
    'email' => 'admin@corp.com',
    'password' => 'a_very_secure_password_123',
    'role' => 'ADMIN',
    'is_active' => '1',
];

echo "--- Attempting creation with VALID data ---\n";
try {
    $userEntity = $userService->createUser($validUserData);
    echo "User created successfully with ID: " . $userEntity->getId() . "\n";
    print_r($userEntity->toArray());
    echo "\n";

    // 3. JSON Serialization
    echo "--- JSON Serialization ---\n";
    $json = UserMapper::toJSON($userEntity);
    echo $json . "\n\n";

    // 4. JSON Deserialization
    echo "--- JSON Deserialization ---\n";
    $dataFromJson = UserMapper::fromJSON($json);
    print_r($dataFromJson);
    echo "\n";

    // 5. XML Serialization
    echo "--- XML Serialization ---\n";
    $xml = UserMapper::toXML($userEntity);
    echo $xml . "\n";
    
    // 6. XML Deserialization
    echo "--- XML Deserialization ---\n";
    $dataFromXml = UserMapper::fromXML($xml);
    print_r($dataFromXml);

} catch (ValidationException $e) {
    echo "User creation failed unexpectedly: " . $e->getMessage() . "\n";
    print_r($e->getErrors());
}

?>