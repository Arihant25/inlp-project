<?php

/**
 * Variation 4: The Middleware Architect
 *
 * This developer abstracts cross-cutting concerns like validation and body parsing into middleware.
 * This keeps route handlers extremely thin and focused purely on business logic.
 * A generic ValidationMiddleware is created that can be configured per-route with different rule sets.
 * An XmlBodyParserMiddleware handles content-type specific parsing before the request hits the route.
 *
 * To Run:
 * 1. Create composer.json:
 *    {
 *      "require": {
 *        "slim/slim": "^4.0",
 *        "slim/psr7": "^1.0",
 *        "respect/validation": "^2.2",
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
 * Example POST to /api/users (JSON):
 * curl -X POST http://localhost:8080/api/users -H "Content-Type: application/json" -d '{"email": "middleware@example.com", "password": "a-strong-password"}'
 *
 * Example POST to /api/posts (XML):
 * curl -X POST http://localhost:8080/api/posts -H "Content-Type: application/xml" -d '<post><user_id>123e4567-e89b-12d3-a456-426614174000</user_id><title>My XML Post</title><content>Content here</content></post>'
 */

// --- File: index.php ---

require __DIR__ . '/vendor/autoload.php';

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Slim\Factory\AppFactory;
use Slim\Routing\RouteCollectorProxy;
use App\Middleware\ValidationMiddleware;
use App\Middleware\XmlBodyParserMiddleware;
use App\Domain\User;
use App\Domain\UserRole;
use App\Domain\Post;
use App\Domain\PostStatus;
use Ramsey\Uuid\Uuid;
use Respect\Validation\Validator as v;
use Spatie\ArrayToXml\ArrayToXml;

$app = AppFactory::create();
$app->addErrorMiddleware(true, true, true);

// --- API Routes ---
$app->group('/api', function (RouteCollectorProxy $group) {

    // User creation route with JSON validation middleware
    $userValidationRules = v::key('email', v::email())
                           ->key('password', v::stringType()->length(8, null));

    $group->post('/users', function (Request $request, Response $response) {
        // The handler only runs if validation passes.
        $validatedData = $request->getAttribute('validated_data');

        $user = new User(
            id: Uuid::uuid4()->toString(),
            email: $validatedData['email'],
            password_hash: password_hash($validatedData['password'], PASSWORD_DEFAULT),
            role: UserRole::USER,
            is_active: false, // e.g., require email verification
            created_at: new \DateTimeImmutable()
        );

        $response->getBody()->write(json_encode($user->toArray()));
        return $response->withHeader('Content-Type', 'application/json')->withStatus(201);
    })->add(new ValidationMiddleware($userValidationRules));


    // Post creation route with XML body parser and validation middleware
    $postValidationRules = v::key('user_id', v::uuid(4))
                           ->key('title', v::stringType()->notEmpty())
                           ->key('content', v::stringType()->notEmpty());

    $group->post('/posts', function (Request $request, Response $response) {
        $validatedData = $request->getAttribute('validated_data');

        $post = new Post(
            id: Uuid::uuid4()->toString(),
            user_id: $validatedData['user_id'],
            title: $validatedData['title'],
            content: $validatedData['content'],
            status: PostStatus::DRAFT
        );

        $response->getBody()->write(json_encode($post->toArray()));
        return $response->withHeader('Content-Type', 'application/json')->withStatus(201);
    })->add(new ValidationMiddleware($postValidationRules))
      ->add(new XmlBodyParserMiddleware()); // XML parser runs first

    // Post retrieval with content negotiation
    $group->get('/posts/{id}', function (Request $request, Response $response, array $args) {
        $post = new Post(
            id: $args['id'], user_id: Uuid::uuid4()->toString(), title: 'Middleware Post',
            content: 'Content negotiation example.', status: PostStatus::PUBLISHED
        );

        $acceptHeader = $request->getHeaderLine('Accept');
        if (str_contains($acceptHeader, 'application/xml')) {
            $xml = ArrayToXml::convert($post->toArray(), 'post');
            $response->getBody()->write($xml);
            return $response->withHeader('Content-Type', 'application/xml');
        }

        $response->getBody()->write(json_encode($post->toArray()));
        return $response->withHeader('Content-Type', 'application/json');
    });
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

// --- File: src/Middleware/ValidationMiddleware.php ---
namespace App\Middleware;

use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface as Request;
use Psr\Http\Server\MiddlewareInterface;
use Psr\Http\Server\RequestHandlerInterface as RequestHandler;
use Respect\Validation\Exceptions\NestedValidationException;
use Respect\Validation\Validator;
use Slim\Psr7\Response;

class ValidationMiddleware implements MiddlewareInterface
{
    private Validator $rules;

    public function __construct(Validator $rules)
    {
        $this->rules = $rules;
    }

    public function process(Request $request, RequestHandler $handler): ResponseInterface
    {
        $data = (array)$request->getParsedBody();

        try {
            $this->rules->assert($data);
            // Add validated data to request attributes for the handler to use
            $request = $request->withAttribute('validated_data', $data);
            return $handler->handle($request);
        } catch (NestedValidationException $exception) {
            $response = new Response();
            $errors = $exception->getMessages();
            $payload = json_encode(['error' => 'Validation failed', 'messages' => $errors]);
            $response->getBody()->write($payload);
            return $response
                ->withHeader('Content-Type', 'application/json')
                ->withStatus(400);
        }
    }
}

// --- File: src/Middleware/XmlBodyParserMiddleware.php ---
namespace App\Middleware;

use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface as Request;
use Psr\Http\Server\MiddlewareInterface;
use Psr\Http\Server\RequestHandlerInterface as RequestHandler;
use Slim\Psr7\Response;

class XmlBodyParserMiddleware implements MiddlewareInterface
{
    public function process(Request $request, RequestHandler $handler): ResponseInterface
    {
        $contentType = $request->getHeaderLine('Content-Type');

        if (str_contains($contentType, 'application/xml')) {
            $body = (string)$request->getBody();
            if (empty($body)) {
                return $handler->handle($request->withParsedBody(null));
            }
            
            libxml_use_internal_errors(true);
            $xml = simplexml_load_string($body);

            if ($xml === false) {
                $response = new Response();
                $response->getBody()->write(json_encode(['error' => 'Malformed XML']));
                return $response->withStatus(400)->withHeader('Content-Type', 'application/json');
            }
            
            // Convert to array and set as parsed body
            $parsedBody = json_decode(json_encode($xml), true);
            $request = $request->withParsedBody($parsedBody);
        }

        return $handler->handle($request);
    }
}