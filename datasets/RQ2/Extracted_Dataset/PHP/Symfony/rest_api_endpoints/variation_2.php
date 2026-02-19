<?php

namespace App\Api\ActionOriented;

use DateTimeImmutable;
use Doctrine\ORM\Mapping as ORM;
use Ramsey\Uuid\Uuid;
use Ramsey\Uuid\UuidInterface;
use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;
use Symfony\Component\PasswordHasher\Hasher\UserPasswordHasherInterface;
use Symfony\Component\Routing\Annotation\Route;
use Symfony\Component\Serializer\SerializerInterface;
use Symfony\Component\Validator\Validator\ValidatorInterface;
use Symfony\Component\Validator\Constraints as Assert;

// --- Domain Model (shared concept) ---

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
    
    // Public properties for easier serialization in this style
    public function getId(): UuidInterface { return $this->id; }
    public function getEmail(): string { return $this->email; }
    public function setEmail(string $email): void { $this->email = $email; }
    public function setPasswordHash(string $hash): void { $this->password_hash = $hash; }
    public function getRole(): UserRole { return $this->role; }
    public function setRole(UserRole $role): void { $this->role = $role; }
    public function isActive(): bool { return $this->is_active; }
    public function setIsActive(bool $isActive): void { $this->is_active = $isActive; }
    public function getCreatedAt(): DateTimeImmutable { return $this->created_at; }

    public function toArray(): array
    {
        return [
            'id' => $this->id->toString(),
            'email' => $this->email,
            'role' => $this->role->value,
            'is_active' => $this->is_active,
            'created_at' => $this->created_at->format(DATE_ATOM),
        ];
    }
}

// --- Mock Repository (shared concept) ---

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
    public function findBy(array $criteria, int $offset, int $limit): array {
        // Simplified filtering for demonstration
        $results = array_values($this->users);
        return array_slice($results, $offset, $limit);
    }
    public function save(User $user): void { $this->users[$user->getId()->toString()] = $user; }
    public function remove(User $user): void { unset($this->users[$user->getId()->toString()]); }
}

// --- Single Action Controllers ---

#[Route('/api/users', name: 'user_create_action', methods: ['POST'])]
class CreateUserAction extends AbstractController
{
    public function __construct(
        private readonly MockUserRepository $userRepo,
        private readonly UserPasswordHasherInterface $hasher,
        private readonly ValidatorInterface $validator
    ) {}

    public function __invoke(Request $req): JsonResponse
    {
        $data = $req->toArray();
        $violations = $this->validator->validate($data, new Assert\Collection([
            'email' => [new Assert\NotBlank(), new Assert\Email()],
            'password' => [new Assert\NotBlank(), new Assert\Length(['min' => 8])],
            'role' => [new Assert\Optional(new Assert\Choice(choices: ['ADMIN', 'USER']))],
        ]));

        if (count($violations) > 0) {
            return $this->json($violations, Response::HTTP_BAD_REQUEST);
        }

        $role = UserRole::tryFrom($data['role'] ?? 'USER');
        $user = new User($data['email'], $role);
        $user->setPasswordHash($this->hasher->hashPassword($user, $data['password']));
        
        $this->userRepo->save($user);

        return $this->json($user->toArray(), Response::HTTP_CREATED);
    }
}

#[Route('/api/users', name: 'user_list_action', methods: ['GET'])]
class ListUsersAction extends AbstractController
{
    public function __construct(private readonly MockUserRepository $userRepo) {}

    public function __invoke(Request $req): JsonResponse
    {
        $page = $req->query->getInt('page', 1);
        $limit = $req->query->getInt('limit', 10);
        $offset = ($page - 1) * $limit;

        // Filtering logic would be passed to repository
        $users = $this->userRepo->findBy([], $offset, $limit);
        $data = array_map(fn(User $user) => $user->toArray(), $users);

        return $this->json($data);
    }
}

#[Route('/api/users/{id}', name: 'user_get_action', methods: ['GET'])]
class GetUserAction extends AbstractController
{
    public function __construct(private readonly MockUserRepository $userRepo) {}

    public function __invoke(string $id): JsonResponse
    {
        $user = $this->userRepo->find($id);
        if (!$user) {
            throw $this->createNotFoundException('User not found');
        }
        return $this->json($user->toArray());
    }
}

#[Route('/api/users/{id}', name: 'user_update_action', methods: ['PUT'])]
class UpdateUserAction extends AbstractController
{
    public function __construct(
        private readonly MockUserRepository $userRepo,
        private readonly ValidatorInterface $validator
    ) {}

    public function __invoke(Request $req, string $id): JsonResponse
    {
        $user = $this->userRepo->find($id);
        if (!$user) {
            throw $this->createNotFoundException('User not found');
        }

        $data = $req->toArray();
        // Simplified validation for PUT
        if (isset($data['email'])) $user->setEmail($data['email']);
        if (isset($data['role'])) $user->setRole(UserRole::from($data['role']));
        if (isset($data['is_active'])) $user->setIsActive($data['is_active']);

        $this->userRepo->save($user);
        return $this->json($user->toArray());
    }
}

#[Route('/api/users/{id}', name: 'user_delete_action', methods: ['DELETE'])]
class DeleteUserAction extends AbstractController
{
    public function __construct(private readonly MockUserRepository $userRepo) {}

    public function __invoke(string $id): Response
    {
        $user = $this->userRepo->find($id);
        if ($user) {
            $this->userRepo->remove($user);
        }
        return new Response(null, Response::HTTP_NO_CONTENT);
    }
}