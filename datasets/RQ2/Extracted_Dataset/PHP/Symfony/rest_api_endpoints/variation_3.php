<?php

namespace App\Api\ServiceDriven;

use DateTimeImmutable;
use Doctrine\ORM\Mapping as ORM;
use Ramsey\Uuid\Uuid;
use Ramsey\Uuid\UuidInterface;
use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpKernel\Attribute\MapRequestPayload;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;
use Symfony\Component\PasswordHasher\Hasher\UserPasswordHasherInterface;
use Symfony\Component\Routing\Annotation\Route;
use Symfony\Component\Serializer\SerializerInterface;
use Symfony\Component\Validator\Constraints as Assert;

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

    public function __construct(string $email, UserRole $role)
    {
        $this->id = Uuid::uuid4();
        $this->email = $email;
        $this->role = $role;
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

// --- DTOs for Service Layer ---

class UserPayloadDto
{
    public function __construct(
        #[Assert\NotBlank]
        #[Assert\Email]
        public readonly string $email,
        #[Assert\NotBlank]
        #[Assert\Length(min: 8)]
        public readonly ?string $password = null, // Nullable for updates
        public readonly ?UserRole $role = UserRole::USER,
        public readonly ?bool $is_active = true,
    ) {}
}

// --- Mock Repository ---

class MockUserRepository
{
    private array $users = [];
    public function __construct() {
        $user1 = new User('admin@example.com', UserRole::ADMIN);
        $user1->setPasswordHash('hashed_password');
        $this->users[$user1->getId()->toString()] = $user1;
        $user2 = new User('user@example.com', UserRole::USER);
        $user2->setPasswordHash('hashed_password');
        $this->users[$user2->getId()->toString()] = $user2;
    }
    public function find(string $id): ?User { return $this->users[$id] ?? null; }
    public function findAll(): array { return array_values($this->users); }
    public function save(User $user): void { $this->users[$user->getId()->toString()] = $user; }
    public function remove(User $user): void { unset($this->users[$user->getId()->toString()]); }
}

// --- Service Layer ---

class UserService
{
    public function __construct(
        private readonly MockUserRepository $userRepository,
        private readonly UserPasswordHasherInterface $passwordHasher
    ) {}

    public function findUsers(array $filters): array
    {
        // In a real app, this logic would be in the repository
        return $this->userRepository->findAll();
    }

    public function findUserOrFail(string $id): User
    {
        $user = $this->userRepository->find($id);
        if (null === $user) {
            throw new NotFoundHttpException('User not found');
        }
        return $user;
    }

    public function createUser(UserPayloadDto $dto): User
    {
        $user = new User($dto->email, $dto->role ?? UserRole::USER);
        $hashedPassword = $this->passwordHasher->hashPassword($user, $dto->password);
        $user->setPasswordHash($hashedPassword);
        $user->setIsActive($dto->is_active ?? true);
        
        $this->userRepository->save($user);
        return $user;
    }

    public function updateUser(string $id, UserPayloadDto $dto): User
    {
        $user = $this->findUserOrFail($id);
        
        $user->setEmail($dto->email);
        if ($dto->role) {
            $user->setRole($dto->role);
        }
        if (null !== $dto->is_active) {
            $user->setIsActive($dto->is_active);
        }
        // Note: Password update would need a separate DTO or logic
        
        $this->userRepository->save($user);
        return $user;
    }

    public function deleteUser(string $id): void
    {
        $user = $this->findUserOrFail($id);
        $this->userRepository->remove($user);
    }
}

// --- Controller (Thin Layer) ---

#[Route('/api/v3/users')]
class UserController extends AbstractController
{
    public function __construct(
        private readonly UserService $userService,
        private readonly SerializerInterface $serializer
    ) {}

    #[Route('', name: 'v3_user_list', methods: ['GET'])]
    public function list(Request $request): JsonResponse
    {
        $filters = $request->query->all();
        $users = $this->userService->findUsers($filters);
        return JsonResponse::fromJsonString($this->serializer->serialize($users, 'json'));
    }

    #[Route('', name: 'v3_user_create', methods: ['POST'])]
    public function create(#[MapRequestPayload] UserPayloadDto $dto): JsonResponse
    {
        $user = $this->userService->createUser($dto);
        $json = $this->serializer->serialize($user, 'json');
        return JsonResponse::fromJsonString($json, Response::HTTP_CREATED);
    }

    #[Route('/{id}', name: 'v3_user_get', methods: ['GET'])]
    public function get(string $id): JsonResponse
    {
        $user = $this->userService->findUserOrFail($id);
        return JsonResponse::fromJsonString($this->serializer->serialize($user, 'json'));
    }

    #[Route('/{id}', name: 'v3_user_update', methods: ['PUT', 'PATCH'])]
    public function update(string $id, #[MapRequestPayload] UserPayloadDto $dto): JsonResponse
    {
        $user = $this->userService->updateUser($id, $dto);
        return JsonResponse::fromJsonString($this->serializer->serialize($user, 'json'));
    }

    #[Route('/{id}', name: 'v3_user_delete', methods: ['DELETE'])]
    public function delete(string $id): Response
    {
        $this->userService->deleteUser($id);
        return new Response(null, Response::HTTP_NO_CONTENT);
    }
}