<?php

namespace App\Api\Pragmatic;

use DateTimeImmutable;
use Doctrine\ORM\Mapping as ORM;
use Ramsey\Uuid\Uuid;
use Ramsey\Uuid\UuidInterface;
use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\PasswordHasher\Hasher\UserPasswordHasherInterface;
use Symfony\Component\Routing\Annotation\Route;

// --- Domain Model ---

enum UserRole: string
{
    case ADMIN = 'ADMIN';
    case USER = 'USER';
}

#[ORM\Entity(repositoryClass: MockUserRepository::class)]
class User
{
    #[ORM\Id]
    #[ORM\Column(type: 'uuid', unique: true)]
    private UuidInterface $id;
    #[ORM\Column(length: 180, unique: true)]
    private string $email;
    #[ORM\Column]
    private string $password_hash;
    #[ORM\Column(type: 'string', enumType: UserRole::class)]
    private UserRole $role;
    #[ORM\Column]
    private bool $is_active = true;
    #[ORM\Column]
    private DateTimeImmutable $created_at;

    public function __construct()
    {
        $this->id = Uuid::uuid4();
        $this->created_at = new DateTimeImmutable();
    }
    
    public function getId(): UuidInterface { return $this->id; }
    public function getEmail(): string { return $this->email; }
    public function setEmail(string $email): self { $this->email = $email; return $this; }
    public function getPasswordHash(): string { return $this->password_hash; }
    public function setPasswordHash(string $hash): self { $this->password_hash = $hash; return $this; }
    public function getRole(): UserRole { return $this->role; }
    public function setRole(UserRole $role): self { $this->role = $role; return $this; }
    public function isActive(): bool { return $this->is_active; }
    public function setIsActive(bool $isActive): self { $this->is_active = $isActive; return $this; }
    public function getCreatedAt(): DateTimeImmutable { return $this->created_at; }
}

// --- Mock Repository ---

class MockUserRepository
{
    private array $users = [];
    public function __construct() {
        $user1 = (new User())->setEmail('admin@example.com')->setRole(UserRole::ADMIN)->setPasswordHash('hashed');
        $this->users[$user1->getId()->toString()] = $user1;
        $user2 = (new User())->setEmail('user@example.com')->setRole(UserRole::USER)->setPasswordHash('hashed');
        $this->users[$user2->getId()->toString()] = $user2;
    }
    public function find(string $id): ?User { return $this->users[$id] ?? null; }
    public function findAll(): array { return $this->users; }
    public function save(User $user): void { $this->users[$user->getId()->toString()] = $user; }
    public function remove(User $user): void { unset($this->users[$user->getId()->toString()]); }
}

// --- Controller with manual/procedural style ---

#[Route('/api/users')]
class ApiUserController extends AbstractController
{
    private MockUserRepository $user_repository;
    private UserPasswordHasherInterface $password_hasher;

    public function __construct(MockUserRepository $user_repository, UserPasswordHasherInterface $password_hasher)
    {
        $this->user_repository = $user_repository;
        $this->password_hasher = $password_hasher;
    }

    #[Route('', name: 'api_user_collection_get', methods: ['GET'])]
    public function get_collection(Request $request): JsonResponse
    {
        $all_users = array_values($this->user_repository->findAll());
        $filtered_users = [];

        // Manual filtering
        $search_term = $request->query->get('search');
        $role_filter = $request->query->get('role');

        foreach ($all_users as $user) {
            $matches = true;
            if ($search_term && !str_contains($user->getEmail(), $search_term)) {
                $matches = false;
            }
            if ($role_filter && $user->getRole()->value !== strtoupper($role_filter)) {
                $matches = false;
            }
            if ($matches) {
                $filtered_users[] = $user;
            }
        }

        // Manual pagination
        $page = $request->query->getInt('page', 1);
        $limit = $request->query->getInt('limit', 10);
        $offset = ($page - 1) * $limit;
        $paginated_users = array_slice($filtered_users, $offset, $limit);

        // Manual serialization
        $data = array_map(function (User $user) {
            return [
                'id' => $user->getId()->toString(),
                'email' => $user->getEmail(),
                'role' => $user->getRole()->value,
                'is_active' => $user->isActive(),
                'created_at' => $user->getCreatedAt()->format('c'),
            ];
        }, $paginated_users);

        return new JsonResponse($data, Response::HTTP_OK);
    }

    #[Route('', name: 'api_user_post', methods: ['POST'])]
    public function post_item(Request $request): JsonResponse
    {
        $payload = $request->toArray();

        // WARNING: Direct mapping from request is risky (mass assignment)
        // Proper validation is crucial here.
        if (!isset($payload['email']) || !isset($payload['password'])) {
            return new JsonResponse(['error' => 'Missing required fields: email, password'], Response::HTTP_BAD_REQUEST);
        }

        $user = new User();
        $user->setEmail($payload['email']);
        $hashed_password = $this->password_hasher->hashPassword($user, $payload['password']);
        $user->setPasswordHash($hashed_password);
        $user->setRole(UserRole::tryFrom($payload['role'] ?? 'USER') ?? UserRole::USER);

        $this->user_repository->save($user);

        return new JsonResponse(['id' => $user->getId()->toString()], Response::HTTP_CREATED);
    }

    #[Route('/{user_id}', name: 'api_user_get', methods: ['GET'])]
    public function get_item(string $user_id): JsonResponse
    {
        $user = $this->user_repository->find($user_id);
        if (!$user) {
            return new JsonResponse(['error' => 'User not found'], Response::HTTP_NOT_FOUND);
        }
        return new JsonResponse([
            'id' => $user->getId()->toString(),
            'email' => $user->getEmail(),
            'role' => $user->getRole()->value,
        ]);
    }

    #[Route('/{user_id}', name: 'api_user_put_patch', methods: ['PUT', 'PATCH'])]
    public function put_patch_item(Request $request, string $user_id): JsonResponse
    {
        $user = $this->user_repository->find($user_id);
        if (!$user) {
            return new JsonResponse(['error' => 'User not found'], Response::HTTP_NOT_FOUND);
        }

        $payload = $request->toArray();
        
        // For PUT, all fields should be present. For PATCH, only some.
        // This code handles both by checking `isset`.
        if (isset($payload['email'])) {
            $user->setEmail($payload['email']);
        }
        if (isset($payload['role'])) {
            $user->setRole(UserRole::from($payload['role']));
        }
        if (isset($payload['is_active'])) {
            $user->setIsActive((bool)$payload['is_active']);
        }

        $this->user_repository->save($user);
        return new JsonResponse(['status' => 'updated']);
    }

    #[Route('/{user_id}', name: 'api_user_delete', methods: ['DELETE'])]
    public function delete_item(string $user_id): Response
    {
        $user = $this->user_repository->find($user_id);
        if ($user) {
            $this->user_repository->remove($user);
        }
        // Always return 204 for DELETE, even if not found (idempotency)
        return new Response(null, Response::HTTP_NO_CONTENT);
    }
}