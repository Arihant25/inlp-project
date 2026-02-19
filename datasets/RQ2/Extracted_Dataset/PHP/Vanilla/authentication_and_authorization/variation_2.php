<?php
// Variation 2: Functional/Procedural Approach
// Developer "Function Frank" prefers organizing logic into groups of related functions, avoiding classes.

// --- Configuration ---
const JWT_SECRET = 'a-different-secret-for-frank';
const OAUTH_CLIENT_ID = 'frank-client-id';
const OAUTH_REDIRECT_URI_FUNC = 'http://localhost:8000?action=oauth_callback_func';
const OAUTH_AUTH_URL = 'https://provider.com/auth';

// --- Mock Data Store ---
$db_users = [];

function db_init() {
    global $db_users;
    $admin_id = util_generate_uuid();
    $db_users[$admin_id] = [
        'id' => $admin_id,
        'email' => 'admin.func@example.com',
        'password_hash' => password_hash('adminpass', PASSWORD_DEFAULT),
        'role' => 'ADMIN',
        'is_active' => true,
        'created_at' => time()
    ];
    $user_id = util_generate_uuid();
    $db_users[$user_id] = [
        'id' => $user_id,
        'email' => 'user.func@example.com',
        'password_hash' => password_hash('userpass', PASSWORD_DEFAULT),
        'role' => 'USER',
        'is_active' => true,
        'created_at' => time()
    ];
}

function db_find_user_by_email($email) {
    global $db_users;
    foreach ($db_users as $user) {
        if ($user['email'] === $email) {
            return $user;
        }
    }
    return null;
}

function db_find_user_by_id($id) {
    global $db_users;
    return $db_users[$id] ?? null;
}

// --- Utility Functions ---
function util_generate_uuid() {
    return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x',
        mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff),
        mt_rand(0, 0x0fff) | 0x4000, mt_rand(0, 0x3fff) | 0x8000,
        mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)
    );
}

function util_base64url_encode($data) {
    return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
}

function util_base64url_decode($data) {
    return base64_decode(strtr($data, '-_', '+/'));
}

// --- Session Management Functions ---
function session_secure_start() {
    if (session_status() === PHP_SESSION_NONE) {
        // Basic security settings
        ini_set('session.cookie_httponly', 1);
        ini_set('session.use_only_cookies', 1);
        if (isset($_SERVER['HTTPS']) && $_SERVER['HTTPS'] === 'on') {
            ini_set('session.cookie_secure', 1);
        }
        session_start();
    }
}

function session_set_user_id($user_id) {
    session_regenerate_id(true); // Prevent session fixation
    $_SESSION['user_id'] = $user_id;
}

function session_get_current_user() {
    if (isset($_SESSION['user_id'])) {
        return db_find_user_by_id($_SESSION['user_id']);
    }
    return null;
}

// --- Authentication Functions ---
function auth_attempt_login($email, $password) {
    $user = db_find_user_by_email($email);
    if ($user && $user['is_active'] && password_verify($password, $user['password_hash'])) {
        session_set_user_id($user['id']);
        return $user;
    }
    return false;
}

// --- JWT Functions ---
function jwt_create_token($user_data) {
    $header = json_encode(['typ' => 'JWT', 'alg' => 'HS256']);
    $payload = json_encode([
        'sub' => $user_data['id'],
        'role' => $user_data['role'],
        'exp' => time() + 3600
    ]);

    $encoded_header = util_base64url_encode($header);
    $encoded_payload = util_base64url_encode($payload);
    $signature = hash_hmac('sha256', "$encoded_header.$encoded_payload", JWT_SECRET, true);
    $encoded_signature = util_base64url_encode($signature);

    return "$encoded_header.$encoded_payload.$encoded_signature";
}

function jwt_validate_token($token) {
    list($encoded_header, $encoded_payload, $encoded_signature) = explode('.', $token);
    if (!$encoded_header || !$encoded_payload || !$encoded_signature) {
        return null;
    }

    $expected_signature = util_base64url_encode(hash_hmac('sha256', "$encoded_header.$encoded_payload", JWT_SECRET, true));

    if (!hash_equals($expected_signature, $encoded_signature)) {
        return null;
    }

    $payload = json_decode(util_base64url_decode($encoded_payload), true);
    if ($payload['exp'] < time()) {
        return null;
    }

    return $payload;
}

// --- RBAC (Role-Based Access Control) Functions ---
function rbac_require_role($allowed_roles) {
    $current_user = session_get_current_user();
    if (!$current_user || !in_array($current_user['role'], (array)$allowed_roles)) {
        header('Content-Type: application/json');
        http_response_code(403);
        echo json_encode(['error' => 'Forbidden: Insufficient permissions.']);
        exit();
    }
    return $current_user;
}

// --- OAuth2 Client Functions ---
function oauth_get_auth_redirect_url() {
    $query = http_build_query([
        'client_id' => OAUTH_CLIENT_ID,
        'redirect_uri' => OAUTH_REDIRECT_URI_FUNC,
        'response_type' => 'code',
        'scope' => 'email profile'
    ]);
    return OAUTH_AUTH_URL . '?' . $query;
}

function oauth_process_callback($code) {
    // Mocked: In reality, this would be a server-to-server cURL request.
    if ($code === 'valid_code_for_frank') {
        return ['email' => 'oauth.func@example.com', 'provider_id' => 'provider123'];
    }
    return null;
}

// --- Main Execution ---
db_init();
session_secure_start();

$action = $_REQUEST['action'] ?? 'info';
header('Content-Type: application/json');

switch ($action) {
    case 'login_func':
        $user = auth_attempt_login($_POST['email'] ?? '', $_POST['password'] ?? '');
        if ($user) {
            $jwt = jwt_create_token($user);
            echo json_encode(['status' => 'logged in via session', 'user_id' => $user['id'], 'jwt' => $jwt]);
        } else {
            http_response_code(401);
            echo json_encode(['error' => 'Login failed']);
        }
        break;

    case 'publish_post':
        // This action requires an ADMIN role and a valid session.
        $admin_user = rbac_require_role('ADMIN');
        echo json_encode([
            'message' => "Post published successfully by admin {$admin_user['email']}.",
            'post' => ['id' => util_generate_uuid(), 'title' => 'New Post', 'status' => 'PUBLISHED']
        ]);
        break;

    case 'view_drafts':
        // This action requires a USER or ADMIN role and a valid session.
        $user = rbac_require_role(['USER', 'ADMIN']);
        echo json_encode([
            'message' => "Drafts for user {$user['email']}.",
            'drafts' => [['id' => util_generate_uuid(), 'title' => 'My Draft']]
        ]);
        break;
        
    case 'oauth_login_func':
        header('Location: ' . oauth_get_auth_redirect_url());
        exit;

    case 'oauth_callback_func':
        $user_info = oauth_process_callback($_GET['code'] ?? '');
        if ($user_info) {
            // Logic to find or create user and log them in
            echo json_encode(['status' => 'OAuth login successful', 'user_info' => $user_info]);
        } else {
            http_response_code(400);
            echo json_encode(['error' => 'OAuth callback failed']);
        }
        break;

    case 'info':
    default:
        echo json_encode(['api_version' => '1.0-functional', 'message' => 'Welcome']);
        break;
}
?>