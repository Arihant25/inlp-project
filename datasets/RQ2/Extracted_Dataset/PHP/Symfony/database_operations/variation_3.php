<?php

// Variation 3: The "Action-Oriented" Developer
// Style: Uses invokable, single-action controllers (or "Action" classes).
// Each class has a single responsibility and is triggered by a route.
// This approach is highly focused and adheres to the Single Responsibility Principle.

// --- 1. MIGRATION ---
// migrations/Version20231115100003.php
namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20231115100003 extends AbstractMigration
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

// --- 3. REPOSITORY ---
// src/Repository/UserRepository.php
namespace App\Repository;

use App\Entity\User;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;

class UserRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, User::class);
    }
}

// --- 4. ACTION CLASSES ---

// src/Action/CreateUserWithPostsAction.php
namespace App\Action;

use App\Entity\User;
use App\Entity\Post;
use App\Entity\Role;
use Doctrine\ORM\EntityManagerInterface;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\Routing\Annotation\Route;

#[Route('/api/users', name: 'user_create_with_posts', methods: ['POST'])]
class CreateUserWithPostsAction
{
    public function __construct(private readonly EntityManagerInterface $em) {}

    // This action demonstrates CREATE, One-to-many, Many-to-many, and a TRANSACTION.
    public function __invoke(Request $request): JsonResponse
    {
        $data = $request->toArray();
        $userEmail = $data['email'] ?? null;
        $postsData = $data['posts'] ?? [];

        if (!$userEmail || empty($postsData)) {
            return new JsonResponse(['error' => 'Invalid data'], 400);
        }

        $this->em->getConnection()->beginTransaction();
        try {
            $user = new User($userEmail, 'hashed_password');
            
            // Add posts (One-to-many)
            foreach ($postsData as $postItem) {
                $post = new Post($postItem['title'], $postItem['content'], $user);
                $user->addPost($post); // The relationship is managed by the User entity
            }

            // Add role (Many-to-many)
            $userRole = $this->em->getRepository(Role::class)->findOneBy(['name' => 'ROLE_USER']);
            if ($userRole) {
                $user->addRole($userRole);
            }

            $this->em->persist($user);
            $this->em->flush();
            $this->em->getConnection()->commit();

            return new JsonResponse(['userId' => $user->getId()], 201);

        } catch (\Exception $e) {
            $this->em->getConnection()->rollBack();
            return new JsonResponse(['error' => 'Transaction failed', 'details' => $e->getMessage()], 500);
        }
    }
}

// src/Action/FindUsersByCriteriaAction.php
namespace App\Action;

use App\Repository\UserRepository;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\Routing\Annotation\Route;

#[Route('/api/users/search', name: 'users_find_by_criteria', methods: ['GET'])]
class FindUsersByCriteriaAction
{
    public function __construct(private readonly UserRepository $userRepository) {}

    // This action demonstrates READ with a dynamic QUERY BUILDER.
    public function __invoke(Request $request): JsonResponse
    {
        $qb = $this->userRepository->createQueryBuilder('u');

        if ($request->query->has('active')) {
            $qb->andWhere('u.is_active = :isActive')
               ->setParameter('isActive', $request->query->getBoolean('active'));
        }

        if ($emailDomain = $request->query->get('email_domain')) {
            $qb->andWhere($qb->expr()->like('u.email', ':domain'))
               ->setParameter('domain', '%' . $emailDomain);
        }
        
        $qb->orderBy('u.created_at', 'DESC');
        
        $users = $qb->getQuery()->getArrayResult();

        return new JsonResponse($users);
    }
}

// src/Action/DeletePostAction.php
namespace App\Action;

use App\Repository\PostRepository;
use Doctrine\ORM\EntityManagerInterface;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\Routing\Annotation\Route;
use Ramsey\Uuid\Uuid;

#[Route('/api/posts/{id}', name: 'post_delete', methods: ['DELETE'])]
class DeletePostAction
{
    public function __construct(
        private readonly PostRepository $postRepository,
        private readonly EntityManagerInterface $em
    ) {}

    // This action demonstrates DELETE.
    public function __invoke(string $id): JsonResponse
    {
        if (!Uuid::isValid($id)) {
            return new JsonResponse(['error' => 'Invalid post ID'], 400);
        }

        $post = $this->postRepository->find($id);

        if (!$post) {
            return new JsonResponse(null, 404);
        }

        $this->em->remove($post);
        $this->em->flush();

        return new JsonResponse(null, 204);
    }
}