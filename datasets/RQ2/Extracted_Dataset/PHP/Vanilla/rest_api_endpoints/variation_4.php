<?php
// Variation 4: The Minimalist Functionalist
// Style: Functional, with a routing table and handler functions.
// Naming: snake_case.
// Structure: Data-driven routing, minimal state mutation.

// --- Initial State (Mock DB) & Schema ---

// Domain Schema:
// User: id (UUID), email (String), password_hash (String), role (Enum: ADMIN, USER), is_active (Boolean), created_at (Timestamp)
// Post: id (UUID), user_id (UUID), title (String), content (Text), status (Enum: DRAFT, PUBLISHED)

function get_initial_state(): array {
    return [
        'users' => [
            'f47ac10b-58cc-4372-a567-0e02b2c3d479' => [
                'id' => 'f47ac10b-58cc-4372-a567-0e02b2c3d479', 'email' => 'admin@example.com',
                'password_hash' => '$2y$10$F/w.x9.g1a8s7d6f5e4r3t2y1u0i9o8p7', 'role' => 'ADMIN',
                'is_active' => true, 'created_at' => '2023-01-01T10:00:00Z'
            ],
            'a1b2c3d4-e5f6-7890-1234-567890abcdef' => [
                'id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef', 'email' => 'user1@example.com',
                'password_hash' => '$2y$10$A/b.c2.d3e4f5g6h7i8j9k0l1m2n3o4p5', 'role' => 'USER',
                'is_active' => true, 'created_at' => '2023-02-15T12:30:00Z'
            ],
            'b2c3d4e5-f6a7-8901-2345-67890abcdef1' => [
                'id' => 'b2c3d4e5-f6a7-8901-2345-67890abcdef1', 'email' => 'user2@example.com',
                'password_hash' => '$2y$10$B/c.d3.e4f5g6h7i8j9k0l1m2n3o4p5q6', 'role' => 'USER',
                'is_active' => false, 'created_at' => '2023-03-20T18:45:00Z'
            ]
        ]
    ];
}

// --- Helper Functions ---

function send_json_response(int $status_code, $body = null): void {
    header("Content-Type: application/json");
    http_response_code($status_code);
    if ($body !== null) {
        echo json_encode($body);
    }
    exit();
}

function parse_json_body(): array {
    return json_decode(file_get_contents('php://input'), true) ?? [];
}

function generate_uuid_v4(): string {
    $data = random_bytes(16);
    $data[6] = chr(ord($data[6]) & 0x0f | 0x40); // set version to 0100
    $data[8] = chr(ord($data[8]) & 0x3f | 0x80); // set bits 6-7 to 10
    return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($data), 4));
}

// --- Request Context ---

function create_request_context(): array {
    return [
        'method' => $_SERVER['REQUEST_METHOD'],
        'path' => parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH),
        'query' => $_GET,
        'body' => parse_json_body()
    ];
}

// --- Handler Functions ---

function handle_list_users(array $db, array $request): void {
    $users = array_values($db['users']);

    // Filter
    $users = array_filter($users, function($user) use ($request) {
        if (isset($request['query']['role']) && strtolower($user['role']) !== strtolower($request['query']['role'])) {
            return false;
        }
        if (isset($request['query']['is_active'])) {
            $is_active = filter_var($request['query']['is_active'], FILTER_VALIDATE_BOOLEAN);
            if ($user['is_active'] !== $is_active) {
                return false;
            }
        }
        return true;
    });

    // Paginate
    $page = (int)($request['query']['page'] ?? 1);
    $limit = (int)($request['query']['limit'] ?? 10);
    $offset = ($page - 1) * $limit;
    
    $response_data = [
        'page' => $page,
        'limit' => $limit,
        'total' => count($users),
        'data' => array_slice(array_values($users), $offset, $limit)
    ];

    send_json_response(200, $response_data);
}

function handle_get_user(array $db, array $request, array $params): void {
    $user_id = $params[0];
    if (!isset($db['users'][$user_id])) {
        send_json_response(404, ['error' => 'User not found']);
    }
    send_json_response(200, $db['users'][$user_id]);
}

function handle_create_user(array &$db, array $request): void {
    $body = $request['body'];
    if (empty($body['email']) || empty($body['password'])) {
        send_json_response(400, ['error' => 'Email and password are required']);
    }

    foreach ($db['users'] as $user) {
        if ($user['email'] === $body['email']) {
            send_json_response(409, ['error' => 'Email already exists']);
        }
    }

    $new_user = [
        'id' => generate_uuid_v4(),
        'email' => $body['email'],
        'password_hash' => password_hash($body['password'], PASSWORD_DEFAULT),
        'role' => $body['role'] ?? 'USER',
        'is_active' => $body['is_active'] ?? true,
        'created_at' => date('c')
    ];

    $db['users'][$new_user['id']] = $new_user; // Mutating state here for simplicity
    send_json_response(201, $new_user);
}

function handle_update_user(array &$db, array $request, array $params): void {
    $user_id = $params[0];
    if (!isset($db['users'][$user_id])) {
        send_json_response(404, ['error' => 'User not found']);
    }

    $body = $request['body'];
    $user = &$db['users'][$user_id];

    if (isset($body['email'])) $user['email'] = $body['email'];
    if (isset($body['password'])) $user['password_hash'] = password_hash($body['password'], PASSWORD_DEFAULT);
    if (isset($body['role'])) $user['role'] = $body['role'];
    if (isset($body['is_active'])) $user['is_active'] = (bool)$body['is_active'];

    send_json_response(200, $user);
}

function handle_delete_user(array &$db, array $request, array $params): void {
    $user_id = $params[0];
    if (!isset($db['users'][$user_id])) {
        send_json_response(404, ['error' => 'User not found']);
    }
    unset($db['users'][$user_id]);
    send_json_response(204);
}

// --- Router ---

function get_routes(): array {
    return [
        ['GET', '#^/users/?$#', 'handle_list_users'],
        ['POST', '#^/users/?$#', 'handle_create_user'],
        ['GET', '#^/users/([a-f0-9\-]+)/?$#', 'handle_get_user'],
        ['PUT', '#^/users/([a-f0-9\-]+)/?$#', 'handle_update_user'],
        ['PATCH', '#^/users/([a-f0-9\-]+)/?$#', 'handle_update_user'],
        ['DELETE', '#^/users/([a-f0-9\-]+)/?$#', 'handle_delete_user'],
    ];
}

function route_request(array &$db, array $request): void {
    $routes = get_routes();
    
    foreach ($routes as $route) {
        [$method, $pattern, $handler] = $route;
        if ($request['method'] === $method && preg_match($pattern, $request['path'], $matches)) {
            array_shift($matches); // Remove the full match
            $handler($db, $request, $matches);
            return; // Exit after first match
        }
    }

    send_json_response(404, ['error' => 'Not Found']);
}

// --- Main Execution ---

$db_state = get_initial_state();
$request_context = create_request_context();
route_request($db_state, $request_context);
?>