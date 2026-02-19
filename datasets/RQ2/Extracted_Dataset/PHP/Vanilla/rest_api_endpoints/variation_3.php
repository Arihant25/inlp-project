<?php
// Variation 3: The "Service Layer" Architect
// Style: OOP with Controller, Service, and DataAccess layers.
// Naming: camelCase, descriptive class names.
// Structure: Promotes testability and separation of business logic from transport layer.

// --- Domain Model & Enums ---

final class UserRole {
    public const ADMIN = 'ADMIN';
    public const USER = 'USER';
    private function __construct() {}
}

final class PostStatus {
    public const DRAFT = 'DRAFT';
    public const PUBLISHED = 'PUBLISHED';
    private function __construct() {}
}

class UserDTO {
    public string $id;
    public string $email;
    public string $role;
    public bool $isActive;
    public string $createdAt;
    // Note: password_hash is intentionally omitted from the DTO for security.
}

// --- Data Access Layer ---

interface UserDataAccessInterface {
    public function find(string $id): ?array;
    public function findAll(array $criteria): array;
    public function findByEmail(string $email): ?array;
    public function persist(array $userData): array;
    public function remove(string $id): bool;
}

class InMemoryUserDataAccess implements UserDataAccessInterface {
    private static array $userStore = [];

    public function __construct() {
        if (empty(self::$userStore)) {
            self::$userStore = [
                'f47ac10b-58cc-4372-a567-0e02b2c3d479' => [
                    'id' => 'f47ac10b-58cc-4372-a567-0e02b2c3d479', 'email' => 'admin@example.com',
                    'password_hash' => '$2y$10$F/w.x9.g1a8s7d6f5e4r3t2y1u0i9o8p7', 'role' => UserRole::ADMIN,
                    'is_active' => true, 'created_at' => '2023-01-01T10:00:00Z'
                ],
                'a1b2c3d4-e5f6-7890-1234-567890abcdef' => [
                    'id' => 'a1b2c3d4-e5f6-7890-1234-567890abcdef', 'email' => 'user1@example.com',
                    'password_hash' => '$2y$10$A/b.c2.d3e4f5g6h7i8j9k0l1m2n3o4p5', 'role' => UserRole::USER,
                    'is_active' => true, 'created_at' => '2023-02-15T12:30:00Z'
                ],
            ];
        }
    }

    public function find(string $id): ?array {
        return self::$userStore[$id] ?? null;
    }

    public function findAll(array $criteria): array {
        return array_values(self::$userStore);
    }

    public function findByEmail(string $email): ?array {
        foreach (self::$userStore as $user) {
            if ($user['email'] === $email) return $user;
        }
        return null;
    }

    public function persist(array $userData): array {
        self::$userStore[$userData['id']] = $userData;
        return $userData;
    }

    public function remove(string $id): bool {
        if (isset(self::$userStore[$id])) {
            unset(self::$userStore[$id]);
            return true;
        }
        return false;
    }
}

// --- Service Layer ---

class UserService {
    private UserDataAccessInterface $userDataAccess;

    public function __construct(UserDataAccessInterface $userDataAccess) {
        $this->userDataAccess = $userDataAccess;
    }

    public function getUserById(string $id): ?UserDTO {
        $user = $this->userDataAccess->find($id);
        return $user ? $this->mapToDTO($user) : null;
    }

    public function getAllUsers(array $queryParams): array {
        $users = $this->userDataAccess->findAll([]);
        
        // Filtering
        if (!empty($queryParams['role'])) {
            $users = array_filter($users, fn($u) => $u['role'] === strtoupper($queryParams['role']));
        }
        if (isset($queryParams['is_active'])) {
            $isActive = filter_var($queryParams['is_active'], FILTER_VALIDATE_BOOLEAN);
            $users = array_filter($users, fn($u) => $u['is_active'] === $isActive);
        }

        // Pagination
        $page = (int)($queryParams['page'] ?? 1);
        $limit = (int)($queryParams['limit'] ?? 10);
        $total = count($users);
        $offset = ($page - 1) * $limit;
        $paginatedData = array_slice(array_values($users), $offset, $limit);

        return [
            'total' => $total,
            'page' => $page,
            'limit' => $limit,
            'data' => array_map([$this, 'mapToDTO'], $paginatedData)
        ];
    }

    public function createUser(array $data): UserDTO {
        if (!filter_var($data['email'], FILTER_VALIDATE_EMAIL)) {
            throw new InvalidArgumentException("Invalid email format.", 400);
        }
        if ($this->userDataAccess->findByEmail($data['email'])) {
            throw new InvalidArgumentException("Email already exists.", 409);
        }
        if (empty($data['password']) || strlen($data['password']) < 8) {
            throw new InvalidArgumentException("Password must be at least 8 characters.", 400);
        }

        $newUser = [
            'id' => $this->generateUuid(),
            'email' => $data['email'],
            'password_hash' => password_hash($data['password'], PASSWORD_DEFAULT),
            'role' => $data['role'] ?? UserRole::USER,
            'is_active' => $data['is_active'] ?? true,
            'created_at' => date('c')
        ];

        $persistedUser = $this->userDataAccess->persist($newUser);
        return $this->mapToDTO($persistedUser);
    }

    public function updateUser(string $id, array $data): ?UserDTO {
        $user = $this->userDataAccess->find($id);
        if (!$user) return null;

        if (isset($data['email'])) $user['email'] = $data['email'];
        if (isset($data['password'])) $user['password_hash'] = password_hash($data['password'], PASSWORD_DEFAULT);
        if (isset($data['role'])) $user['role'] = $data['role'];
        if (isset($data['is_active'])) $user['is_active'] = (bool)$data['is_active'];

        $updatedUser = $this->userDataAccess->persist($user);
        return $this->mapToDTO($updatedUser);
    }

    public function deleteUser(string $id): bool {
        return $this->userDataAccess->remove($id);
    }

    private function mapToDTO(array $user): UserDTO {
        $dto = new UserDTO();
        $dto->id = $user['id'];
        $dto->email = $user['email'];
        $dto->role = $user['role'];
        $dto->isActive = $user['is_active'];
        $dto->createdAt = $user['created_at'];
        return $dto;
    }

    private function generateUuid(): string {
        return bin2hex(random_bytes(16)); // A simpler UUID-like string
    }
}

// --- API Handler (Controller) Layer ---

class UserApiHandler {
    private UserService $userService;

    public function __construct(UserService $userService) {
        $this->userService = $userService;
    }

    public function handleRequest(string $method, string $path, array $queryParams): void {
        try {
            if (preg_match('#^/users/?$#', $path)) {
                if ($method === 'GET') $this->list($queryParams);
                elseif ($method === 'POST') $this->create();
            } elseif (preg_match('#^/users/([a-f0-9\-]+)/?$#', $path, $matches)) {
                $id = $matches[1];
                if ($method === 'GET') $this->get($id);
                elseif ($method === 'PUT' || $method === 'PATCH') $this->update($id);
                elseif ($method === 'DELETE') $this->delete($id);
            } else {
                $this->sendResponse(['error' => 'Endpoint not found'], 404);
            }
        } catch (InvalidArgumentException $e) {
            $this->sendResponse(['error' => $e->getMessage()], $e->getCode() ?: 400);
        } catch (Exception $e) {
            $this->sendResponse(['error' => 'An internal error occurred'], 500);
        }
    }

    private function list(array $queryParams): void {
        $result = $this->userService->getAllUsers($queryParams);
        $this->sendResponse($result);
    }

    private function get(string $id): void {
        $user = $this->userService->getUserById($id);
        if ($user) {
            $this->sendResponse($user);
        } else {
            $this->sendResponse(['error' => 'User not found'], 404);
        }
    }

    private function create(): void {
        $data = json_decode(file_get_contents('php://input'), true);
        $userDto = $this->userService->createUser($data);
        $this->sendResponse($userDto, 201);
    }

    private function update(string $id): void {
        $data = json_decode(file_get_contents('php://input'), true);
        $userDto = $this->userService->updateUser($id, $data);
        if ($userDto) {
            $this->sendResponse($userDto);
        } else {
            $this->sendResponse(['error' => 'User not found'], 404);
        }
    }

    private function delete(string $id): void {
        if ($this->userService->deleteUser($id)) {
            $this->sendResponse(null, 204);
        } else {
            $this->sendResponse(['error' => 'User not found'], 404);
        }
    }

    private function sendResponse($payload, int $statusCode = 200): void {
        header("Content-Type: application/json");
        http_response_code($statusCode);
        if ($payload !== null) {
            echo json_encode($payload);
        }
    }
}

// --- Application Entry Point ---
$dataAccess = new InMemoryUserDataAccess();
$userService = new UserService($dataAccess);
$apiHandler = new UserApiHandler($userService);

$requestMethod = $_SERVER['REQUEST_METHOD'];
$requestPath = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$queryParams = $_GET;

$apiHandler->handleRequest($requestMethod, $requestPath, $queryParams);
?>