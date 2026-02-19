<?php

namespace App\Api\Standard;

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
use Symfony\Component\Serializer\Annotation\Groups;
use Symfony\Component\Serializer\SerializerInterface;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Validator\ValidatorInterface;
use Symfony\Bridge\Doctrine\Attribute\MapEntity;

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
    #[Groups(['user:read'])]
    private UuidInterface $id;

    #[ORM\Column(length: 180, unique: true)]
    #[Groups(['user:read', 'user:write'])]
    #[Assert\NotBlank]
    #[Assert\Email]
    private string $email;

    #[ORM\Column]
    private string $password_hash;

    #[ORM\Column(type: 'string', enumType: UserRole::class)]
    #[Groups(['user:read', 'user:write'])]
    #[Assert\NotNull]
    private UserRole $role;

    #[ORM\Column]
    #[Groups(['user:read', 'user:write'])]
    private bool $is_active = true;

    #[ORM\Column]
    #[Groups(['user:read'])]
    private DateTimeImmutable $created_at;

    public function __construct(string $email, UserRole $role)
    {
        $this->id = Uuid::uuid4();
        $this->email = $email;
        $this->role = $role;
        $this->created_at = new DateTimeImmutable();
    }

    // Getters and Setters
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

// --- Data Transfer Object (DTO) for Input ---

class CreateUserDto
{
    #[Assert\NotBlank]
    #[Assert\Email]
    public string $email;

    #[Assert\NotBlank]
    #[Assert\Length(min: 8)]
    public string $password;

    #[Assert\NotNull]
    public UserRole $role = UserRole::USER;
}

// --- Mock Repository for demonstration ---

class MockUserRepository
{
    private array $users = [];

    public function __construct()
    {
        // Pre-populate with some data
        $user1 = new User('admin@example.com', UserRole::ADMIN);
        $user1->setPasswordHash('hashed_password');
        $this->users[$user1->getId()->toString()] = $user1;

        $user2 = new User('user@example.com', UserRole::USER);
        $user2->setPasswordHash('hashed_password');
        $this->users[$user2->getId()->toString()] = $user2;
    }

    public function find(string $id): ?User
    {
        return $this->users[$id] ?? null;
    }

    public function findByWithPagination(array $criteria, int $page, int $limit): array
    {
        $filtered = array_filter($this->users, function (User $user) use ($criteria) {
            if (isset($criteria['email']) && !str_contains($user->getEmail(), $criteria['email'])) {
                return false;
            }
            if (isset($criteria['role']) && $user->getRole() !== $criteria['role']) {
                return false;
            }
            return true;
        });
        return array_slice(array_values($filtered), ($page - 1) * $limit, $limit);
    }

    public function save(User $user): void
    {
        $this->users[$user->getId()->toString()] = $user;
    }

    public function remove(User $user): void
    {
        unset($this->users[$user->getId()->toString()]);
    }
}

// --- Controller ---

#[Route('/api/users')]
class UserController extends AbstractController
{
    public function __construct(
        private readonly MockUserRepository $userRepository,
        private readonly SerializerInterface $serializer,
        private readonly ValidatorInterface $validator,
        private readonly UserPasswordHasherInterface $passwordHasher
    ) {}

    #[Route('', name: 'user_list', methods: ['GET'])]
    public function list(Request $request): JsonResponse
    {
        $page = $request->query->getInt('page', 1);
        $limit = $request->query->getInt('limit', 10);
        $emailSearch = $request->query->get('email');
        $roleFilter = $request->query->get('role');

        $criteria = [];
        if ($emailSearch) {
            $criteria['email'] = $emailSearch;
        }
        if ($roleFilter && $role = UserRole::tryFrom(strtoupper($roleFilter))) {
            $criteria['role'] = $role;
        }

        $users = $this->userRepository->findByWithPagination($criteria, $page, $limit);

        return $this->json($users, Response::HTTP_OK, [], ['groups' => 'user:read']);
    }

    #[Route('', name: 'user_create', methods: ['POST'])]
    public function create(Request $request): JsonResponse
    {
        /** @var CreateUserDto $dto */
        $dto = $this->serializer->deserialize($request->getContent(), CreateUserDto::class, 'json');

        $errors = $this->validator->validate($dto);
        if (count($errors) > 0) {
            return $this->json($errors, Response::HTTP_BAD_REQUEST);
        }

        $user = new User($dto->email, $dto->role);
        $hashedPassword = $this->passwordHasher->hashPassword($user, $dto->password);
        $user->setPasswordHash($hashedPassword);

        $this->userRepository->save($user);

        return $this->json($user, Response::HTTP_CREATED, [], ['groups' => 'user:read']);
    }

    #[Route('/{id}', name: 'user_get', methods: ['GET'])]
    public function getById(#[MapEntity] User $user): JsonResponse
    {
        return $this->json($user, Response::HTTP_OK, [], ['groups' => 'user:read']);
    }

    #[Route('/{id}', name: 'user_update', methods: ['PUT', 'PATCH'])]
    public function update(Request $request, #[MapEntity] User $user): JsonResponse
    {
        // For PUT, we expect all fields. For PATCH, only a subset.
        // The serializer handles this by populating the existing object.
        $this->serializer->deserialize(
            $request->getContent(),
            User::class,
            'json',
            ['object_to_populate' => $user, 'groups' => 'user:write']
        );

        $errors = $this->validator->validate($user);
        if (count($errors) > 0) {
            return $this->json($errors, Response::HTTP_BAD_REQUEST);
        }

        $this->userRepository->save($user);

        return $this->json($user, Response::HTTP_OK, [], ['groups' => 'user:read']);
    }

    #[Route('/{id}', name: 'user_delete', methods: ['DELETE'])]
    public function delete(#[MapEntity] User $user): Response
    {
        $this->userRepository->remove($user);
        return new Response(null, Response::HTTP_NO_CONTENT);
    }
}