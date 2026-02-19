<?php

// --- Domain Enums ---
enum UserRoleEnum: string { case ADMIN = 'ADMIN'; case USER = 'USER'; }
enum PostStatusEnum: string { case DRAFT = 'DRAFT'; case PUBLISHED = 'PUBLISHED'; }

// --- Request/Response Context Object ---
class HttpContext {
    public array $request;
    public array $response = [
        'statusCode' => 200,
        'headers' => [],
        'body' => ''
    ];

    public function __construct() {
        $this->request = [
            'method' => $_SERVER['REQUEST_METHOD'],
            'uri' => $_SERVER['REQUEST_URI'],
            'path' => parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH),
            'ip' => $_SERVER['REMOTE_ADDR'] ?? '127.0.0.1',
            'body' => file_get_contents('php://input')
        ];
    }
}

// --- Middleware: Chain of Responsibility Pattern ---
abstract class AbstractMiddleware {
    protected ?AbstractMiddleware $next_middleware = null;

    public function setNext(AbstractMiddleware $next): AbstractMiddleware {
        $this->next_middleware = $next;
        return $next;
    }

    public function handle(HttpContext $context): HttpContext {
        if ($this->next_middleware) {
            return $this->next_middleware->handle($context);
        }
        return $context;
    }
}

class ErrorHandlerMiddleware extends AbstractMiddleware {
    public function handle(HttpContext $context): HttpContext {
        try {
            return parent::handle($context);
        } catch (Exception $e) {
            error_log("Chain Exception: " . $e->getMessage());
            $context->response['statusCode'] = 500;
            $context->response['headers']['Content-Type'] = 'application/json';
            $context->response['body'] = json_encode(['error' => 'Server Error']);
            return $context;
        }
    }
}

class LoggingMiddleware extends AbstractMiddleware {
    public function handle(HttpContext $context): HttpContext {
        $method = $context->request['method'];
        $path = $context->request['path'];
        error_log("Incoming Request: {$method} {$path}");
        return parent::handle($context);
    }
}

class CorsHandlerMiddleware extends AbstractMiddleware {
    public function handle(HttpContext $context): HttpContext {
        $context->response['headers']['Access-Control-Allow-Origin'] = '*';
        if ($context->request['method'] === 'OPTIONS') {
            $context->response['statusCode'] = 204;
            $context->response['headers']['Access-Control-Allow-Methods'] = 'GET, POST, OPTIONS';
            $context->response['headers']['Access-Control-Allow-Headers'] = 'Content-Type';
            return $context; // Terminate chain for OPTIONS
        }
        return parent::handle($context);
    }
}

class RateLimitMiddleware extends AbstractMiddleware {
    private static $ip_requests = [];
    const MAX_REQUESTS = 100;
    const TIME_WINDOW = 60;

    public function handle(HttpContext $context): HttpContext {
        $ip = $context->request['ip'];
        $currentTime = time();
        
        if (!isset(self::$ip_requests[$ip])) {
            self::$ip_requests[$ip] = [];
        }

        // Filter out old timestamps
        self::$ip_requests[$ip] = array_filter(self::$ip_requests[$ip], function($timestamp) use ($currentTime) {
            return ($currentTime - $timestamp) < self::TIME_WINDOW;
        });

        if (count(self::$ip_requests[$ip]) >= self::MAX_REQUESTS) {
            $context->response['statusCode'] = 429;
            $context->response['headers']['Content-Type'] = 'application/json';
            $context->response['body'] = json_encode(['error' => 'Rate limit exceeded']);
            return $context; // Terminate chain
        }

        self::$ip_requests[$ip][] = $currentTime;
        return parent::handle($context);
    }
}

// --- Decorator for Response Transformation ---
abstract class BaseController extends AbstractMiddleware {
    // This is the final link in the chain, it doesn't call parent::handle
}

class JsonResponseTransformer extends BaseController {
    private BaseController $wrapped_controller;

    public function __construct(BaseController $controller) {
        $this->wrapped_controller = $controller;
    }

    public function handle(HttpContext $context): HttpContext {
        $context = $this->wrapped_controller->handle($context);
        // The wrapped controller puts an array in the body, we transform it
        if (is_array($context->response['body'])) {
            $context->response['body'] = json_encode($context->response['body']);
            $context->response['headers']['Content-Type'] = 'application/json';
        }
        return $context;
    }
}

// --- Application Handlers ---
class GetUserEndpoint extends BaseController {
    public function handle(HttpContext $context): HttpContext {
        // Mock data
        $context->response['body'] = [
            'user' => [
                'id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
                'email' => 'user@example.com',
                'role' => UserRoleEnum::USER->value,
                'is_active' => true,
            ]
        ];
        return $context;
    }
}

class GetPostEndpoint extends BaseController {
    public function handle(HttpContext $context): HttpContext {
        // Mock data
        $context->response['body'] = [
            'post' => [
                'id' => 'f0e9d8c7-b6a5-4321-fedc-ba9876543210',
                'user_id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
                'title' => 'Chain of Responsibility',
                'status' => PostStatusEnum::PUBLISHED->value
            ]
        ];
        return $context;
    }
}

// --- Entry Point ---
function main() {
    $context = new HttpContext();

    // Simple Router
    $controller = null;
    switch ($context->request['path']) {
        case '/users/me':
            $controller = new GetUserEndpoint();
            break;
        case '/posts/1':
            $controller = new GetPostEndpoint();
            break;
        default:
            $context->response['statusCode'] = 404;
            $context->response['body'] = json_encode(['error' => 'Not Found']);
            $context->response['headers']['Content-Type'] = 'application/json';
    }

    if ($controller) {
        // Decorate the controller for JSON transformation
        $decoratedController = new JsonResponseTransformer($controller);

        // Build the middleware chain
        $chain = new ErrorHandlerMiddleware();
        $chain->setNext(new LoggingMiddleware())
              ->setNext(new CorsHandlerMiddleware())
              ->setNext(new RateLimitMiddleware())
              ->setNext($decoratedController); // The controller is the last link

        // Execute the chain
        $context = $chain->handle($context);
    }

    // Send the response
    http_response_code($context->response['statusCode']);
    foreach ($context->response['headers'] as $key => $value) {
        header("$key: $value");
    }
    echo $context->response['body'];
}

main();

?>