<?php

/**
 * Variation 3: The DTO Enthusiast
 *
 * This developer uses Data Transfer Objects (DTOs) to represent and validate incoming data.
 * The DTO is responsible for its own validation, creating a type-safe, self-contained object
 * that can be passed around the application. This decouples the domain logic from the raw request data.
 * This example uses the `rakit/validation` library and a custom validation rule class.
 *
 * To Run:
 * 1. Create composer.json:
 *    {
 *      "require": {
 *        "slim/slim": "^4.0",
 *        "slim/psr7": "^1.0",
 *        "rakit/validation": "^1.4",
 *        "ramsey/uuid": "^4.2",
 *        "spatie/array-to-xml": "^3.2"
 *      },
 *      "autoload": {
 *        "psr-4": {
 *          "App\\": "src/"
 *        }
 *      }
 *    }
 * 2. composer install
 * 3. Create the directory structure and files as below.
 * 4. php -S localhost:8080
 *
 * Example POST to /users:
 * curl -X POST http://localhost:8080/users -H "Content-Type: application/json" -d '{"email": "dto@example.com", "password": "a-strong-password", "phone": "+1-555-867-5309"}'
 *
 * Example POST to /posts (XML):
 * curl -X POST http://localhost:8080/posts -H "Content-Type: application/xml" -d '<post><user_id>123e4567-e89b-12d3-a456-426614174000</user_id><title>My XML Post</title><content>Content here</content></post>'
 */

// --- File: index.php ---

require __DIR__ . '/vendor/autoload.php';

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Slim\Factory\AppFactory;
use App\Dto\CreateUserDto;
use App\Dto\CreatePostDto;
use App\Domain\User;
use App\Domain\UserRole;
use App\Domain\Post;
use App\Domain\PostStatus;
use Ramsey\Uuid\Uuid;
use Spatie\ArrayToXml\ArrayToXml;

$app = AppFactory::create();
$app->addErrorMiddleware(true, true, true);

$app->post('/users', function (Request $request, Response $response) {
    try {
        $createUserDto = CreateUserDto::fromRequest((array)$request->getParsedBody());

        // Business logic uses the validated and type-safe DTO
        $user = new User(
            id: Uuid::uuid4()->toString(),
            email: $createUserDto->email,
            password_hash: password_hash($createUserDto->password, PASSWORD_DEFAULT),
            role: UserRole::USER,
            is_active: true,
            created_at: new \DateTimeImmutable()
        );

        $response->getBody()->write(json_encode($user->toArray(), JSON_PRETTY_PRINT));
        return $response->withHeader('Content-Type', 'application/json')->withStatus(201);

    } catch (\InvalidArgumentException $e) {
        $response->getBody()->write($e->getMessage()); // DTO exception provides formatted JSON
        return $response->withHeader('Content-Type', 'application/json')->withStatus(400);
    }
});

$app->post('/posts', function (Request $request, Response $response) {
    // XML Deserialization
    $xmlString = (string)$request->getBody();
    $xmlData = simplexml_load_string($xmlString, "SimpleXMLElement", LIBXML_NOCDATA);
    $data = json_decode(json_encode($xmlData), true);

    try {
        $createPostDto = CreatePostDto::fromRequest($data);

        $post = new Post(
            id: Uuid::uuid4()->toString(),
            user_id: $createPostDto->userId,
            title: $createPostDto->title,
            content: $createPostDto->content,
            status: PostStatus::DRAFT
        );

        $response->getBody()->write(json_encode($post->toArray(), JSON_PRETTY_PRINT));
        return $response->withHeader('Content-Type', 'application/json')->withStatus(201);

    } catch (\InvalidArgumentException $e) {
        $response->getBody()->write($e->getMessage());
        return $response->withHeader('Content-Type', 'application/json')->withStatus(400);
    }
});

$app->get('/posts/{id}', function (Request $request, Response $response, array $args) {
    $post = new Post(
        id: $args['id'], user_id: Uuid::uuid4()->toString(), title: 'DTO Post',
        content: 'Content negotiation example.', status: PostStatus::PUBLISHED
    );

    // Content Negotiation for XML/JSON Serialization
    $acceptHeader = $request->getHeaderLine('Accept');
    if (str_contains($acceptHeader, 'application/xml')) {
        $xml = ArrayToXml::convert($post->toArray(), 'post');
        $response->getBody()->write($xml);
        return $response->withHeader('Content-Type', 'application/xml');
    }

    $response->getBody()->write(json_encode($post->toArray()));
    return $response->withHeader('Content-Type', 'application/json');
});

$app->run();

// --- File: src/Domain/User.php (and Post.php) ---
// (Identical to Variation 2, omitted for brevity)
namespace App\Domain;
enum UserRole: string { case ADMIN = 'ADMIN'; case USER = 'USER'; }
class User {
    public function __construct(
        public string $id, public string $email, public string $password_hash,
        public UserRole $role, public bool $is_active, public \DateTimeImmutable $created_at
    ) {}
    public function toArray(): array {
        return [
            'id' => $this->id, 'email' => $this->email, 'role' => $this->role->value,
            'is_active' => $this->is_active, 'created_at' => $this->created_at->format(\DateTime::ATOM),
        ];
    }
}
enum PostStatus: string { case DRAFT = 'DRAFT'; case PUBLISHED = 'PUBLISHED'; }
class Post {
    public function __construct(
        public string $id, public string $user_id, public string $title,
        public string $content, public PostStatus $status
    ) {}
    public function toArray(): array {
        return [
            'id' => $this->id, 'user_id' => $this->user_id, 'title' => $this->title,
            'content' => $this->content, 'status' => $this->status->value,
        ];
    }
}


// --- File: src/Validation/Rules/E164PhoneRule.php ---
namespace App\Validation\Rules;

use Rakit\Validation\Rule;

class E164PhoneRule extends Rule
{
    protected $message = ":attribute must be a valid E.164 phone number";

    public function check($value): bool
    {
        // Simple regex for E.164 format, e.g., +15551234567
        return preg_match('/^\+[1-9]\d{1,14}$/', $value) === 1;
    }
}

// --- File: src/Dto/CreateUserDto.php ---
namespace App\Dto;

use Rakit\Validation\Validator;
use App\Validation\Rules\E164PhoneRule;

final class CreateUserDto
{
    public readonly string $email;
    public readonly string $password;
    public readonly ?string $phone;

    private function __construct(array $validatedData)
    {
        // Type Coercion
        $this->email = (string) $validatedData['email'];
        $this->password = (string) $validatedData['password'];
        $this->phone = isset($validatedData['phone']) ? (string) $validatedData['phone'] : null;
    }

    public static function fromRequest(array $data): self
    {
        $validator = new Validator;
        $validator->addValidator('e164_phone', new E164PhoneRule());

        $validation = $validator->make($data, [
            'email'    => 'required|email',
            'password' => 'required|min:8',
            'phone'    => 'e164_phone' // Optional, custom rule
        ]);

        $validation->validate();

        if ($validation->fails()) {
            $errors = $validation->errors()->toArray();
            $errorPayload = json_encode(['error' => 'Validation failed', 'messages' => $errors]);
            throw new \InvalidArgumentException($errorPayload);
        }

        return new self($validation->getValidatedData());
    }
}

// --- File: src/Dto/CreatePostDto.php ---
namespace App\Dto;

use Rakit\Validation\Validator;

final class CreatePostDto
{
    public readonly string $userId;
    public readonly string $title;
    public readonly string $content;

    private function __construct(array $validatedData)
    {
        $this->userId = (string) $validatedData['user_id'];
        $this->title = (string) $validatedData['title'];
        $this->content = (string) $validatedData['content'];
    }

    public static function fromRequest(array $data): self
    {
        $validator = new Validator;
        $validation = $validator->make($data, [
            'user_id' => 'required|regex:/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i',
            'title'   => 'required|max:255',
            'content' => 'required'
        ]);
        $validation->validate();

        if ($validation->fails()) {
            $errors = $validation->errors()->toArray();
            $errorPayload = json_encode(['error' => 'Validation failed', 'messages' => $errors]);
            throw new \InvalidArgumentException($errorPayload);
        }
        return new self($validation->getValidatedData());
    }
}