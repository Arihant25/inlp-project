<?php

// Variation 1: The "By-the-Book" Developer
// Style: Clean, service-oriented architecture. Logic is in dedicated services.
// Controllers are thin and delegate work. Uses modern PHP 8 attributes.
// Clear, verbose variable names. Follows official Symfony best practices closely.

// --- 1. MIGRATION ---
// migrations/Version20231115100001.php

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20231115100001 extends AbstractMigration
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

    public function down(Schema $schema): void
    {
        // down migration logic
    }
}


// --- 2. ENUMS and ENTITIES ---
// src/Entity/Enum/PostStatus.php
namespace App\Entity\Enum;

enum PostStatus: string
{
    case DRAFT = 'draft';
    case PUBLISHED = 'published';
}

// src/Entity/Role.php
namespace App\Entity;

use App\Repository\RoleRepository;
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\ORM\Mapping as ORM;

#[ORM\Entity(repositoryClass: RoleRepository::class)]
#[ORM\Table(name: '`role`')]
class Role
{
    #[ORM\Id]
    #[ORM\GeneratedValue]
    #[ORM\Column(type: 'integer')]
    private ?int $id = null;

    #[ORM\Column(length: 50, unique: true)]
    private string $name;

    #[ORM\ManyToMany(targetEntity: User::class, mappedBy: 'roles')]
    private Collection $users;

    public function __construct()
    {
        $this->users = new ArrayCollection();
    }
    // ...getters and setters
}

// src/Entity/User.php
namespace App\Entity;

use App\Repository\UserRepository;
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\ORM\Mapping as ORM;
use Ramsey\Uuid\UuidInterface;
use Ramsey\Uuid\Doctrine\UuidGenerator;

#[ORM\Entity(repositoryClass: UserRepository::class)]
#[ORM\Table(name: '`user`')]
class User
{
    #[ORM\Id]
    #[ORM\Column(type: 'uuid', unique: true)]
    #[ORM\GeneratedValue(strategy: 'CUSTOM')]
    #[ORM\CustomIdGenerator(class: UuidGenerator::class)]
    private UuidInterface $id;

    #[ORM\Column(length: 180, unique: true)]
    private string $email;

    #[ORM\Column]
    private string $password_hash;

    #[ORM\Column]
    private bool $is_active = true;

    #[ORM\Column]
    private \DateTimeImmutable $created_at;

    #[ORM\OneToMany(mappedBy: 'author', targetEntity: Post::class, cascade: ['persist', 'remove'])]
    private Collection $posts;

    #[ORM\ManyToMany(targetEntity: Role::class, inversedBy: 'users')]
    #[ORM\JoinTable(name: 'user_role')]
    private Collection $roles;

    public function __construct(string $email, string $password_hash)
    {
        $this->email = $email;
        $this->password_hash = $password_hash;
        $this->posts = new ArrayCollection();
        $this->roles = new ArrayCollection();
        $this->created_at = new \DateTimeImmutable();
    }
    
    public function addPost(Post $post): self
    {
        if (!$this->posts->contains($post)) {
            $this->posts[] = $post;
            $post->setAuthor($this);
        }
        return $this;
    }
    // ...getters and setters
}

// src/Entity/Post.php
namespace App\Entity;

use App\Entity\Enum\PostStatus;
use App\Repository\PostRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Ramsey\Uuid\UuidInterface;
use Ramsey\Uuid\Doctrine\UuidGenerator;

#[ORM\Entity(repositoryClass: PostRepository::class)]
class Post
{
    #[ORM\Id]
    #[ORM\Column(type: 'uuid', unique: true)]
    #[ORM\GeneratedValue(strategy: 'CUSTOM')]
    #[ORM\CustomIdGenerator(class: UuidGenerator::class)]
    private UuidInterface $id;

    #[ORM\Column(length: 255)]
    private string $title;

    #[ORM\Column(type: Types::TEXT)]
    private string $content;

    #[ORM\Column(type: 'string', enumType: PostStatus::class)]
    private PostStatus $status;

    #[ORM\ManyToOne(inversedBy: 'posts')]
    #[ORM\JoinColumn(nullable: false, name: 'author_id')]
    private ?User $author;

    public function __construct(string $title, string $content, User $author)
    {
        $this->title = $title;
        $this->content = $content;
        $this->author = $author;
        $this->status = PostStatus::DRAFT;
    }
    
    public function setAuthor(?User $author): self { $this->author = $author; return $this; }
    public function getAuthor(): ?User { return $this->author; }
    public function getTitle(): string { return $this->title; }
    public function setTitle(string $title): self { $this->title = $title; return $this; }
    public function setStatus(PostStatus $status): self { $this->status = $status; return $this; }
    // ...other getters
}


// --- 3. REPOSITORY with Query Builder ---
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
    public function findPublishedPostsByAuthorEmail(string $email): array
    {
        $queryBuilder = $this->createQueryBuilder('p');

        return $queryBuilder
            ->innerJoin('p.author', 'u')
            ->where($queryBuilder->expr()->eq('p.status', ':status'))
            ->andWhere($queryBuilder->expr()->eq('u.email', ':email'))
            ->setParameter('status', PostStatus::PUBLISHED->value)
            ->setParameter('email', $email)
            ->orderBy('p.title', 'ASC')
            ->getQuery()
            ->getResult();
    }
}


// --- 4. SERVICE LAYER ---
// src/Service/UserManager.php
namespace App\Service;

use App\Entity\User;
use App\Entity\Post;
use App\Entity\Enum\PostStatus;
use Doctrine\ORM\EntityManagerInterface;
use Psr\Log\LoggerInterface;

class UserManager
{
    public function __construct(
        private readonly EntityManagerInterface $entityManager,
        private readonly PostRepository $postRepository,
        private readonly LoggerInterface $logger
    ) {}

    /**
     * Demonstrates a transactional operation.
     * Creates a user and a post. If post creation fails, the user is rolled back.
     */
    public function createUserWithInitialPost(string $email, string $password, string $postTitle, string $postContent): ?User
    {
        $this->entityManager->beginTransaction();
        try {
            // CREATE User
            $newUser = new User($email, $password);
            $this->entityManager->persist($newUser);

            // CREATE Post (One-to-many)
            $newPost = new Post($postTitle, $postContent, $newUser);
            $this->entityManager->persist($newPost);
            
            // This would cause a rollback if uncommented
            // if (empty($postTitle)) {
            //     throw new \InvalidArgumentException("Post title cannot be empty.");
            // }

            $this->entityManager->flush();
            $this->entityManager->commit();

            return $newUser;
        } catch (\Exception $exception) {
            $this->entityManager->rollback();
            $this->logger->error('User creation transaction failed: ' . $exception->getMessage());
            return null;
        }
    }

    // READ (using custom repository method with query builder)
    public function findPublishedPostsForUser(string $email): array
    {
        return $this->postRepository->findPublishedPostsByAuthorEmail($email);
    }

    // UPDATE
    public function changePostTitle(UuidInterface $postId, string $newTitle): ?Post
    {
        $post = $this->postRepository->find($postId);
        if (!$post) {
            return null;
        }
        $post->setTitle($newTitle);
        $this->entityManager->flush();
        return $post;
    }

    // DELETE
    public function deleteUserAndPosts(UuidInterface $userId): bool
    {
        $user = $this->entityManager->getRepository(User::class)->find($userId);
        if ($user) {
            // Thanks to `cascade: ['remove']` on the User::$posts relationship,
            // removing the user will also remove their posts.
            $this->entityManager->remove($user);
            $this->entityManager->flush();
            return true;
        }
        return false;
    }
}