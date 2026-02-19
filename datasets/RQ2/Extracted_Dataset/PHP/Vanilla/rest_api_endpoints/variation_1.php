<?php
// Variation 1: The Procedural Pragmatist
// Style: Single-file, procedural approach with helper functions.
// Naming: snake_case.
// Structure: A large switch statement for routing.

header("Content-Type: application/json");

// --- Mock Database & Schema ---

// Domain Schema:
// User: id (UUID), email (String), password_hash (String), role (Enum: ADMIN, USER), is_active (Boolean), created_at (Timestamp)
// Post: id (UUID), user_id (UUID), title (String), content (Text), status (Enum: DRAFT, PUBLISHED)

// A simple in-memory "database"
global $users_db;
$users_db = [
    'f47ac10b-58cc-4372-a567-0e02b2c3d479' => [
        'id' => 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
        'email' => 'admin@example.com',
        'password_hash' => '$2y$10$F/w.x9.g1a8s7d6f5e4r3t2y1u0i9o8p7',
        'role' => 'ADMIN',
        'is_active' => true,
        'created_at' => '2023-01-01T10:00:00Z'
    ],
    'a1b2c3d4-e5f6-7890-1234-567890abcdef' => [
        'id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
        'email' => 'user1@example.com',
        'password_hash' => '$2y$10$A/b.c2.d3e4f5g6h7i8j9k0l1m2n3o4p5',
        'role' => 'USER',
        'is_active' => true,
        'created_at' => '2023-02-15T12:30:00Z'
    ],
    'b2c3d4e5-f6a7-8901-2345-67890abcdef1' => [
        'id' => 'b2c3d4e5-f6a7-8901-2345-67890abcdef1',
        'email' => 'user2@example.com',
        'password_hash' => '$2y$10$B/c.d3.e4f5g6h7i8j9k0l1m2n3o4p5q6',
        'role' => 'USER',
        'is_active' => false,
        'created_at' => '2023-03-20T18:45:00Z'
    ]
];

// --- Helper Functions ---

function generate_uuid() {
    return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x',
        mt_rand(0, 0xffff), mt_rand(0, 0xffff),
        mt_rand(0, 0xffff),
        mt_rand(0, 0x0fff) | 0x4000,
        mt_rand(0, 0x3fff) | 0x8000,
        mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)
    );
}

function send_response($data, $status_code = 200) {
    http_response_code($status_code);
    echo json_encode($data);
    exit();
}

function get_request_body() {
    return json_decode(file_get_contents('php://input'), true);
}

// --- API Logic ---

$request_method = $_SERVER['REQUEST_METHOD'];
$request_uri = $_SERVER['REQUEST_URI'];
$path = parse_url($request_uri, PHP_URL_PATH);
$path_parts = explode('/', trim($path, '/'));

$resource = $path_parts[0] ?? null;
$resource_id = $path_parts[1] ?? null;

if ($resource !== 'users') {
    send_response(['error' => 'Not Found'], 404);
}

switch ($request_method) {
    case 'GET':
        if ($resource_id) {
            // Get user by ID: GET /users/{id}
            if (!isset($users_db[$resource_id])) {
                send_response(['error' => 'User not found'], 404);
            }
            send_response($users_db[$resource_id]);
        } else {
            // List/Search users: GET /users
            $users = array_values($users_db);

            // Filtering
            if (isset($_GET['role'])) {
                $users = array_filter($users, fn($user) => $user['role'] === strtoupper($_GET['role']));
            }
            if (isset($_GET['is_active'])) {
                $is_active_filter = filter_var($_GET['is_active'], FILTER_VALIDATE_BOOLEAN);
                $users = array_filter($users, fn($user) => $user['is_active'] === $is_active_filter);
            }

            // Pagination
            $page = isset($_GET['page']) ? (int)$_GET['page'] : 1;
            $limit = isset($_GET['limit']) ? (int)$_GET['limit'] : 10;
            $offset = ($page - 1) * $limit;
            $paginated_users = array_slice($users, $offset, $limit);

            send_response([
                'page' => $page,
                'limit' => $limit,
                'total' => count($users),
                'data' => array_values($paginated_users)
            ]);
        }
        break;

    case 'POST':
        // Create user: POST /users
        $data = get_request_body();
        if (empty($data['email']) || empty($data['password'])) {
            send_response(['error' => 'Email and password are required'], 400);
        }

        foreach ($users_db as $user) {
            if ($user['email'] === $data['email']) {
                send_response(['error' => 'Email already exists'], 409);
            }
        }

        $new_user = [
            'id' => generate_uuid(),
            'email' => $data['email'],
            'password_hash' => password_hash($data['password'], PASSWORD_DEFAULT),
            'role' => $data['role'] ?? 'USER',
            'is_active' => $data['is_active'] ?? true,
            'created_at' => date('c')
        ];

        $users_db[$new_user['id']] = $new_user;
        send_response($new_user, 201);
        break;

    case 'PUT':
    case 'PATCH':
        // Update user: PUT/PATCH /users/{id}
        if (!$resource_id || !isset($users_db[$resource_id])) {
            send_response(['error' => 'User not found'], 404);
        }

        $data = get_request_body();
        $user_to_update = &$users_db[$resource_id];

        if (isset($data['email'])) $user_to_update['email'] = $data['email'];
        if (isset($data['password'])) $user_to_update['password_hash'] = password_hash($data['password'], PASSWORD_DEFAULT);
        if (isset($data['role'])) $user_to_update['role'] = $data['role'];
        if (isset($data['is_active'])) $user_to_update['is_active'] = (bool)$data['is_active'];

        send_response($user_to_update);
        break;



    case 'DELETE':
        // Delete user: DELETE /users/{id}
        if (!$resource_id || !isset($users_db[$resource_id])) {
            send_response(['error' => 'User not found'], 404);
        }
        unset($users_db[$resource_id]);
        send_response(null, 204);
        break;

    default:
        send_response(['error' => 'Method Not Allowed'], 405);
        break;
}
?>