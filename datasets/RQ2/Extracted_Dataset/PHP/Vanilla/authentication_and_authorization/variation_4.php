<?php
// Variation 4: Single-File API / Microservice Approach
// Developer "Pragmatic Pete" likes to keep simple APIs in a single, well-structured file for easy deployment.
// This approach is stateless and relies entirely on JWTs in headers.

// --- Configuration & Constants ---
const API_JWT_SECRET = 'pete-pragmatic-secret-for-jwt-!@#$';
const API_ROLE_ADMIN = 'ADMIN';
const API_ROLE_USER = 'USER';

// --- Mock Data ---
$MOCK_USERS_DB = [];

function setup_mock_data() {
    global $MOCK_USERS_DB;
    $admin_id = 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d';
    $user_id = 'f1e2d3c4-b5a6-4f7e-8d9c-0a1b2c3d4e5f';
    $MOCK_USERS_DB = [
        $admin_id => [
            'id' => $admin_id,
            'email' => 'pete.admin@api.local',
            'password_hash' => password_hash('pete_admin_pass', PASSWORD_BCRYPT),
            'role' => API_ROLE_ADMIN,
            'is_active' => true
        ],
        $user_id => [
            'id' => $user_id,
            'email' => 'pete.user@api.local',
            'password_hash' => password_hash('pete_user_pass', PASSWORD_BCRYPT),
            'role' => API_ROLE_USER,
            'is_active' => true
        ]
    ];
}

// --- Helper Functions ---

function send_json_response($data, $http_code = 200) {
    header_remove();
    http_response_code($http_code);
    header('Content-Type: application/json');
    echo json_encode($data);
    exit();
}

function get_user_by_email($email) {
    global $MOCK_USERS_DB;
    foreach ($MOCK_USERS_DB as $user) {
        if ($user['email'] === $email) {
            return $user;
        }
    }
    return null;
}

function base64url_encode($str) {
    return rtrim(strtr(base64_encode($str), '+/', '-_'), '=');
}

function base64url_decode($str) {
    return base64_decode(strtr($str, '-_', '+/'));
}

function generate_jwt($user_id, $role) {
    $header = base64url_encode(json_encode(['alg' => 'HS256', 'typ' => 'JWT']));
    $payload = base64url_encode(json_encode([
        'sub' => $user_id,
        'role' => $role,
        'iat' => time(),
        'exp' => time() + (60 * 60 * 8) // 8 hours
    ]));
    $signature = base64url_encode(hash_hmac('sha256', "$header.$payload", API_JWT_SECRET, true));
    return "$header.$payload.$signature";
}

function get_auth_user_from_request() {
    $auth_header = $_SERVER['HTTP_AUTHORIZATION'] ?? null;
    if (!$auth_header || !preg_match('/Bearer\s(\S+)/', $auth_header, $matches)) {
        return null;
    }
    $token = $matches[1];
    
    $parts = explode('.', $token);
    if (count($parts) !== 3) return null;

    list($header_b64, $payload_b64, $signature_b64) = $parts;
    $expected_signature = base64url_encode(hash_hmac('sha256', "$header_b64.$payload_b64", API_JWT_SECRET, true));

    if (!hash_equals($expected_signature, $signature_b64)) {
        return null;
    }

    $payload = json_decode(base64url_decode($payload_b64), true);
    if (!$payload || $payload['exp'] < time()) {
        return null;
    }
    
    // In a real app, you might re-fetch the user from DB to ensure they are still active
    // For this example, we trust the payload.
    return $payload;
}

function authorize_role($required_role) {
    $user = get_auth_user_from_request();
    if (!$user) {
        send_json_response(['error' => 'Authentication required'], 401);
    }
    if ($user['role'] !== $required_role && $user['role'] !== API_ROLE_ADMIN) { // Admins can do anything
        send_json_response(['error' => 'Forbidden'], 403);
    }
    return $user;
}

// --- Main Request Handler ---
setup_mock_data();

$request_uri = explode('?', $_SERVER['REQUEST_URI'])[0];
$request_method = $_SERVER['REQUEST_METHOD'];
$input_data = json_decode(file_get_contents('php://input'), true) ?? [];

// Simple Router
if ($request_method === 'POST' && $request_uri === '/login') {
    // --- User Login ---
    $email = $input_data['email'] ?? '';
    $password = $input_data['password'] ?? '';
    $user = get_user_by_email($email);

    if ($user && $user['is_active'] && password_verify($password, $user['password_hash'])) {
        $jwt = generate_jwt($user['id'], $user['role']);
        send_json_response(['access_token' => $jwt, 'token_type' => 'Bearer']);
    } else {
        send_json_response(['error' => 'Invalid credentials'], 401);
    }

} elseif ($request_method === 'POST' && $request_uri === '/posts') {
    // --- Create a Post (Admin only) ---
    $admin_user = authorize_role(API_ROLE_ADMIN);
    $title = $input_data['title'] ?? 'Untitled';
    $content = $input_data['content'] ?? '';

    // Mock post creation
    $new_post = [
        'id' => 'post-' . bin2hex(random_bytes(8)),
        'user_id' => $admin_user['sub'],
        'title' => $title,
        'content' => $content,
        'status' => 'PUBLISHED'
    ];
    send_json_response(['message' => 'Post created successfully', 'post' => $new_post], 201);

} elseif ($request_method === 'GET' && $request_uri === '/posts/my-drafts') {
    // --- Get User's Drafts (User or Admin) ---
    $current_user = authorize_role(API_ROLE_USER); // Requires at least USER role
    
    // Mock fetching drafts
    $drafts = [
        ['id' => 'draft-123', 'user_id' => $current_user['sub'], 'title' => 'My first draft', 'status' => 'DRAFT']
    ];
    send_json_response($drafts);

} elseif ($request_method === 'GET' && $request_uri === '/oauth/start') {
    // --- OAuth2 Client Implementation (Start) ---
    $state = bin2hex(random_bytes(16));
    // In a real app, you'd save this state in a temporary store (e.g., Redis, or even a cookie)
    // to verify it on callback. For this stateless example, we'll ignore that.
    $params = http_build_query([
        'client_id' => 'pete-oauth-client',
        'redirect_uri' => 'http://localhost:8000/oauth/callback',
        'response_type' => 'code',
        'scope' => 'user:email',
        'state' => $state
    ]);
    header('Location: https://github.com/login/oauth/authorize?' . $params); // Using GitHub as a real example
    exit();

} elseif ($request_method === 'GET' && $request_uri === '/oauth/callback') {
    // --- OAuth2 Client Implementation (Callback) ---
    $code = $_GET['code'] ?? null;
    if (!$code) {
        send_json_response(['error' => 'OAuth code missing'], 400);
    }
    // Mocked: In a real app, you'd exchange the code for an access token,
    // then use the token to get user info.
    // Then, you'd find or create a user in your DB and issue your own JWT.
    $mock_oauth_user_email = 'oauth.pete@example.com';
    $user = get_user_by_email($mock_oauth_user_email);
    if (!$user) {
        // Auto-register the user
        $user_id = 'oauth-' . bin2hex(random_bytes(8));
        $user = [
            'id' => $user_id,
            'email' => $mock_oauth_user_email,
            'password_hash' => '', // No password for OAuth users
            'role' => API_ROLE_USER,
            'is_active' => true
        ];
        $MOCK_USERS_DB[$user_id] = $user;
    }
    $jwt = generate_jwt($user['id'], $user['role']);
    send_json_response(['message' => 'OAuth login successful', 'access_token' => $jwt]);

} else {
    // --- Not Found ---
    send_json_response(['error' => 'Endpoint not found'], 404);
}
?>