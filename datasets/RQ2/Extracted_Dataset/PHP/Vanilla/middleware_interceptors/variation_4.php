<?php

// --- Domain Model Enums ---
const USER_ROLE_ADMIN = 'ADMIN';
const USER_ROLE_USER = 'USER';
const POST_STATUS_DRAFT = 'DRAFT';
const POST_STATUS_PUBLISHED = 'PUBLISHED';

// --- Mock Data Store ---
$db_users = [
    'a1b2c3d4-e5f6-7890-1234-567890abcdef' => [
        'id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
        'email' => 'procedural@example.com',
        'password_hash' => '...hashed...',
        'role' => USER_ROLE_ADMIN,
        'is_active' => true,
        'created_at' => '2023-01-01 12:00:00'
    ]
];

$db_posts = [
    'f0e9d8c7-b6a5-4321-fedc-ba9876543210' => [
        'id' => 'f0e9d8c7-b6a5-4321-fedc-ba9876543210',
        'user_id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
        'title' => 'Simple PHP',
        'content' => 'This is a post.',
        'status' => POST_STATUS_PUBLISHED
    ]
];

// --- Middleware Functions ---

function handle_errors() {
    set_error_handler(function($severity, $message, $file, $line) {
        if (!(error_reporting() & $severity)) {
            return;
        }
        throw new ErrorException($message, 0, $severity, $file, $line);
    });

    set_exception_handler(function($exception) {
        error_log("Caught Exception: " . $exception->getMessage());
        send_response(500, ['Content-Type' => 'application/json'], json_encode(['error' => 'Internal Server Error']));
    });
}

function log_request(&$context) {
    $log_entry = sprintf(
        "[%s] %s %s",
        date('Y-m-d H:i:s'),
        $context['request']['method'],
        $context['request']['path']
    );
    // In a real app, this would go to a file or syslog
    error_log($log_entry);
}

function handle_cors(&$context) {
    header('Access-Control-Allow-Origin: *');
    if ($context['request']['method'] === 'OPTIONS') {
        header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
        header('Access-Control-Allow-Headers: Content-Type');
        send_response(204, [], '');
        exit; // Terminate script for preflight requests
    }
}

function check_rate_limit(&$context) {
    // Simple in-memory rate limiting for demonstration
    static $requests_by_ip = [];
    $ip = $context['request']['ip'];
    $time = time();
    $limit = 100;
    $window = 60;

    if (!isset($requests_by_ip[$ip])) {
        $requests_by_ip[$ip] = [];
    }

    // Remove old timestamps
    $requests_by_ip[$ip] = array_filter($requests_by_ip[$ip], function($timestamp) use ($time, $window) {
        return ($time - $timestamp) < $window;
    });

    if (count($requests_by_ip[$ip]) >= $limit) {
        send_response(429, ['Content-Type' => 'application/json'], json_encode(['error' => 'Rate limit exceeded']));
        exit;
    }

    $requests_by_ip[$ip][] = $time;
}

// --- Response Helper ---
function send_response($status_code, $headers, $body) {
    http_response_code($status_code);
    foreach ($headers as $name => $value) {
        header("$name: $value");
    }
    echo $body;
}

// --- Decorator/Wrapper for Transformation ---
function json_response_wrapper(callable $handler_func) {
    return function($context) use ($handler_func) {
        $data = $handler_func($context);
        $json_body = json_encode(['data' => $data]);
        send_response(200, ['Content-Type' => 'application/json'], $json_body);
    };
}

// --- Application Logic ---
function get_user_controller(&$context) {
    global $db_users;
    return $db_users['a1b2c3d4-e5f6-7890-1234-567890abcdef'];
}

function get_post_controller(&$context) {
    global $db_posts;
    return $db_posts['f0e9d8c7-b6a5-4321-fedc-ba9876543210'];
}

// --- Main Execution Block ---

// 1. Setup global error handling
handle_errors();

// 2. Create a request context
$request_context = [
    'request' => [
        'method' => $_SERVER['REQUEST_METHOD'],
        'path' => parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH),
        'ip' => $_SERVER['REMOTE_ADDR'] ?? '127.0.0.1',
    ],
    'response' => [
        'status' => 200,
        'headers' => [],
        'body' => null
    ]
];

// 3. Run "Middleware" in sequence
log_request($request_context);
handle_cors($request_context);
check_rate_limit($request_context);

// 4. Simple Routing
$controller_func = null;
if ($request_context['request']['path'] === '/users/me' && $request_context['request']['method'] === 'GET') {
    $controller_func = 'get_user_controller';
} elseif ($request_context['request']['path'] === '/posts/1' && $request_context['request']['method'] === 'GET') {
    $controller_func = 'get_post_controller';
}

// 5. Dispatch to controller and send response
if ($controller_func) {
    // Use the decorator to wrap the controller
    $wrapped_controller = json_response_wrapper($controller_func);
    $wrapped_controller($request_context);
} else {
    send_response(404, ['Content-Type' => 'application/json'], json_encode(['error' => 'Not Found']));
}

?>