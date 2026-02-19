<?php

// Variation 4: The "Service-Heavy" Developer with DTOs
// Style: Maximum decoupling using Data Transfer Objects (DTOs) to pass data
// from the controller to a dedicated service layer (Manager). This makes the
// code highly testable and independent of the HTTP layer.

// --- 1. MIGRATION ---
// migrations/Version20231115100004.php
namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20231115100004 extends AbstractMigration
{
    public function getDescription(): string { return 'Create user, post, and role tables with relationships'; }
    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE post (id UUID NOT NULL, author_id UUID NOT NULL, title VARCHAR(255) NOT NULL, content TEXT NOT NULL, status VARCHAR(255) NOT NULL, PRIMARY KEY(id))');
        $this->addSql('CREATE INDEX IDX_5A8A6C8DF675F31B ON post (author_id)');
        $this->addSql('CREATE TABLE "role" (id INT NOT NULL, name VARCHAR(50) NOT NULL, PRIMARY KEY(id))');
        $this->addSql('CREATE UNIQUE INDEX UNIQ_57698A6A5E237E06 ON "role" (name)');
        $this->addSql('CREATE TABLE "user" (id UUID NOT NULL, email VARCHAR(180) NOT NULL, password_hash VARCHAR(255) NOT NULL, is_active BOOLEAN NOT NULL, created_at TIMESTAMP(0) WITHOUT TIME ZONE NOT NULL, PRIMARY KEY(id))');
        $this->addSql('CREATE UNIQUE INDEX UNIQ_8D93D649E7927C74 ON "user" (email)');
        $this->addSql('CREATE TABLE user_role (user_id UUID NOT NULL, role_id INT NOT NULL, PRIMARY KEY(user_id, role_id))');
        $this->addSql('CREATE INDEX IDX_2DE8C6A3A76ED395 ON user_role (user_id)');
        $this->addSql('CREATE INDEX IDX_2DE8C6A3D60322AC ON user_role (role_id)');
        $this->addSql('ALTER TABLE post ADD CONSTRAINT FK_5A8A6C8DF675F31B FOREIGN KEY (author_id) REFERENCES "user" (id) NOT DEFERRABLE INITIALLY IMMEDIATE');
        $this->addSql('ALTER TABLE user_role ADD CONSTRAINT FK_2DE8C6A3A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE INITIALLY IMMEDIATE');
        $this->addSql('ALTER TABLE user_role ADD CONSTRAINT FK_2DE8C6A3D60322AC FOREIGN KEY (role_id) REFERENCES "role" (id) ON DELETE CASCADE NOT DEFERRABLE INITIALLY IMMEDIATE');
    }
    public function down(Schema $schema): void {}
}

// --- 2. ENTITIES (Identical to Variation 1, omitted for brevity, but required for compilation) ---
// src/Entity/Enum/PostStatus.php, src/Entity/User.php, src/Entity/Post.php, src/Entity/Role.php
// (Assuming these files exist and use PHP 8 attributes as in Variation 1)

// --- 3. DATA TRANSFER OBJECT (DTO) ---
// src/DTO/CreatePostDTO.php
namespace App\DTO;

use Ramsey\Uuid\UuidInterface;
use Symfony\Component\Validator\Constraints as Assert;

class CreatePostDTO
{
    #[Assert\NotBlank]
    #[Assert\Length(min: 5, max: 255)]
    public string $title;

    #[Assert\NotBlank]
    public string $content;

    #[Assert\NotBlank]
    #[Assert\Uuid]
    public UuidInterface $authorId;

    public function __construct(string $title, string $content, UuidInterface $authorId)
    {
        $this->title = $title;
        $this->content = $content;
        $this->authorId = $authorId;
    }
}

// --- 4. REPOSITORIES ---
// src/Repository/PostRepository.php
namespace App\Repository;

use App\Entity\Post;
use App\Entity\Enum\PostStatus;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;

class PostRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Post::class);
    }

    /**
     * @return Post[]
     */
    public function findWithFilters(array $filters): array
    {
        $qb = $this->createQueryBuilder('p');

        if (isset($filters['status'])) {
            $qb->andWhere('p.status = :status')
               ->setParameter('status', $filters['status']);
        }

        if (isset($filters['authorId'])) {
            $qb->andWhere('p.author = :authorId')
               ->setParameter('authorId', $filters['authorId']);
        }
        
        return $qb->getQuery()->getResult();
    }
}

// src/Repository/UserRepository.php
namespace App\Repository;
use App\Entity\User;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
class UserRepository extends ServiceEntityRepository {
    public function __construct(ManagerRegistry $registry) { parent::__construct($registry, User::class); }
}

// --- 5. SERVICE LAYER (MANAGER) ---
// src/Service/PostManager.php
namespace App\Service;

use App\DTO\CreatePostDTO;
use App\Entity\Post;
use App\Entity\User;
use App\Repository\PostRepository;
use Doctrine\ORM\EntityManagerInterface;
use Symfony\Component\Validator\Validator\ValidatorInterface;

class PostManager
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly PostRepository $postRepository,
        private readonly ValidatorInterface $validator
    ) {}

    // CREATE operation using a DTO
    public function create(CreatePostDTO $dto): Post
    {
        $errors = $this->validator->validate($dto);
        if (count($errors) > 0) {
            throw new \InvalidArgumentException((string) $errors);
        }

        $author = $this->em->getRepository(User::class)->find($dto->authorId);
        if (!$author) {
            throw new \RuntimeException('Author not found.');
        }

        $post = new Post($dto->title, $dto->content, $author);

        $this->em->persist($post);
        $this->em->flush();

        return $post;
    }

    // READ operation with filter abstraction
    public function getPosts(array $filters): array
    {
        return $this->postRepository->findWithFilters($filters);
    }

    // UPDATE operation
    public function updateContent(string $postId, string $newContent): Post
    {
        $post = $this->postRepository->find($postId);
        if (!$post) {
            throw new \RuntimeException('Post not found.');
        }
        // ... update content
        $this->em->flush();
        return $post;
    }
}

// src/Service/UserRegistrationService.php
namespace App\Service;

use App\Entity\User;
use App\Entity\Role;
use Doctrine\ORM\EntityManagerInterface;

class UserRegistrationService
{
    public function __construct(private readonly EntityManagerInterface $em) {}

    // TRANSACTION with explicit ROLLBACK
    public function register(string $email, string $password, bool $shouldFail = false): User
    {
        $user = new User($email, $password);
        $defaultRole = $this->em->getRepository(Role::class)->findOneBy(['name' => 'ROLE_USER']);
        if ($defaultRole) {
            $user->addRole($defaultRole);
        }

        $this->em->persist($user);

        // Simulate a second, dependent operation that might fail
        if ($shouldFail) {
            // This will cause the flush to throw an exception,
            // and the calling code should handle the rollback.
            $user->setEmail(str_repeat('a', 200)); // Assume email has a 180 char limit
        }

        $this->em->flush();
        return $user;
    }
}

// --- 6. CONTROLLER (very thin, just prepares DTOs and calls services) ---
// src/Controller/Api/UserController.php
namespace App\Controller\Api;

use App\Service\UserRegistrationService;
use Doctrine\ORM\EntityManagerInterface;
use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;

class UserController extends AbstractController
{
    public function __construct(
        private readonly UserRegistrationService $registrationService,
        private readonly EntityManagerInterface $em
    ) {}

    public function register(Request $request): JsonResponse
    {
        $data = $request->toArray();
        
        // The transaction is handled here, wrapping the service call.
        $this->em->beginTransaction();
        try {
            $user = $this->registrationService->register(
                $data['email'],
                $data['password'],
                $data['shouldFail'] ?? false // For demonstrating rollback
            );
            $this->em->commit();
            return new JsonResponse(['userId' => $user->getId()], 201);
        } catch (\Exception $e) {
            $this->em->rollback();
            return new JsonResponse(['error' => 'Registration failed due to an internal error.'], 500);
        }
    }
}