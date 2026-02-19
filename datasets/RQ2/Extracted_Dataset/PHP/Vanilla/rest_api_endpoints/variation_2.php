<?php
// Variation 2: The OOP Enthusiast
// Style: Object-Oriented with clear separation of concerns.
// Naming: camelCase.
// Structure: Router, Controller, and Repository classes.

// --- Domain Schema ---

abstract class Role {
    const ADMIN = 'ADMIN';
    const USER = 'USER';
}

abstract class PostStatus {
    const DRAFT = 'DRAFT';
    const PUBLISHED = 'PUBLISHED';
}

class User {
    public string $id;
    public string $email;
    public string $passwordHash;
    public string $role;
    public bool $isActive;
    public string $createdAt;
}

class Post {
    public string $id;
    public string $userId;
    public string $title;
    public string $content;
    public string $status;
}

// --- Data Layer ---

class UserRepository {
    private static array $users = [];

    public function __construct() {
        if (empty(self::$users)) {
            self::$users = [
                'f47ac10b-58cc-4372-a567-0e02b2c3d479' => [
                    'id' => 'f47ac10b-58cc-4372-a567-0e02b2c3d479', 'email' => 'admin@example.com',
                    'password_hash' => '$2y$10$F/w.x9.g1a8s7d6f5e4r3t2y1u0i9o8p7', 'role' => Role::ADMIN,
                    'is_active' => true, 'created_at' => '2023-01-01T10:00:00Z'
                ],
                'a1b2c3d4-e5f6-7890-1234-567890abcdef' => [
                    'id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef', 'email' => 'user1@example.com',
                    'password_hash' => '$2y$10$A/b.c2.d3e4f5g6h7i8j9k0l1m2n3o4p5', 'role' => Role::USER,
                    'is_active' => true, 'created_at' => '2023-02-15T12:30:00Z'
                ],
            ];
        }
    }

    public function findById(string $id): ?array {
        return self::$users[$id] ?? null;
    }

    public function findByEmail(string $email): ?array {
        foreach (self::$users as $user) {
            if ($user['email'] === $email) {
                return $user;
            }
        }
        return null;
    }

    public function findAll(array $filters = [], int $page = 1, int $limit = 10): array {
        $filteredUsers = array_values(self::$users);

        if (!empty($filters['role'])) {
            $filteredUsers = array_filter($filteredUsers, fn($user) => $user['role'] === strtoupper($filters['role']));
        }
        if (isset($filters['is_active'])) {
            $isActiveFilter = filter_var($filters['is_active'], FILTER_VALIDATE_BOOLEAN);
            $filteredUsers = array_filter($filteredUsers, fn($user) => $user['is_active'] === $isActiveFilter);
        }

        $total = count($filteredUsers);
        $offset = ($page - 1) * $limit;
        $data = array_slice($filteredUsers, $offset, $limit);

        return ['total' => $total, 'data' => array_values($data)];
    }

    public function save(array $user): array {
        self::$users[$user['id']] = $user;
        return $user;
    }

    public function delete(string $id): bool {
        if (isset(self::$users[$id])) {
            unset(self::$users[$id]);
            return true;
        }
        return false;
    }
}

// --- Controller Layer ---

class UserController {
    private UserRepository $userRepository;

    public function __construct() {
        $this->userRepository = new UserRepository();
    }

    public function listUsers(array $queryParams): void {
        $page = isset($queryParams['page']) ? (int)$queryParams['page'] : 1;
        $limit = isset($queryParams['limit']) ? (int)$queryParams['limit'] : 10;
        $filters = [
            'role' => $queryParams['role'] ?? null,
            'is_active' => $queryParams['is_active'] ?? null,
        ];

        $result = $this->userRepository->findAll($filters, $page, $limit);
        $this->sendJsonResponse([
            'page' => $page,
            'limit' => $limit,
            'total' => $result['total'],
            'data' => $result['data']
        ]);
    }

    public function getUser(string $id): void {
        $user = $this->userRepository->findById($id);
        if (!$user) {
            $this->sendJsonResponse(['error' => 'User not found'], 404);
        } else {
            $this->sendJsonResponse($user);
        }
    }

    public function createUser(): void {
        $data = $this->getJsonBody();
        if (empty($data['email']) || empty($data['password'])) {
            $this->sendJsonResponse(['error' => 'Email and password are required'], 400);
            return;
        }
        if ($this->userRepository->findByEmail($data['email'])) {
            $this->sendJsonResponse(['error' => 'Email already in use'], 409);
            return;
        }

        $newUser = [
            'id' => $this->generateUuidV4(),
            'email' => $data['email'],
            'password_hash' => password_hash($data['password'], PASSWORD_DEFAULT),
            'role' => $data['role'] ?? Role::USER,
            'is_active' => $data['is_active'] ?? true,
            'created_at' => date('c')
        ];

        $createdUser = $this->userRepository->save($newUser);
        $this->sendJsonResponse($createdUser, 201);
    }

    public function updateUser(string $id): void {
        $user = $this->userRepository->findById($id);
        if (!$user) {
            $this->sendJsonResponse(['error' => 'User not found'], 404);
            return;
        }

        $data = $this->getJsonBody();
        if (isset($data['email'])) $user['email'] = $data['email'];
        if (isset($data['password'])) $user['password_hash'] = password_hash($data['password'], PASSWORD_DEFAULT);
        if (isset($data['role'])) $user['role'] = $data['role'];
        if (isset($data['is_active'])) $user['is_active'] = (bool)$data['is_active'];

        $updatedUser = $this->userRepository->save($user);
        $this->sendJsonResponse($updatedUser);
    }

    public function deleteUser(string $id): void {
        if (!$this->userRepository->delete($id)) {
            $this->sendJsonResponse(['error' => 'User not found'], 404);
        } else {
            $this->sendJsonResponse(null, 204);
        }
    }

    private function sendJsonResponse($data, int $statusCode = 200): void {
        http_response_code($statusCode);
        if ($data !== null) {
            echo json_encode($data);
        }
    }

    private function getJsonBody(): array {
        return json_decode(file_get_contents('php://input'), true) ?? [];
    }

    private function generateUuidV4(): string {
        return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x',
            mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff),
            mt_rand(0, 0x0fff) | 0x4000, mt_rand(0, 0x3fff) | 0x8000,
            mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)
        );
    }
}

// --- Router ---

class Router {
    public function route(string $method, string $uri): void {
        header("Content-Type: application/json");
        $path = parse_url($uri, PHP_URL_PATH);
        $userController = new UserController();

        // Simple regex-based routing
        if (preg_match('#^/users/?$#', $path)) {
            if ($method === 'GET') {
                $userController->listUsers($_GET);
            } elseif ($method === 'POST') {
                $userController->createUser();
            }
        } elseif (preg_match('#^/users/([a-f0-9\-]+)/?$#', $path, $matches)) {
            $userId = $matches[1];
            if ($method === 'GET') {
                $userController->getUser($userId);
            } elseif ($method === 'PUT' || $method === 'PATCH') {
                $userController->updateUser($userId);
            } elseif ($method === 'DELETE') {
                $userController->deleteUser($userId);
            }
        } else {
            http_response_code(404);
            echo json_encode(['error' => 'Not Found']);
        }
    }
}

// --- Entry Point ---
$router = new Router();
$router->route($_SERVER['REQUEST_METHOD'], $_SERVER['REQUEST_URI']);
?>