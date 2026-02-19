<?php
// Variation 3: Modern Minimalist / Service-Oriented Approach
// Developer "Interface Irene" focuses on contracts (interfaces) and dependency injection for testability and decoupling.

// --- Constants ---
namespace ModernAuth;

const JWT_SIGNING_KEY = 'irenes-secure-and-modern-key';

// --- Interfaces (Contracts) ---
interface UserProvider {
    public function findByEmail(string $email): ?array;
    public function findById(string $id): ?array;
}

interface TokenManager {
    public function createToken(array $user): string;
    public function decodeToken(string $token): ?object;
}

interface PasswordHandler {
    public function hash(string $password): string;
    public function verify(string $password, string $hash): bool;
}

// --- Concrete Implementations ---
class MockUserProvider implements UserProvider {
    private array $users;

    public function __construct() {
        $adminId = $this->generateUuid();
        $userId = $this->generateUuid();
        $this->users = [
            $adminId => ['id' => $adminId, 'email' => 'admin.modern@example.com', 'password_hash' => password_hash('securepass1', PASSWORD_ARGON2ID), 'role' => 'ADMIN', 'is_active' => true],
            $userId => ['id' => $userId, 'email' => 'user.modern@example.com', 'password_hash' => password_hash('securepass2', PASSWORD_ARGON2ID), 'role' => 'USER', 'is_active' => true],
        ];
    }

    public function findByEmail(string $email): ?array {
        foreach ($this->users as $user) {
            if ($user['email'] === $email) return $user;
        }
        return null;
    }

    public function findById(string $id): ?array {
        return $this->users[$id] ?? null;
    }
    
    private function generateUuid(): string {
        return bin2hex(random_bytes(16)); // Simplified for example
    }
}

class StandardPasswordHandler implements PasswordHandler {
    public function hash(string $password): string {
        return password_hash($password, PASSWORD_ARGON2ID);
    }

    public function verify(string $password, string $hash): bool {
        return password_verify($password, $hash);
    }
}

class JwtTokenManager implements TokenManager {
    private string $secret;

    public function __construct(string $secret) {
        $this->secret = $secret;
    }

    public function createToken(array $user): string {
        $header = $this->base64UrlEncode(json_encode(['alg' => 'HS256', 'typ' => 'JWT']));
        $payload = $this->base64UrlEncode(json_encode([
            'sub' => $user['id'],
            'rol' => $user['role'],
            'iat' => time(),
            'exp' => time() + 7200, // 2 hours
        ]));
        $signature = $this->sign("$header.$payload");
        return "$header.$payload.$signature";
    }

    public function decodeToken(string $token): ?object {
        $parts = explode('.', $token);
        if (count($parts) !== 3) return null;

        list($headerB64, $payloadB64, $signatureB64) = $parts;
        
        if (!$this->verifySignature("$headerB64.$payloadB64", $signatureB64)) {
            return null;
        }

        $payload = json_decode($this->base64UrlDecode($payloadB64));
        if (!$payload || !isset($payload->exp) || $payload->exp < time()) {
            return null;
        }
        return $payload;
    }
    
    private function sign(string $data): string {
        return $this->base64UrlEncode(hash_hmac('sha256', $data, $this->secret, true));
    }

    private function verifySignature(string $data, string $signatureB64): bool {
        $expectedSignature = $this->sign($data);
        return hash_equals($expectedSignature, $signatureB64);
    }

    private function base64UrlEncode(string $text): string {
        return str_replace(['+', '/', '='], ['-', '_', ''], base64_encode($text));
    }

    private function base64UrlDecode(string $text): string {
        return base64_decode(str_replace(['-', '_'], ['+', '/'], $text));
    }
}

// --- Core Services ---
class AuthenticationService {
    private UserProvider $userProvider;
    private PasswordHandler $passwordHandler;
    private SessionService $sessionService;

    public function __construct(UserProvider $userProvider, PasswordHandler $passwordHandler, SessionService $sessionService) {
        $this->userProvider = $userProvider;
        $this->passwordHandler = $passwordHandler;
        $this->sessionService = $sessionService;
    }

    public function attempt(string $email, string $password): ?array {
        $user = $this->userProvider->findByEmail($email);
        if ($user && $user['is_active'] && $this->passwordHandler->verify($password, $user['password_hash'])) {
            $this->sessionService->set('auth_user_id', $user['id']);
            return $user;
        }
        return null;
    }
}

class AuthorizationService {
    public static function hasRole(?array $user, string $role): bool {
        return $user && isset($user['role']) && $user['role'] === $role;
    }

    public static function can(string $permission, ?array $user, $resource = null): bool {
        if (!$user) return false;
        // More complex logic could go here, e.g., checking ownership of a post
        switch ($permission) {
            case 'publish_post':
                return self::hasRole($user, 'ADMIN');
            case 'edit_post':
                if (self::hasRole($user, 'ADMIN')) return true;
                // if ($resource && $resource['user_id'] === $user['id']) return true;
                return false;
        }
        return false;
    }
}

class SessionService {
    public function __construct() {
        if (session_status() !== PHP_SESSION_ACTIVE) {
            session_start();
        }
    }
    public function get(string $key, $default = null) { return $_SESSION[$key] ?? $default; }
    public function set(string $key, $value) { $_SESSION[$key] = $value; }
}

class OAuth2Service {
    public function initiateFlow() {
        // Mocked: Redirect to provider
        header('Location: https://oauth.provider.com/auth?client_id=irene-client&redirect_uri=http://localhost/callback');
        exit;
    }
    public function handleCallback(string $code): ?array {
        // Mocked: Exchange code for user info
        if ($code === 'irene_code') {
            return ['email' => 'oauth.irene@example.com', 'name' => 'Irene OAuth'];
        }
        return null;
    }
}

// --- Dependency Injection and Application Bootstrap ---
$userProvider = new MockUserProvider();
$passwordHandler = new StandardPasswordHandler();
$tokenManager = new JwtTokenManager(JWT_SIGNING_KEY);
$sessionService = new SessionService();
$authService = new AuthenticationService($userProvider, $passwordHandler, $sessionService);
$oauthService = new OAuth2Service();

// --- API Endpoint Routing ---
header("Content-Type: application/json");
$path = $_SERVER['REQUEST_URI'] ?? '/';
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method === 'POST' && $path === '/login') {
    $input = json_decode(file_get_contents('php://input'), true);
    $user = $authService->attempt($input['email'] ?? '', $input['password'] ?? '');
    if ($user) {
        echo json_encode(['token' => $tokenManager->createToken($user)]);
    } else {
        http_response_code(401);
        echo json_encode(['error' => 'Unauthorized']);
    }
} elseif ($method === 'POST' && $path === '/posts') {
    $authHeader = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    $token = substr($authHeader, 7); // "Bearer "
    $payload = $tokenManager->decodeToken($token);
    $user = $payload ? $userProvider->findById($payload->sub) : null;

    if (AuthorizationService::can('publish_post', $user)) {
        echo json_encode(['status' => 'success', 'message' => 'Post created by admin.']);
    } else {
        http_response_code(403);
        echo json_encode(['error' => 'Forbidden']);
    }
} elseif ($method === 'GET' && $path === '/oauth/login') {
    $oauthService->initiateFlow();
} elseif ($method === 'GET' && strpos($path, '/oauth/callback') === 0) {
    $code = $_GET['code'] ?? '';
    $userInfo = $oauthService->handleCallback($code);
    if ($userInfo) {
        // Find or create user, then log them in.
        echo json_encode(['status' => 'OAuth successful', 'data' => $userInfo]);
    } else {
        http_response_code(400);
        echo json_encode(['error' => 'Invalid OAuth code']);
    }
} else {
    http_response_code(404);
    echo json_encode(['error' => 'Not Found']);
}
?>