<?php
/**
 * Variation 4: Service-oriented / Dependency Injection
 *
 * This implementation uses a DI container (PHP-DI) to manage all dependencies,
 * including middleware, controllers, and services like logging. This promotes
 * a highly decoupled, testable, and scalable architecture, typical of large,
 * enterprise-grade applications.
 *
 * Project Structure:
 * /
 * |- config/
 * |  |- container.php
 * |- src/
 * |  |- Controller/
 * |  |  |- UserController.php
 * |  |  |- PostController.php
 * |  |- Middleware/
 * |  |  |- ResponseEnvelopeMiddleware.php
 * |  |  |- TrafficLogMiddleware.php
 * |- public/
 * |  |- index.php (This file)
 * |- vendor/
 * |- composer.json
 *
 * To run:
 * 1. Create the directory structure. Place each class in its respective file.
 * 2. composer require slim/slim:"4.*" slim/psr7:"1.*" php-di/php-di:"^6.3" psr/log:"^1.1" monolog/monolog:"^2.3" di/bridge-slim
 * 3. composer dump-autoload -o
 * 4. Run `php -S localhost:8080 -t public`
 */

// --- File: config/container.php ---
namespace {
    use DI\ContainerBuilder;
    use Monolog\Handler\StreamHandler;
    use Monolog\Logger;
    use Monolog\Processor\UidProcessor;
    use Psr\Container\ContainerInterface;
    use Psr\Log\LoggerInterface;

    $containerBuilder = new ContainerBuilder();
    $containerBuilder->addDefinitions([
        LoggerInterface::class => function (ContainerInterface $c) {
            $logger = new Logger('app_logger');
            $logger->pushProcessor(new UidProcessor());
            $logger->pushHandler(new StreamHandler('php://stdout', Logger::DEBUG));
            return $logger;
        },
    ]);
    return $containerBuilder->build();
}

// --- File: src/Controller/UserController.php ---
namespace App\Controller {
    use Psr\Http\Message\ResponseInterface as Response;
    use Psr\Http\Message\ServerRequestInterface as Request;
    use Psr\Log\LoggerInterface;

    class UserController {
        private LoggerInterface $logger;
        public function __construct(LoggerInterface $logger) { $this->logger = $logger; }
        public function listAll(Request $request, Response $response): Response {
            $this->logger->info("User list requested.");
            $users = [
                ['id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef', 'email' => 'admin@example.com', 'role' => 'ADMIN'],
            ];
            $response->getBody()->write(json_encode($users));
            return $response->withHeader('Content-Type', 'application/json');
        }
    }
}

// --- File: src/Controller/PostController.php ---
namespace App\Controller {
    use Psr\Http\Message\ResponseInterface as Response;
    use Psr\Http\Message\ServerRequestInterface as Request;
    use Slim\Exception\HttpBadRequestException;

    class PostController {
        public function create(Request $request, Response $response): Response {
            $parsedBody = $request->getParsedBody();
            if (!isset($parsedBody['title'])) {
                throw new HttpBadRequestException($request, "Field 'title' is required.");
            }
            $newPost = ['id' => uniqid(), 'title' => $parsedBody['title'], 'status' => 'DRAFT'];
            $response->getBody()->write(json_encode($newPost));
            return $response->withStatus(201)->withHeader('Content-Type', 'application/json');
        }
    }
}

// --- File: src/Middleware/TrafficLogMiddleware.php ---
namespace App\Middleware {
    use Psr\Http\Message\ResponseInterface;
    use Psr\Http\Message\ServerRequestInterface;
    use Psr\Http\Server\MiddlewareInterface;
    use Psr\Http\Server\RequestHandlerInterface;
    use Psr\Log\LoggerInterface;

    class TrafficLogMiddleware implements MiddlewareInterface {
        private LoggerInterface $logger;
        public function __construct(LoggerInterface $logger) { $this->logger = $logger; }
        public function process(ServerRequestInterface $request, RequestHandlerInterface $handler): ResponseInterface {
            $this->logger->info("Incoming traffic", ['method' => $request->getMethod(), 'uri' => (string)$request->getUri()]);
            return $handler->handle($request);
        }
    }
}

// --- File: src/Middleware/ResponseEnvelopeMiddleware.php ---
namespace App\Middleware {
    use Psr\Http\Message\ResponseInterface;
    use Psr\Http\Message\ServerRequestInterface;
    use Psr\Http\Server\MiddlewareInterface;
    use Psr\Http\Server\RequestHandlerInterface;

    class ResponseEnvelopeMiddleware implements MiddlewareInterface {
        public function process(ServerRequestInterface $request, RequestHandlerInterface $handler): ResponseInterface {
            $response = $handler->handle($request);
            if ($response->getStatusCode() >= 200 && $response->getStatusCode() < 300 && str_contains($response->getHeaderLine('Content-Type'), 'application/json')) {
                $body = (string) $response->getBody();
                $payload = json_encode(['status' => 'success', 'data' => json_decode($body)]);
                $response->getBody()->rewind();
                $response->getBody()->write($payload);
            }
            return $response;
        }
    }
}

// --- File: public/index.php ---
namespace {
    require __DIR__ . '/../vendor/autoload.php';

    use App\Controller\PostController;
    use App\Controller\UserController;
    use App\Middleware\ResponseEnvelopeMiddleware;
    use App\Middleware\TrafficLogMiddleware;
    use DI\Bridge\Slim\Bridge;
    use Psr\Log\LoggerInterface;

    $container = require __DIR__ . '/../config/container.php';
    $app = Bridge::create($container);

    // --- Middleware Pipeline (LIFO order) ---
    $app->add(TrafficLogMiddleware::class);
    $app->add(ResponseEnvelopeMiddleware::class); // Response transformation
    $app->addBodyParsingMiddleware(); // Request transformation

    // Rate Limiting (as a closure for simplicity)
    $app->add(function ($request, $handler) {
        static $hits = [];
        $ip = $request->getServerParams()['REMOTE_ADDR'] ?? '127.0.0.1';
        if (!isset($hits[$ip])) $hits[$ip] = 0;
        if ($hits[$ip]++ > 100) { // Simple counter, not time-based for this example
            $response = new \Slim\Psr7\Response(429);
            $response->getBody()->write(json_encode(['status' => 'error', 'message' => 'Rate limit hit']));
            return $response->withHeader('Content-Type', 'application/json');
        }
        return $handler->handle($request);
    });

    // CORS Handling
    $app->add(function ($request, $handler) {
        $response = $handler->handle($request);
        return $response
            ->withHeader('Access-Control-Allow-Origin', '*')
            ->withHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization')
            ->withHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    });
    $app->options('/{routes:.+}', fn($req, $res) => $res); // Pre-flight requests

    $app->addRoutingMiddleware();

    // Error Handling
    $errorMiddleware = $app->addErrorMiddleware(true, true, true);
    $errorMiddleware->setDefaultErrorHandler(function ($request, $exception, $d, $l, $ld) use ($container) {
        $container->get(LoggerInterface::class)->error($exception->getMessage());
        $response = new \Slim\Psr7\Response();
        $response->getBody()->write(json_encode(['status' => 'error', 'message' => $exception->getMessage()]));
        return $response->withStatus(500)->withHeader('Content-Type', 'application/json');
    });

    // --- Routes ---
    // Controllers are resolved and instantiated by the DI container
    $app->get('/users', [UserController::class, 'listAll']);
    $app->post('/posts', [PostController::class, 'create']);

    $app->run();
}