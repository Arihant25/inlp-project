<?php

/**
 * Variation 1: The Pragmatist
 *
 * This developer prefers a straightforward, functional approach.
 * Validation logic is placed directly within the route handler for simplicity and co-location.
 * It's a common pattern for smaller APIs or microservices where extensive layering is not yet needed.
 *
 * To Run:
 * 1. composer require slim/slim slim/psr7 respect/validation ramsey/uuid
 * 2. php -S localhost:8080
 *
 * Example POST to /users:
 * curl -X POST http://localhost:8080/users -H "Content-Type: application/json" -d '{"email": "test@example.com", "password": "a-strong-password", "phone": "123-456-7890"}'
 *
 * Example GET from /posts/some-uuid.xml:
 * curl http://localhost:8080/posts/123e4567-e89b-12d3-a456-426614174000.xml
 */

require __DIR__ . '/vendor/autoload.php';

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Slim\Factory\AppFactory;
use Respect\Validation\Validator as v;
use Respect\Validation\Exceptions\NestedValidationException;
use Ramsey\Uuid\Uuid;

// --- Domain Model ---

enum UserRole: string {
    case ADMIN = 'ADMIN';
    case USER = 'USER';
}

class User {
    public function __construct(
        public string $id,
        public string $email,
        public string $password_hash,
        public UserRole $role,
        public bool $is_active,
        public DateTimeImmutable $created_at
    ) {}

    public function toArray(): array {
        return [
            'id' => $this->id,
            'email' => $this->email,
            'role' => $this->role->value,
            'is_active' => $this->is_active,
            'created_at' => $this->created_at->format(DateTime::ATOM),
        ];
    }
}

enum PostStatus: string {
    case DRAFT = 'DRAFT';
    case PUBLISHED = 'PUBLISHED';
}

class Post {
    public function __construct(
        public string $id,
        public string $user_id,
        public string $title,
        public string $content,
        public PostStatus $status
    ) {}

    public function toArray(): array {
        return [
            'id' => $this->id,
            'user_id' => $this->user_id,
            'title' => $this->title,
            'content' => $this->content,
            'status' => $this->status->value,
        ];
    }
}

// --- App Setup ---

$app = AppFactory::create();
$app->addErrorMiddleware(true, true, true);

// --- Custom Validator ---
// In this functional style, we can define a custom rule as a reusable validator instance.
$phoneValidator = v::regex('/^\d{3}-\d{3}-\d{4}$/')->setTemplate('{{name}} must be in the format XXX-XXX-XXXX');

// --- Routes ---

$app->post('/users', function (Request $request, Response $response) use ($phoneValidator) {
    $data = (array)$request->getParsedBody();

    $userValidator = v::key('email', v::email()->notEmpty())
                     ->key('password', v::stringType()->length(8, null))
                     ->key('phone', $phoneValidator, false); // 'phone' is optional

    try {
        $userValidator->assert($data);

        // Type Coercion & Object Creation
        $user = new User(
            id: Uuid::uuid4()->toString(),
            email: (string) $data['email'],
            password_hash: password_hash((string) $data['password'], PASSWORD_DEFAULT),
            role: UserRole::USER,
            is_active: true,
            created_at: new \DateTimeImmutable()
        );

        $payload = json_encode($user->toArray(), JSON_PRETTY_PRINT);
        $response->getBody()->write($payload);
        return $response
            ->withHeader('Content-Type', 'application/json')
            ->withStatus(201);

    } catch (NestedValidationException $exception) {
        // Error Message Formatting
        $errors = $exception->getMessages();
        $payload = json_encode(['error' => 'Validation failed', 'messages' => $errors], JSON_PRETTY_PRINT);
        $response->getBody()->write($payload);
        return $response
            ->withHeader('Content-Type', 'application/json')
            ->withStatus(400);
    }
});

$app->post('/posts', function (Request $request, Response $response) {
    // XML Parsing
    $contentType = $request->getHeaderLine('Content-Type');
    if (strpos($contentType, 'application/xml') === false) {
        $response->getBody()->write(json_encode(['error' => 'Content-Type must be application/xml']));
        return $response->withStatus(415)->withHeader('Content-Type', 'application/json');
    }

    $xmlString = (string)$request->getBody();
    $xml = simplexml_load_string($xmlString);
    if ($xml === false) {
        $response->getBody()->write(json_encode(['error' => 'Invalid XML format']));
        return $response->withStatus(400)->withHeader('Content-Type', 'application/json');
    }
    $data = json_decode(json_encode($xml), true); // Convert SimpleXMLElement to array

    // Validation
    $postValidator = v::key('user_id', v::uuid(4))
                     ->key('title', v::stringType()->notEmpty()->length(1, 100))
                     ->key('content', v::stringType()->notEmpty());

    try {
        $postValidator->assert($data);
        $post = new Post(
            id: Uuid::uuid4()->toString(),
            user_id: (string) $data['user_id'],
            title: (string) $data['title'],
            content: (string) $data['content'],
            status: PostStatus::DRAFT
        );

        $response->getBody()->write(json_encode($post->toArray()));
        return $response->withStatus(201)->withHeader('Content-Type', 'application/json');
    } catch (NestedValidationException $exception) {
        $response->getBody()->write(json_encode(['error' => 'Validation failed', 'messages' => $exception->getMessages()]));
        return $response->withStatus(400)->withHeader('Content-Type', 'application/json');
    }
});


$app->get('/posts/{id}.xml', function (Request $request, Response $response, array $args) {
    // Mock data retrieval
    $post = new Post(
        id: $args['id'],
        user_id: Uuid::uuid4()->toString(),
        title: 'A Post Title',
        content: 'This is the content of the post.',
        status: PostStatus::PUBLISHED
    );

    // XML Generation
    $xml = new SimpleXMLElement('<post/>');
    $postArray = $post->toArray();
    foreach ($postArray as $key => $value) {
        $xml->addChild($key, htmlspecialchars($value));
    }

    $response->getBody()->write($xml->asXML());
    return $response->withHeader('Content-Type', 'application/xml');
});


$app->run();