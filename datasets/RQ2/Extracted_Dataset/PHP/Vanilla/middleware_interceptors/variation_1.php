<?php

// --- Domain Model & Enums ---

enum UserRole: string {
    case ADMIN = 'ADMIN';
    case USER = 'USER';
}

enum PostStatus: string {
    case DRAFT = 'DRAFT';
    case PUBLISHED = 'PUBLISHED';
}

// --- Core Abstractions (Request, Response, Middleware) ---

final class Request {
    public function __construct(
        public readonly array $server,
        public readonly array $get,
        public readonly array $post,
        public readonly ?array $json,
        public readonly string $ip
    ) {}

    public static function capture(): self {
        $json = null;
        if (isset($_SERVER['CONTENT_TYPE']) && str_contains($_SERVER['CONTENT_TYPE'], 'application/json')) {
            $input = file_get_contents('php://input');
            $json = json_decode($input, true);
        }
        return new self(
            $_SERVER,
            $_GET,
            $_POST,
            $json,
            $_SERVER['REMOTE_ADDR'] ?? '127.0.0.1'
        );
    }

    public function getPath(): string {
        return parse_url($this->server['REQUEST_URI'], PHP_URL_PATH);
    }

    public function getMethod(): string {
        return $this->server['REQUEST_METHOD'];
    }
}

final class Response {
    public function __construct(
        private string $content,
        private int $statusCode = 200,
        private array $headers = []
    ) {}

    public function send(): void {
        http_response_code($this->statusCode);
        foreach ($this->headers as $name => $value) {
            header("$name: $value");
        }
        echo $this->content;
    }
}

interface Middleware {
    public function process(Request $request, callable $next): Response;
}

// --- Middleware Implementations ---

class ErrorHandlingMiddleware implements Middleware {
    public function process(Request $request, callable $next): Response {
        try {
            return $next($request);
        } catch (Throwable $e) {
            error_log("Uncaught Exception: " . $e->getMessage());
            $statusCode = method_exists($e, 'getStatusCode') ? $e->getStatusCode() : 500;
            $errorData = [
                'error' => [
                    'message' => 'An internal server error occurred.',
                    'type' => get_class($e),
                ]
            ];
            return new Response(json_encode($errorData), $statusCode, ['Content-Type' => 'application/json']);
        }
    }
}

class RequestLoggingMiddleware implements Middleware {
    public function process(Request $request, callable $next): Response {
        $start = microtime(true);
        $response = $next($request);
        $duration = microtime(true) - $start;
        
        $logMessage = sprintf(
            "[%s] %s %s - %dms",
            $request->ip,
            $request->getMethod(),
            $request->getPath(),
            round($duration * 1000)
        );
        error_log($logMessage);

        return $response;
    }
}

class CorsMiddleware implements Middleware {
    public function process(Request $request, callable $next): Response {
        if ($request->getMethod() === 'OPTIONS') {
            return new Response('', 204, [
                'Access-Control-Allow-Origin' => '*',
                'Access-Control-Allow-Methods' => 'GET, POST, OPTIONS',
                'Access-Control-Allow-Headers' => 'Content-Type, Authorization',
            ]);
        }
        
        $response = $next($request);
        $response->headers['Access-Control-Allow-Origin'] = '*';
        return $response;
    }
}

class RateLimitingMiddleware implements Middleware {
    private static array $requests = [];
    private const LIMIT = 100; // 100 requests
    private const PERIOD = 60; // per 60 seconds

    public function process(Request $request, callable $next): Response {
        $now = time();
        $ip = $request->ip;

        // Clean up old requests
        self::$requests[$ip] = array_filter(
            self::$requests[$ip] ?? [],
            fn($timestamp) => ($now - $timestamp) < self::PERIOD
        );

        if (count(self::$requests[$ip] ?? []) >= self::LIMIT) {
            $errorData = ['error' => ['message' => 'Too Many Requests']];
            return new Response(json_encode($errorData), 429, ['Content-Type' => 'application/json']);
        }

        self::$requests[$ip][] = $now;
        return $next($request);
    }
}

// --- Decorator for Response Transformation ---

interface Handler {
    public function handle(Request $request): array;
}

class JsonResponseDecorator implements Middleware {
    private Handler $handler;

    public function __construct(Handler $handler) {
        $this->handler = $handler;
    }

    public function process(Request $request, callable $next): Response {
        $data = $this->handler->handle($request);
        return new Response(json_encode($data), 200, ['Content-Type' => 'application/json']);
    }
}

// --- Application Logic (Router, Handlers) ---

class UserHandler implements Handler {
    public function handle(Request $request): array {
        // Mock data access
        return [
            'data' => [
                'id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
                'email' => 'admin@example.com',
                'role' => UserRole::ADMIN->value,
                'is_active' => true,
                'created_at' => '2023-10-27T10:00:00Z'
            ]
        ];
    }
}

class PostHandler implements Handler {
    public function handle(Request $request): array {
        // Mock data access
        return [
            'data' => [
                'id' => 'f0e9d8c7-b6a5-4321-fedc-ba9876543210',
                'user_id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
                'title' => 'Hello, Middleware!',
                'content' => 'This is a post served through a middleware stack.',
                'status' => PostStatus::PUBLISHED->value
            ]
        ];
    }
}

class Kernel {
    private array $middlewareStack;

    public function __construct(array $middleware) {
        $this->middlewareStack = $middleware;
    }

    public function handle(Request $request): Response {
        $core = function(Request $request) {
            // Default "Not Found" response if no route matches
            return new Response(json_encode(['error' => 'Not Found']), 404, ['Content-Type' => 'application/json']);
        };

        $chain = array_reduce(
            array_reverse($this->middlewareStack),
            function ($next, $middleware) {
                return function (Request $request) use ($next, $middleware) {
                    return $middleware->process($request, $next);
                };
            },
            $core
        );

        return $chain($request);
    }
}

// --- Entry Point ---

// Simple Router
$request = Request::capture();
$path = $request->getPath();
$handler = null;

if ($path === '/users/me' && $request->getMethod() === 'GET') {
    $handler = new UserHandler();
} elseif ($path === '/posts/1' && $request->getMethod() === 'GET') {
    $handler = new PostHandler();
}

// Build Middleware Stack
$stack = [
    new ErrorHandlingMiddleware(),
    new RequestLoggingMiddleware(),
    new CorsMiddleware(),
    new RateLimitingMiddleware(),
];

if ($handler) {
    // Add the response transformation decorator at the end of the chain
    $stack[] = new JsonResponseDecorator($handler);
}

// Run the application
$kernel = new Kernel($stack);
$response = $kernel->handle($request);
$response->send();

?>