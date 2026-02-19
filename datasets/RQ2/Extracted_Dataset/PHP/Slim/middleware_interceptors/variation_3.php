<?php
/**
 * Variation 3: Route-Specific & Grouped Middleware
 *
 * This implementation demonstrates a more granular approach where middleware
 * is applied to specific routes or groups of routes. This is highly realistic,
 * as not all middleware (e.g., authentication, specific rate limiting) should
 * be global.
 *
 * Project Structure:
 * /
 * |- src/
 * |  |- Middleware/
 * |  |  |- AdminAuthMiddleware.php
 * |  |  |- ApiRateLimiter.php
 * |  |  |- GlobalCorsMiddleware.php
 * |- public/
 * |  |- index.php (This file)
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
    use Slim\Psr7\Response;

    class AdminAuthMiddleware implements MiddlewareInterface
    {
        public function process(ServerRequestInterface $request, RequestHandlerInterface $handler): ResponseInterface
        {
            // MOCK: In a real app, decode a JWT or check a session.
            $authHeader = $request->getHeaderLine('Authorization');
            if ($authHeader !== 'Bearer ADMIN_SECRET_TOKEN') {
                $response = new Response();
                $response->getBody()->write(json_encode(['error' => 'Forbidden']));
                return $response->withStatus(403)->withHeader('Content-Type', 'application/json');
            }
            // Add authenticated user info to the request for the controller to use
            $request = $request->withAttribute('user', ['id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef', 'role' => 'ADMIN']);
            return $handler->handle($request);
        }
    }

    class ApiRateLimiter implements MiddlewareInterface
    {
        private static array $ip_tracker = [];
        public function process(ServerRequestInterface $request, RequestHandlerInterface $handler): ResponseInterface
        {
            $ip = $request->getServerParams()['REMOTE_ADDR'] ?? '127.0.0.1';
            $now = time();
            self::$ip_tracker[$ip] = self::$ip_tracker[$ip] ?? [];
            self::$ip_tracker[$ip] = array_filter(self::$ip_tracker[$ip], fn($t) => $now - $t < 60); // 1 minute window
            if (count(self::$ip_tracker[$ip]) >= 50) { // 50 requests per minute
                $response = new Response();
                $response->getBody()->write(json_encode(['error' => 'API rate limit exceeded']));
                return $response->withStatus(429)->withHeader('Content-Type', 'application/json');
            }
            self::$ip_tracker[$ip][] = $now;
            return $handler->handle($request);
        }
    }

    class GlobalCorsMiddleware implements MiddlewareInterface
    {
        public function process(ServerRequestInterface $request, RequestHandlerInterface $handler): ResponseInterface
        {
            $response = $handler->handle($request);
            return $response
                ->withHeader('Access-Control-Allow-Origin', 'https://example.com')
                ->withHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization')
                ->withHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
        }
    }
}

// --- Main Application File (public/index.php) ---
namespace {
    require __DIR__ . '/../vendor/autoload.php';

    use App\Middleware\AdminAuthMiddleware;
    use App\Middleware\ApiRateLimiter;
    use App\Middleware\GlobalCorsMiddleware;
    use Psr\Http\Message\ResponseInterface as Response;
    use Psr\Http\Message\ServerRequestInterface as Request;
    use Slim\Factory\AppFactory;
    use Slim\Routing\RouteCollectorProxy;
    use Slim\Exception\HttpNotFoundException;

    // Mock Logger
    class FileLogger extends Psr\Log\AbstractLogger {
        public function log($level, $message, array $context = []) {
            file_put_contents('app.log', "[$level] $message\n", FILE_APPEND);
        }
    }
    $logger = new FileLogger();

    $app = AppFactory::create();

    // --- Global Middleware ---
    // These apply to ALL routes.
    $app->add(new GlobalCorsMiddleware());
    $app->addBodyParsingMiddleware(); // For request transformation (JSON body parsing)
    $app->addRoutingMiddleware();

    // Global Error Handler with response transformation
    $errorMiddleware = $app->addErrorMiddleware(true, true, true);
    $errorMiddleware->setDefaultErrorHandler(
        function (Request $request, Throwable $exception, bool $displayErrorDetails) use ($logger) {
            $logger->error($exception->getMessage());
            $statusCode = $exception instanceof HttpNotFoundException ? 404 : 500;
            $message = $exception instanceof HttpNotFoundException ? 'Resource not found.' : 'An internal error occurred.';
            
            $response = new \Slim\Psr7\Response();
            $response->getBody()->write(json_encode(['status' => 'error', 'message' => $message]));
            return $response->withStatus($statusCode)->withHeader('Content-Type', 'application/json');
        }
    );

    // --- Route Groups and Route-Specific Middleware ---

    $app->group('/api/v1', function (RouteCollectorProxy $group) {

        // Middleware for this entire group: Rate Limiting
        $group->add(new ApiRateLimiter());

        // Public route for posts
        $group->get('/posts', function (Request $request, Response $response) {
            $posts = [
                ['id' => 'c1d2e3f4-a5b6-7890-1234-567890abcdef', 'user_id' => 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', 'title' => 'Public Post', 'status' => 'PUBLISHED'],
            ];
            $response->getBody()->write(json_encode(['data' => $posts]));
            return $response->withHeader('Content-Type', 'application/json');
        });

        // A subgroup for admin-only routes, with its own auth middleware
        $group->group('/admin', function (RouteCollectorProxy $adminGroup) {
            $adminGroup->get('/users', function (Request $request, Response $response) {
                $users = [
                    ['id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef', 'email' => 'admin@example.com', 'role' => 'ADMIN'],
                ];
                $response->getBody()->write(json_encode(['data' => $users]));
                return $response->withHeader('Content-Type', 'application/json');
            });
        })->add(new AdminAuthMiddleware()); // Middleware applied only to the /admin group

    })->add(function ($request, $handler) { // A simple closure middleware for logging API requests
        file_put_contents('api.log', sprintf("API Request: %s %s\n", $request->getMethod(), $request->getUri()->getPath()), FILE_APPEND);
        return $handler->handle($request);
    });

    // A public route outside the API group that is not rate-limited or logged
    $app->get('/', function (Request $request, Response $response) {
        $response->getBody()->write('Welcome to the public homepage!');
        return $response;
    });

    $app->run();
}