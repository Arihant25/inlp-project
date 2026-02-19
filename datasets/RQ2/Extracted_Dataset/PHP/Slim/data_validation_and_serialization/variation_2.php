<?php

/**
 * Variation 2: The OOP Purist
 *
 * This developer favors a structured, object-oriented approach using Action classes (single-action controllers)
 * and a dedicated, injectable Validator service. This promotes separation of concerns, testability, and reusability.
 * A custom exception is used for validation errors, which is caught by a middleware for a clean response.
 *
 * To Run:
 * 1. Create composer.json:
 *    {
 *      "require": {
 *        "slim/slim": "^4.0",
 *        "slim/psr7": "^1.0",
 *        "respect/validation": "^2.2",
 *        "ramsey/uuid": "^4.2",
 *        "php-di/php-di": "^6.3"
 *      },
 *      "autoload": {
 *        "psr-4": {
 *          "App\\": "src/"
 *        }
 *      }
 *    }
 * 2. composer install
 * 3. Create the directory structure and files as below (index.php, src/...).
 * 4. php -S localhost:8080
 *
 * Example POST to /users:
 * curl -X POST http://localhost:8080/users -H "Content-Type: application/json" -d '{"email": "oop@example.com", "password": "a-strong-password", "phone": "987-654-3210"}'
 */

// --- File: index.php ---

require __DIR__ . '/vendor/autoload.php';

use DI\Container;
use Slim\Factory\AppFactory;
use Psr\Http\Message\ServerRequestInterface as Request;
use Psr\Http\Server\RequestHandlerInterface as RequestHandler;
use App\Action\CreateUserAction;
use App\Action\GetPostAsXmlAction;
use App\Validation\ValidationException;

$container = new Container();
AppFactory::setContainer($container);

$app = AppFactory::create();

// Middleware to catch custom validation exceptions and format the response
$validationErrorMiddleware = function (Request $request, RequestHandler $handler) {
    try {
        return $handler->handle($request);
    } catch (ValidationException $e) {
        $responseFactory = new \Slim\Psr7\Factory\ResponseFactory();
        $response = $responseFactory->createResponse(400);
        $response->getBody()->write(json_encode([
            'error' => 'Validation Failed',
            'messages' => $e->getErrors()
        ], JSON_PRETTY_PRINT));
        return $response->withHeader('Content-Type', 'application/json');
    }
};
$app->add($validationErrorMiddleware);

$app->post('/users', CreateUserAction::class);
$app->get('/posts/{id}.xml', GetPostAsXmlAction::class);

$app->run();


// --- File: src/Domain/User.php ---
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

// --- File: src/Domain/Post.php ---
namespace App\Domain;

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


// --- File: src/Validation/Validator.php ---
namespace App\Validation;

use Respect\Validation\Exceptions\NestedValidationException;
use Respect\Validation\Validator as v;

class Validator
{
    public function validate(array $data, v $rules): array
    {
        try {
            $rules->assert($data);
            return $data;
        } catch (NestedValidationException $exception) {
            throw new ValidationException($exception->getMessages());
        }
    }
}

// --- File: src/Validation/ValidationException.php ---
namespace App\Validation;

class ValidationException extends \Exception
{
    private array $errors;

    public function __construct(array $errors, string $message = "Validation Failed", int $code = 0, ?\Throwable $previous = null)
    {
        parent::__construct($message, $code, $previous);
        $this->errors = $errors;
    }

    public function getErrors(): array
    {
        return $this->errors;
    }
}

// --- File: src/Action/CreateUserAction.php ---
namespace App\Action;

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use App\Validation\Validator;
use App\Domain\User;
use App\Domain\UserRole;
use Ramsey\Uuid\Uuid;
use Respect\Validation\Validator as v;

class CreateUserAction
{
    private Validator $validator;

    public function __construct(Validator $validator)
    {
        $this->validator = $validator;
    }

    public function __invoke(Request $request, Response $response): Response
    {
        $data = (array)$request->getParsedBody();

        // Define rules, including a custom one via a closure for demonstration
        $phoneValidator = v::callback(function ($input) {
            return preg_match('/^\d{3}-\d{3}-\d{4}$/', $input) === 1;
        })->setTemplate('{{name}} must be in the format XXX-XXX-XXXX');

        $rules = v::key('email', v::email()->notEmpty())
                 ->key('password', v::stringType()->length(8, null))
                 ->key('phone', $phoneValidator, false); // optional

        $validatedData = $this->validator->validate($data, $rules);

        // Type Coercion and Object Creation
        $user = new User(
            id: Uuid::uuid4()->toString(),
            email: (string) $validatedData['email'],
            password_hash: password_hash((string) $validatedData['password'], PASSWORD_DEFAULT),
            role: UserRole::USER,
            is_active: true,
            created_at: new \DateTimeImmutable()
        );

        $response->getBody()->write(json_encode($user->toArray(), JSON_PRETTY_PRINT));
        return $response->withHeader('Content-Type', 'application/json')->withStatus(201);
    }
}

// --- File: src/Action/GetPostAsXmlAction.php ---
namespace App\Action;

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use App\Domain\Post;
use App\Domain\PostStatus;
use Ramsey\Uuid\Uuid;

class GetPostAsXmlAction
{
    public function __invoke(Request $request, Response $response, array $args): Response
    {
        $post = new Post(
            id: $args['id'],
            user_id: Uuid::uuid4()->toString(),
            title: 'OOP Post Title',
            content: 'Content from the action class.',
            status: PostStatus::PUBLISHED
        );

        $xml = new \SimpleXMLElement('<post/>');
        array_walk_recursive($post->toArray(), function ($value, $key) use ($xml) {
            $xml->addChild($key, htmlspecialchars($value));
        });

        $response->getBody()->write($xml->asXML());
        return $response->withHeader('Content-Type', 'application/xml');
    }
}