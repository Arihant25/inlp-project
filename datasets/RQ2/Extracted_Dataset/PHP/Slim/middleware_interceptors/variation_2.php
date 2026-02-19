<?php
/**
 * Variation 2: Class-based Middleware (OOP)
 *
 * This implementation uses separate, invokable classes for each middleware,
 * promoting separation of concerns, testability, and reusability. This is a
 * common pattern for medium to large applications.
 *
 * Project Structure:
 * /
 * |- src/
 * |  |- Middleware/
 * |  |  |- CorsMiddleware.php
 * |  |  |- JsonResponseFormatterMiddleware.php
 * |  |  |- LoggingMiddleware.php
 * |  |  |- RateLimitingMiddleware.php
 * |  |  |- RequestBodyParserMiddleware.php
 * |  |- Handler/
 * |  |  |- CustomErrorHandler.php
 * |- public/
 * |  |- index.php  (This file)
 * |- vendor/
 * |- composer.json
 *
 * To run:
 * 1. Create the directory structure above.
 * 2. Place each class in its respective file.
 * 3. composer require slim/slim:"4.*" slim/psr7:"1.*" psr/log:"^1.1"
 * 4. composer dump-autoload -o
 * 5. Run `php -S localhost:8080 -t public`
 */

// The following code would be split into multiple files as described above.
// For this self-contained example, they are all included here.

namespace App\Middleware {
    use Psr\Http\Message\ResponseInterface;
    use Psr\Http\Message\ServerRequestInterface;
    use Psr\Http\Server\MiddlewareInterface;
    use Psr\Http\Server\RequestHandlerInterface;
    use Psr\Log\LoggerInterface;
    use Slim\Exception\HttpBadRequestException;
    use Slim\Psr7\Response;

    class LoggingMiddleware implements MiddlewareInterface
    {
        private LoggerInterface $logger;
        public function __construct(LoggerInterface $logger) { $this->logger = $logger; }
        public function process(ServerRequestInterface $request, RequestHandlerInterface $handler): ResponseInterface
        {
            $this->logger->info(sprintf("Request: %s %s", $request->getMethod(), $request->getUri()));
            $response = $handler->handle($request);
            $this->logger->info(sprintf("Response: %d", $response->getStatusCode()));
            return $response;
        }
    }

    class CorsMiddleware implements MiddlewareInterface
    {
        public function process(ServerRequestInterface $request, RequestHandlerInterface $handler): ResponseInterface
        {
            if ($request->getMethod() === 'OPTIONS') {
                $response = new Response();
            } else {
                $response = $handler->handle($request);
            }
            return $response
                ->withHeader('Access-Control-Allow-Origin', '*')
                ->withHeader('Access-Control-Allow-Headers', 'X-Requested-With, Content-Type, Accept, Origin, Authorization')
                ->withHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, PATCH, OPTIONS');
        }
    }

    class RateLimitingMiddleware implements MiddlewareInterface
    {
        private array $requests = [];
        private int $limit;
        private int $windowInSeconds;
        public function __construct(int $limit = 100, int $windowInSeconds = 60)
        {
            $this->limit = $limit;
            $this->windowInSeconds = $windowInSeconds;
        }
        public function process(ServerRequestInterface $request, RequestHandlerInterface $handler): ResponseInterface
        {
            $clientIp = $request->getServerParams()['REMOTE_ADDR'] ?? '127.0.0.1';
            $currentTime = time();
            if (!isset($this->requests[$clientIp])) {
                $this->requests[$clientIp] = [];
            }
            $this->requests[$clientIp] = array_filter($this->requests[$clientIp], fn($ts) => ($currentTime - $ts) < $this->windowInSeconds);
            if (count($this->requests[$clientIp]) >= $this->limit) {
                $response = new Response();
                $response->getBody()->write(json_encode(['status' => 'error', 'message' => 'Rate limit exceeded']));
                return $response->withStatus(429)->withHeader('Content-Type', 'application/json');
            }
            $this->requests[$clientIp][] = $currentTime;
            return $handler->handle($request);
        }
    }

    class RequestBodyParserMiddleware implements MiddlewareInterface
    {
        public function process(ServerRequestInterface $request, RequestHandlerInterface $handler): ResponseInterface
        {
            if (str_contains($request->getHeaderLine('Content-Type'), 'application/json')) {
                $contents = json_decode(file_get_contents('php://input'), true);
                if (json_last_error() !== JSON_ERROR_NONE) {
                    throw new HttpBadRequestException($request, 'Malformed JSON input.');
                }
                $request = $request->withParsedBody($contents);
            }
            return $handler->handle($request);
        }
    }

    class JsonResponseFormatterMiddleware implements MiddlewareInterface
    {
        public function process(ServerRequestInterface $request, RequestHandlerInterface $handler): ResponseInterface
        {
            $response = $handler->handle($request);
            $isSuccess = $response->getStatusCode() >= 200 && $response->getStatusCode() < 300;
            if ($isSuccess && str_contains($response->getHeaderLine('Content-Type'), 'application/json')) {
                $body = (string) $response->getBody();
                $originalData = json_decode($body, true);
                $payload = json_encode(['status' => 'success', 'data' => $originalData]);
                $response->getBody()->rewind();
                $response->getBody()->write($payload);
            }
            return $response;
        }
    }
}

namespace App\Handler {
    use Psr\Http\Message\ResponseInterface;
    use Psr\Log\LoggerInterface;
    use Slim\Exception\HttpException;
    use Slim\Handlers\ErrorHandler;

    class CustomErrorHandler extends ErrorHandler
    {
        protected LoggerInterface $logger;
        protected function respond(): ResponseInterface
        {
            $exception = $this->exception;
            $statusCode = ($exception instanceof HttpException) ? $exception->getCode() : 500;
            $message = ($exception instanceof HttpException) ? $exception->getMessage() : 'An internal server error occurred.';
            $this->logger->error(sprintf('%s: %s', get_class($exception), $exception->getMessage()));
            $payload = ['status' => 'error', 'message' => $message];
            $response = $this->responseFactory->createResponse($statusCode);
            $response->getBody()->write(json_encode($payload, JSON_PRETTY_PRINT));
            return $response->withHeader('Content-Type', 'application/json');
        }
        public function setLogger(LoggerInterface $logger): void { $this->logger = $logger; }
    }
}

// --- Main Application File (public/index.php) ---
namespace {
    require __DIR__ . '/../vendor/autoload.php';

    use App\Handler\CustomErrorHandler;
    use App\Middleware\CorsMiddleware;
    use App\Middleware\JsonBodyParserMiddleware;
    use App\Middleware\JsonResponseFormatterMiddleware;
    use App\Middleware\LoggingMiddleware;
    use App\Middleware\RateLimitingMiddleware;
    use Psr\Http\Message\ResponseInterface as Response;
    use Psr\Http\Message\ServerRequestInterface as Request;
    use Slim\Factory\AppFactory;
    use Slim\Exception\HttpBadRequestException;

    // Mock Logger
    class ConsoleLogger extends Psr\Log\AbstractLogger {
        public function log($level, $message, array $context = []) {
            file_put_contents('php://stdout', sprintf("[%s] %s\n", $level, $message));
        }
    }
    $logger = new ConsoleLogger();

    $app = AppFactory::create();

    // Add middleware in reverse order of execution (LIFO)
    $app->add(new LoggingMiddleware($logger));
    $app->add(new JsonResponseFormatterMiddleware());
    $app->add(new RequestBodyParserMiddleware());
    $app->add(new RateLimitingMiddleware(100, 60));
    $app->add(new CorsMiddleware());

    $app->addRoutingMiddleware();

    // Custom Error Handler
    $customErrorHandler = new CustomErrorHandler($app->getCallableResolver(), $app->getResponseFactory());
    $customErrorHandler->setLogger($logger);
    $errorMiddleware = $app->addErrorMiddleware(true, true, true);
    $errorMiddleware->setDefaultErrorHandler($customErrorHandler);

    // --- Routes ---
    $app->get('/users', function (Request $request, Response $response, array $args): Response {
        $users = [
            ['id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef', 'email' => 'admin@example.com', 'role' => 'ADMIN', 'is_active' => true],
            ['id' => 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', 'email' => 'user@example.com', 'role' => 'USER', 'is_active' => true],
        ];
        $response->getBody()->write(json_encode($users));
        return $response->withHeader('Content-Type', 'application/json');
    });

    $app->post('/posts', function (Request $request, Response $response, array $args): Response {
        $postData = $request->getParsedBody();
        if (empty($postData['title'])) {
            throw new HttpBadRequestException($request, 'Title is required.');
        }
        $newPost = [
            'id' => bin2hex(random_bytes(16)),
            'user_id' => 'b2c3d4e5-f6a7-8901-2345-67890abcdef0',
            'title' => htmlspecialchars($postData['title']),
            'content' => htmlspecialchars($postData['content'] ?? ''),
            'status' => 'DRAFT'
        ];
        $response->getBody()->write(json_encode($newPost));
        return $response->withStatus(201)->withHeader('Content-Type', 'application/json');
    });

    $app->run();
}