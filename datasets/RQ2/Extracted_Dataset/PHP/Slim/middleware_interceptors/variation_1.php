<?php
/**
 * Variation 1: Functional / Closure-based Style
 *
 * This implementation uses a single file and anonymous functions (closures)
 * for middleware and route handlers. It's simple, direct, and suitable for
 * microservices or small projects.
 *
 * To run:
 * 1. composer require slim/slim:"4.*" slim/psr7:"1.*" psr/log:"^1.1"
 * 2. Save this code as index.php
 * 3. Run `php -S localhost:8080` in the same directory.
 */

require __DIR__ . '/vendor/autoload.php';

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Psr\Http\Server\RequestHandlerInterface as RequestHandler;
use Slim\Factory\AppFactory;
use Slim\Exception\HttpBadRequestException;
use Slim\Exception\HttpNotFoundException;

// Mock Logger for demonstration purposes
class StdoutLogger extends Psr\Log\AbstractLogger {
    public function log($level, $message, array $context = []) {
        file_put_contents('php://stdout', sprintf("[%s] %s %s\n", strtoupper($level), $message, json_encode($context)));
    }
}
$logger = new StdoutLogger();

// In-memory store for rate limiting
$rateLimitTracker = [];

$app = AppFactory::create();

// Middleware are added in reverse order of execution (LIFO stack).

// 1. Logging Middleware (Executes fourth, just before the route handler)
$loggingMiddleware = function (Request $req, RequestHandler $h) use ($logger) {
    $method = $req->getMethod();
    $uri = (string) $req->getUri();
    $logger->info("Request received", ['method' => $method, 'uri' => $uri]);
    
    $res = $h->handle($req);
    
    $statusCode = $res->getStatusCode();
    $logger->info("Response sent", ['status' => $statusCode]);
    return $res;
};
$app->add($loggingMiddleware);

// 2. Request/Response Transformation Middleware (Executes third)
$transformationMiddleware = function (Request $req, RequestHandler $h) {
    // Request Transformation: Parse JSON body
    $contentType = $req->getHeaderLine('Content-Type');
    if (strpos($contentType, 'application/json') !== false) {
        $contents = json_decode(file_get_contents('php://input'), true);
        if (json_last_error() === JSON_ERROR_NONE) {
            $req = $req->withParsedBody($contents);
        }
    }

    $res = $h->handle($req);

    // Response Transformation: Wrap successful JSON responses in a standard envelope
    if ($res->getStatusCode() >= 200 && $res->getStatusCode() < 300 && strpos($res->getHeaderLine('Content-Type'), 'application/json') !== false) {
        $body = (string) $res->getBody();
        $data = json_decode($body, true);
        $payload = json_encode(['status' => 'success', 'data' => $data]);
        $res->getBody()->rewind();
        $res->getBody()->write($payload);
    }
    return $res;
};
$app->add($transformationMiddleware);

// 3. Rate Limiting Middleware (Executes second)
$rateLimitingMiddleware = function (Request $req, RequestHandler $h) use (&$rateLimitTracker) {
    $ip = $req->getServerParams()['REMOTE_ADDR'] ?? '127.0.0.1';
    $limit = 100; // 100 requests
    $window = 60; // per 60 seconds

    $currentTime = time();
    if (!isset($rateLimitTracker[$ip])) {
        $rateLimitTracker[$ip] = [];
    }
    // Remove timestamps older than the window
    $rateLimitTracker[$ip] = array_filter($rateLimitTracker[$ip], function($timestamp) use ($currentTime, $window) {
        return ($currentTime - $timestamp) < $window;
    });

    if (count($rateLimitTracker[$ip]) >= $limit) {
        $res = new \Slim\Psr7\Response();
        $res->getBody()->write(json_encode(['status' => 'error', 'message' => 'Too Many Requests']));
        return $res->withStatus(429)->withHeader('Content-Type', 'application/json');
    }

    $rateLimitTracker[$ip][] = $currentTime;
    return $h->handle($req);
};
$app->add($rateLimitingMiddleware);

// 4. CORS Middleware (Executes first, after Error Middleware)
$corsMiddleware = function (Request $req, RequestHandler $h) {
    if ($req->getMethod() === 'OPTIONS') {
        $response = new \Slim\Psr7\Response();
        return $response
            ->withHeader('Access-Control-Allow-Origin', '*')
            ->withHeader('Access-Control-Allow-Headers', 'X-Requested-With, Content-Type, Accept, Origin, Authorization')
            ->withHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, PATCH, OPTIONS');
    }
    $res = $h->handle($req);
    return $res
        ->withHeader('Access-Control-Allow-Origin', '*')
        ->withHeader('Access-Control-Allow-Headers', 'X-Requested-With, Content-Type, Accept, Origin, Authorization')
        ->withHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, PATCH, OPTIONS');
};
$app->add($corsMiddleware);

// 5. Error Handling Middleware (The outermost layer)
$errorMiddleware = $app->addErrorMiddleware(true, true, true);
$errorMiddleware->setDefaultErrorHandler(
    function (Request $request, Throwable $exception, bool $displayErrorDetails, bool $logErrors, bool $logErrorDetails) use ($logger) {
        $logger->error($exception->getMessage(), ['trace' => $exception->getTraceAsString()]);
        
        $statusCode = 500;
        if ($exception instanceof HttpNotFoundException) {
            $statusCode = 404;
        } elseif ($exception instanceof HttpBadRequestException) {
            $statusCode = 400;
        }

        $payload = ['status' => 'error', 'message' => $exception->getMessage()];
        $response = new \Slim\Psr7\Response();
        $response->getBody()->write(json_encode($payload, JSON_UNESCAPED_SLASHES));

        return $response->withStatus($statusCode)->withHeader('Content-Type', 'application/json');
    }
);

// --- Routes ---

$app->get('/users', function (Request $request, Response $response, $args) {
    $users = [
        ['id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef', 'email' => 'admin@example.com', 'role' => 'ADMIN', 'is_active' => true, 'created_at' => '2023-01-01 10:00:00'],
        ['id' => 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', 'email' => 'user@example.com', 'role' => 'USER', 'is_active' => true, 'created_at' => '2023-01-02 11:00:00'],
    ];
    $payload = json_encode($users);
    $response->getBody()->write($payload);
    return $response->withHeader('Content-Type', 'application/json');
});

$app->post('/posts', function (Request $request, Response $response, $args) {
    $postData = $request->getParsedBody();
    if (empty($postData['title']) || empty($postData['content'])) {
        throw new HttpBadRequestException($request, 'Title and content are required.');
    }
    $newPost = [
        'id' => bin2hex(random_bytes(16)),
        'user_id' => 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', // Mock user_id
        'title' => $postData['title'],
        'content' => $postData['content'],
        'status' => 'DRAFT'
    ];
    $response->getBody()->write(json_encode($newPost));
    return $response->withStatus(201)->withHeader('Content-Type', 'application/json');
});

$app->get('/error-test', function (Request $request, Response $response, $args) {
    throw new \RuntimeException("This is a test server error to demonstrate error handling.");
});

$app->run();