<?php
// Variation 1: Classic Object-Oriented Programming Approach
// Developer "SOLID Stan" prefers clear separation of concerns with dedicated classes for each responsibility.

// --- Configuration and Constants ---
define('JWT_SECRET_KEY', 'your-super-secret-key-for-hs256');
define('OAUTH2_CLIENT_ID', 'mock-client-id');
define('OAUTH2_CLIENT_SECRET', 'mock-client-secret');
define('OAUTH2_REDIRECT_URI', 'http://localhost:8000?action=oauth_callback');
define('OAUTH2_PROVIDER_AUTH_URL', 'https://example.com/oauth/authorize');
define('OAUTH2_PROVIDER_TOKEN_URL', 'https://example.com/oauth/token');

// --- Utility Functions ---
function generate_uuid_v4() {
    $data = random_bytes(16);
    $data[6] = chr(ord($data[6]) & 0x0f | 0x40); // set version to 0100
    $data[8] = chr(ord($data[8]) & 0x3f | 0x80); // set bits 6-7 to 10
    return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($data), 4));
}

// --- Domain Entities ---
class User {
    public string $id;
    public string $email;
    public string $password_hash;
    public string $role; // 'ADMIN' or 'USER'
    public bool $is_active;
    public int $created_at;

    public function __construct(string $id, string $email, string $password_hash, string $role, bool $is_active, int $created_at) {
        $this->id = $id;
        $this->email = $email;
        $this->password_hash = $password_hash;
        $this->role = $role;
        $this->is_active = $is_active;
        $this->created_at = $created_at;
    }
}

class Post {
    public string $id;
    public string $user_id;
    public string $title;
    public string $content;
    public string $status; // 'DRAFT' or 'PUBLISHED'
}

// --- Mock Database ---
class MockDatabase {
    private static array $users = [];
    private static array $posts = [];

    public static function initialize() {
        $adminId = generate_uuid_v4();
        self::$users[$adminId] = new User($adminId, 'admin@example.com', password_hash('admin123', PASSWORD_DEFAULT), 'ADMIN', true, time());
        
        $userId = generate_uuid_v4();
        self::$users[$userId] = new User($userId, 'user@example.com', password_hash('user123', PASSWORD_DEFAULT), 'USER', true, time());
    }

    public static function findUserByEmail(string $email): ?User {
        foreach (self::$users as $user) {
            if ($user->email === $email) {
                return $user;
            }
        }
        return null;
    }
    
    public static function findUserById(string $id): ?User {
        return self::$users[$id] ?? null;
    }
}

// --- Services and Handlers ---
class SessionManager {
    public function __construct() {
        if (session_status() === PHP_SESSION_NONE) {
            session_start();
        }
    }

    public function set(string $key, $value): void {
        $_SESSION[$key] = $value;
    }

    public function get(string $key) {
        return $_SESSION[$key] ?? null;
    }

    public function destroy(): void {
        session_destroy();
    }
}

class JwtHandler {
    private string $secret;

    public function __construct(string $secret) {
        $this->secret = $secret;
    }

    private function base64UrlEncode(string $data): string {
        return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
    }

    private function base64UrlDecode(string $data): string {
        return base64_decode(strtr($data, '-_', '+/'));
    }

    public function generateToken(User $user): string {
        $header = json_encode(['typ' => 'JWT', 'alg' => 'HS256']);
        $payload = json_encode([
            'sub' => $user->id,
            'email' => $user->email,
            'role' => $user->role,
            'iat' => time(),
            'exp' => time() + (60 * 60) // 1 hour expiration
        ]);

        $base64UrlHeader = $this->base64UrlEncode($header);
        $base64UrlPayload = $this->base64UrlEncode($payload);

        $signature = hash_hmac('sha256', $base64UrlHeader . "." . $base64UrlPayload, $this->secret, true);
        $base64UrlSignature = $this->base64UrlEncode($signature);

        return $base64UrlHeader . "." . $base64UrlPayload . "." . $base64UrlSignature;
    }

    public function validateToken(string $jwt): ?object {
        $tokenParts = explode('.', $jwt);
        if (count($tokenParts) !== 3) return null;

        $header = $this->base64UrlDecode($tokenParts[0]);
        $payload = $this->base64UrlDecode($tokenParts[1]);
        $signatureProvided = $tokenParts[2];

        $decodedHeader = json_decode($header);
        if ($decodedHeader->alg !== 'HS256') return null;
        
        $decodedPayload = json_decode($payload);
        if ($decodedPayload->exp < time()) return null;

        $base64UrlHeader = $this->base64UrlEncode($header);
        $base64UrlPayload = $this->base64UrlEncode($payload);
        $signature = hash_hmac('sha256', $base64UrlHeader . "." . $base64UrlPayload, $this->secret, true);
        $base64UrlSignature = $this->base64UrlEncode($signature);

        if (hash_equals($base64UrlSignature, $signatureProvided)) {
            return $decodedPayload;
        }

        return null;
    }
}

class AuthService {
    private SessionManager $sessionManager;

    public function __construct(SessionManager $sessionManager) {
        $this->sessionManager = $sessionManager;
    }

    public function login(string $email, string $password): ?User {
        $user = MockDatabase::findUserByEmail($email);
        if ($user && $user->is_active && password_verify($password, $user->password_hash)) {
            $this->sessionManager->set('user_id', $user->id);
            return $user;
        }
        return null;
    }

    public function getAuthenticatedUser(): ?User {
        $userId = $this->sessionManager->get('user_id');
        if ($userId) {
            return MockDatabase::findUserById($userId);
        }
        return null;
    }
}

class RbacMiddleware {
    public static function checkRole(User $user, array $requiredRoles): bool {
        return in_array($user->role, $requiredRoles);
    }
}

class OAuth2Client {
    public function getAuthorizationUrl(): string {
        $params = [
            'response_type' => 'code',
            'client_id' => OAUTH2_CLIENT_ID,
            'redirect_uri' => OAUTH2_REDIRECT_URI,
            'scope' => 'read:user',
            'state' => bin2hex(random_bytes(16))
        ];
        // In a real app, you'd store the 'state' in the session to prevent CSRF
        return OAUTH2_PROVIDER_AUTH_URL . '?' . http_build_query($params);
    }

    public function handleCallback(string $code): ?array {
        // This is a mock. In a real scenario, you'd use cURL to make a POST request
        // to OAUTH2_PROVIDER_TOKEN_URL with the code, client_id, and client_secret.
        if ($code === 'mock_auth_code') {
            // Mocked response from the token endpoint
            return [
                'access_token' => 'mock_access_token',
                'token_type' => 'Bearer',
                'user_info' => [
                    'email' => 'oauth_user@example.com',
                    'id' => generate_uuid_v4()
                ]
            ];
        }
        return null;
    }
}

// --- Main Application Logic ---
MockDatabase::initialize();
$sessionManager = new SessionManager();
$authService = new AuthService($sessionManager);
$jwtHandler = new JwtHandler(JWT_SECRET_KEY);
$oauthClient = new OAuth2Client();

header('Content-Type: application/json');

$action = $_GET['action'] ?? 'default';

switch ($action) {
    case 'login':
        $email = $_POST['email'] ?? '';
        $password = $_POST['password'] ?? '';
        $user = $authService->login($email, $password);
        if ($user) {
            $jwt = $jwtHandler->generateToken($user);
            echo json_encode(['status' => 'success', 'message' => 'Login successful.', 'session_user_id' => $user->id, 'jwt_token' => $jwt]);
        } else {
            http_response_code(401);
            echo json_encode(['status' => 'error', 'message' => 'Invalid credentials.']);
        }
        break;

    case 'admin_action':
        $currentUser = $authService->getAuthenticatedUser();
        if (!$currentUser) {
            http_response_code(401);
            echo json_encode(['status' => 'error', 'message' => 'Not authenticated via session.']);
            break;
        }
        if (RbacMiddleware::checkRole($currentUser, ['ADMIN'])) {
            echo json_encode(['status' => 'success', 'message' => 'Welcome Admin! You have access.']);
        } else {
            http_response_code(403);
            echo json_encode(['status' => 'error', 'message' => 'Access denied. Admin role required.']);
        }
        break;

    case 'validate_jwt':
        $authHeader = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
        $token = str_replace('Bearer ', '', $authHeader);
        $payload = $jwtHandler->validateToken($token);
        if ($payload) {
            echo json_encode(['status' => 'success', 'message' => 'JWT is valid.', 'payload' => $payload]);
        } else {
            http_response_code(401);
            echo json_encode(['status' => 'error', 'message' => 'Invalid or expired JWT.']);
        }
        break;
        
    case 'oauth_login':
        header('Location: ' . $oauthClient->getAuthorizationUrl());
        exit;

    case 'oauth_callback':
        $code = $_GET['code'] ?? '';
        $tokenData = $oauthClient->handleCallback($code);
        if ($tokenData) {
            // Here you would typically find or create a user in your DB based on $tokenData['user_info']
            // and then log them in using the AuthService.
            echo json_encode(['status' => 'success', 'message' => 'OAuth callback successful.', 'data' => $tokenData]);
        } else {
            http_response_code(400);
            echo json_encode(['status' => 'error', 'message' => 'Invalid OAuth code.']);
        }
        break;

    default:
        echo json_encode(['message' => 'Welcome to the Auth API. Use actions like login, admin_action, etc.']);
        break;
}
?>