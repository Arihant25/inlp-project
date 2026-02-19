<?php

// Variation 2: The "Fat Repository" Developer
// Style: Business logic, especially complex queries, is moved into the Repository classes.
// Services/Controllers are thin. Uses older Doctrine annotations for entity mapping.
// Variable names are sometimes shorter (e.g., `em`).

// --- 1. MIGRATION ---
// migrations/Version20231115100002.php

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20231115100002 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create user, post, and role tables with relationships';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE post (id UUID NOT NULL, author_id UUID NOT NULL, title VARCHAR(255) NOT NULL, content TEXT NOT NULL, status VARCHAR(255) NOT NULL, PRIMARY KEY(id))');
        $this->addSql('CREATE INDEX IDX_5A8A6C8DF675F31B ON post (author_id)');
        $this->addSql('COMMENT ON COLUMN post.id IS \'(DC2Type:uuid)\'');
        $this->addSql('COMMENT ON COLUMN post.author_id IS \'(DC2Type:uuid)\'');
        $this->addSql('CREATE TABLE "role" (id INT NOT NULL, name VARCHAR(50) NOT NULL, PRIMARY KEY(id))');
        $this->addSql('CREATE UNIQUE INDEX UNIQ_57698A6A5E237E06 ON "role" (name)');
        $this->addSql('CREATE TABLE "user" (id UUID NOT NULL, email VARCHAR(180) NOT NULL, password_hash VARCHAR(255) NOT NULL, is_active BOOLEAN NOT NULL, created_at TIMESTAMP(0) WITHOUT TIME ZONE NOT NULL, PRIMARY KEY(id))');
        $this->addSql('CREATE UNIQUE INDEX UNIQ_8D93D649E7927C74 ON "user" (email)');
        $this->addSql('COMMENT ON COLUMN "user".id IS \'(DC2Type:uuid)\'');
        $this->addSql('COMMENT ON COLUMN "user".created_at IS \'(DC2Type:datetime_immutable)\'');
        $this->addSql('CREATE TABLE user_role (user_id UUID NOT NULL, role_id INT NOT NULL, PRIMARY KEY(user_id, role_id))');
        $this->addSql('CREATE INDEX IDX_2DE8C6A3A76ED395 ON user_role (user_id)');
        $this->addSql('CREATE INDEX IDX_2DE8C6A3D60322AC ON user_role (role_id)');
        $this->addSql('COMMENT ON COLUMN user_role.user_id IS \'(DC2Type:uuid)\'');
        $this->addSql('ALTER TABLE post ADD CONSTRAINT FK_5A8A6C8DF675F31B FOREIGN KEY (author_id) REFERENCES "user" (id) NOT DEFERRABLE INITIALLY IMMEDIATE');
        $this->addSql('ALTER TABLE user_role ADD CONSTRAINT FK_2DE8C6A3A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE INITIALLY IMMEDIATE');
        $this->addSql('ALTER TABLE user_role ADD CONSTRAINT FK_2DE8C6A3D60322AC FOREIGN KEY (role_id) REFERENCES "role" (id) ON DELETE CASCADE NOT DEFERRABLE INITIALLY IMMEDIATE');
    }

    public function down(Schema $schema): void {}
}


// --- 2. ENUMS and ENTITIES (using Annotations) ---
// src/Entity/Enum/PostStatus.php
namespace App\Entity\Enum;

enum PostStatus: string
{
    case DRAFT = 'draft';
    case PUBLISHED = 'published';
}

// src/Entity/Role.php
namespace App\Entity;

use Doctrine\ORM\Mapping as ORM;
use Doctrine\Common\Collections\Collection;

/**
 * @ORM\Entity
 * @ORM\Table(name="`role`")
 */
class Role
{
    /**
     * @ORM\Id
     * @ORM\GeneratedValue
     * @ORM\Column(type="integer")
     */
    private ?int $id = null;

    /** @ORM\Column(length=50, unique=true) */
    private string $name;

    /**
     * @ORM\ManyToMany(targetEntity="User", mappedBy="roles")
     */
    private Collection $users;
    // ...getters and setters
}

// src/Entity/User.php
namespace App\Entity;

use App\Repository\UserRepository;
use Doctrine\ORM\Mapping as ORM;
use Doctrine\Common\Collections\Collection;
use Ramsey\Uuid\UuidInterface;

/**
 * @ORM\Entity(repositoryClass=UserRepository::class)
 * @ORM\Table(name="`user`")
 */
class User
{
    /**
     * @ORM\Id
     * @ORM\Column(type="uuid", unique=true)
     * @ORM\GeneratedValue(strategy="CUSTOM")
     * @ORM\CustomIdGenerator(class="Ramsey\Uuid\Doctrine\UuidGenerator")
     */
    private UuidInterface $id;

    /** @ORM\Column(length=180, unique=true) */
    private string $email;

    /** @ORM\Column */
    private string $password_hash;

    /** @ORM\Column(type="boolean") */
    private bool $is_active = true;

    /** @ORM\Column(type="datetime_immutable") */
    private \DateTimeImmutable $created_at;

    /**
     * @ORM\OneToMany(targetEntity="Post", mappedBy="author", cascade={"persist", "remove"})
     */
    private Collection $posts;

    /**
     * @ORM\ManyToMany(targetEntity="Role", inversedBy="users")
     * @ORM\JoinTable(name="user_role")
     */
    private Collection $roles;
    // ...constructor, getters and setters
}

// src/Entity/Post.php
namespace App\Entity;

use App\Entity\Enum\PostStatus;
use App\Repository\PostRepository;
use Doctrine\ORM\Mapping as ORM;
use Ramsey\Uuid\UuidInterface;

/**
 * @ORM\Entity(repositoryClass=PostRepository::class)
 */
class Post
{
    /**
     * @ORM\Id
     * @ORM\Column(type="uuid", unique=true)
     * @ORM\GeneratedValue(strategy="CUSTOM")
     * @ORM\CustomIdGenerator(class="Ramsey\Uuid\Doctrine\UuidGenerator")
     */
    private UuidInterface $id;

    /** @ORM\Column(length=255) */
    private string $title;

    /** @ORM\Column(type="text") */
    private string $content;

    /** @ORM\Column(type="string", enumType=PostStatus::class) */
    private PostStatus $status;

    /**
     * @ORM\ManyToOne(targetEntity="User", inversedBy="posts")
     * @ORM\JoinColumn(nullable=false, name="author_id")
     */
    private ?User $author;
    
    public function getId(): UuidInterface { return $this->id; }
    public function getTitle(): string { return $this->title; }
    public function setTitle(string $title): void { $this->title = $title; }
    public function setStatus(PostStatus $status): void { $this->status = $status; }
    // ...constructor, getters and setters
}


// --- 3. "FAT" REPOSITORY ---
// src/Repository/UserRepository.php
namespace App\Repository;

use App\Entity\User;
use App\Entity\Post;
use App\Entity\Role;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Doctrine\ORM\EntityManagerInterface;

class UserRepository extends ServiceEntityRepository
{
    private EntityManagerInterface $em;

    public function __construct(ManagerRegistry $registry, EntityManagerInterface $em)
    {
        parent::__construct($registry, User::class);
        $this->em = $em;
    }

    // CREATE and TRANSACTION logic inside the repository
    public function createUserWithPostAndRole(string $email, string $pass, string $postTitle, string $roleName): ?User
    {
        $this->em->beginTransaction();
        try {
            $user = new User();
            // ... set user properties
            $this->em->persist($user);

            $post = new Post();
            // ... set post properties and associate with user
            $this->em->persist($post);

            $role = $this->em->getRepository(Role::class)->findOneBy(['name' => $roleName]);
            if ($role) {
                // ... add role to user (Many-to-many)
            }
            
            $this->em->flush();
            $this->em->commit();
            return $user;
        } catch (\Exception $e) {
            $this->em->rollback();
            // log error
            return null;
        }
    }

    // UPDATE logic inside the repository
    public function updateUserEmail(string $oldEmail, string $newEmail): bool
    {
        $user = $this->findOneBy(['email' => $oldEmail]);
        if ($user) {
            // ... set new email
            $this->em->flush();
            return true;
        }
        return false;
    }

    // DELETE logic inside the repository
    public function removeUser(string $email): bool
    {
        $user = $this->findOneBy(['email' => $email]);
        if ($user) {
            $this->em->remove($user);
            $this->em->flush();
            return true;
        }
        return false;
    }

    // READ with DQL and filters
    /**
     * @return User[]
     */
    public function findActiveUsersWithPostCount(bool $isActive, int $minPostCount = 1): array
    {
        $dql = 'SELECT u, COUNT(p.id) as post_count 
                FROM App\Entity\User u 
                JOIN u.posts p
                WHERE u.is_active = :active
                GROUP BY u.id
                HAVING COUNT(p.id) >= :min_posts';
        
        return $this->em->createQuery($dql)
            ->setParameter('active', $isActive)
            ->setParameter('min_posts', $minPostCount)
            ->getResult();
    }
}


// --- 4. THIN CONTROLLER ---
// src/Controller/UserController.php
namespace App\Controller;

use App\Repository\UserRepository;
use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\Routing\Annotation\Route;

class UserController extends AbstractController
{
    private UserRepository $userRepository;

    public function __construct(UserRepository $userRepository)
    {
        $this->userRepository = $userRepository;
    }

    #[Route('/users/create-demo')]
    public function createDemoUser(): JsonResponse
    {
        $user = $this->userRepository->createUserWithPostAndRole(
            'test@example.com', 'pass123', 'My First Post', 'ROLE_USER'
        );
        return new JsonResponse(['userId' => $user ? $user->getId() : null]);
    }

    #[Route('/users/active')]
    public function getActiveUsers(): JsonResponse
    {
        $users = $this->userRepository->findActiveUsersWithPostCount(true, 2);
        return new JsonResponse(['users' => count($users)]);
    }
}