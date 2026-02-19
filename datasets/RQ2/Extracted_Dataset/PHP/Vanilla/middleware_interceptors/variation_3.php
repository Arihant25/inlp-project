<?php

// --- Domain Enums ---
enum UserRole: string { case ADMIN = 'ADMIN'; case USER = 'USER'; }
enum PostStatus: string { case DRAFT = 'DRAFT'; case PUBLISHED = 'PUBLISHED'; }

// --- Global Exception Handler ---
set_exception_handler(function (Throwable $e) {
    error_log("FATAL ERROR: " . $e->getMessage() . " in " . $e->getFile() . " on line " . $e->getLine());
    http_response_code(500);
    header('Content-Type: application/json');
    echo json_encode(['error' => 'A critical error occurred.']);
    exit;
});

// --- Middleware Functions ---

function create_logging_middleware(callable $next): callable {
    return function (array $request) use ($next) {
        error_log(sprintf(
            "Processing: %s %s from IP: %s",
            $request['method'],
            $request['path'],
            $request['ip']
        ));
        return $next($request);
    };
}

function create_cors_middleware(callable $next): callable {
    return function (array $request) use ($next) {
        if ($request['method'] === 'OPTIONS') {
            return [
                'status' => 204,
                'headers' => [
                    'Access-Control-Allow-Origin' => '*',
                    'Access-Control-Allow-Methods' => 'GET, POST, OPTIONS',
                    'Access-Control-Allow-Headers' => 'Content-Type',
                ],
                'body' => ''
            ];
        }
        $response = $next($request);
        $response['headers']['Access-Control-Allow-Origin'] = '*';
        return $response;
    };
}

function create_rate_limit_middleware(callable $next): callable {
    return function (array $request) use ($next) {
        static $hits = [];
        $limit = 100;
        $period = 60;
        $ip = $request['ip'];
        $now = time();

        $hits[$ip] = array_filter($hits[$ip] ?? [], fn($t) => ($now - $t) < $period);
        
        if (count($hits[$ip] ?? []) >= $limit) {
            return [
                'status' => 429,
                'headers' => ['Content-Type' => 'application/json'],
                'body' => json_encode(['error' => 'Too many requests'])
            ];
        }
        
        $hits[$ip][] = $now;
        return $next($request);
    };
}

// --- Decorator for Response Transformation ---

function with_json_response(callable $handler): callable {
    return function (array $request) use ($handler) {
        $data = $handler($request);
        return [
            'status' => 200,
            'headers' => ['Content-Type' => 'application/json'],
            'body' => json_encode($data)
        ];
    };
}

// --- Application Handlers ---

function get_user_handler(array $request): array {
    // Mock data for a user
    return [
        'id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
        'email' => 'functional.user@example.com',
        'role' => UserRole::USER->value,
        'is_active' => true,
        'created_at' => (new DateTime())->format(DateTime::ATOM)
    ];
}

function get_post_handler(array $request): array {
    // Mock data for a post
    return [
        'id' => 'f0e9d8c7-b6a5-4321-fedc-ba9876543210',
        'user_id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
        'title' => 'Functional Programming in PHP',
        'content' => 'A post about middleware implemented functionally.',
        'status' => PostStatus::PUBLISHED->value
    ];
}

// --- Dispatcher ---

function run_app() {
    $request = [
        'method' => $_SERVER['REQUEST_METHOD'],
        'uri' => $_SERVER['REQUEST_URI'],
        'path' => parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH),
        'ip' => $_SERVER['REMOTE_ADDR'] ?? '127.0.0.1',
    ];

    // Simple Router
    $routes = [
        'GET:/users/me' => 'get_user_handler',
        'GET:/posts/1' => 'get_post_handler',
    ];

    $route_key = $request['method'] . ':' . $request['path'];
    $handler_name = $routes[$route_key] ?? null;

    if (!$handler_name) {
        $final_handler = function(array $request) {
            return [
                'status' => 404,
                'headers' => ['Content-Type' => 'application/json'],
                'body' => json_encode(['error' => 'Endpoint not found'])
            ];
        };
    } else {
        // Decorate the handler for JSON transformation
        $final_handler = with_json_response($handler_name);
    }

    // Define the middleware pipeline in reverse order of execution
    $pipeline = [
        'create_logging_middleware',
        'create_rate_limit_middleware',
        'create_cors_middleware',
    ];

    // Wrap the final handler with all middleware
    $app = array_reduce(
        $pipeline,
        fn($next, $middleware) => $middleware($next),
        $final_handler
    );

    // Execute the full stack
    $response = $app($request);

    // Send response to client
    http_response_code($response['status']);
    foreach ($response['headers'] as $name => $value) {
        header("$name: $value");
    }
    echo $response['body'];
}

run_app();

?>