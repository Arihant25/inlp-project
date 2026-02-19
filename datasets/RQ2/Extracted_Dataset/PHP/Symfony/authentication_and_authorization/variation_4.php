<?php

// Variation 4: The "Modern & Minimalist" Developer
// This developer uses modern PHP 8+ features like constructor property promotion
// and attributes to write concise, expressive code. Controllers are kept lean
// by leveraging argument resolvers like #[CurrentUser] and #[MapRequestPayload].

namespace App\Variation4;

use Doctrine\ORM\Mapping as ORM;
use KnpU\OAuth2ClientBundle\Client\ClientRegistry;
use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Attribute\MapRequestPayload;
use Symfony\Component\PasswordHasher\Hasher\UserPasswordHasherInterface;
use Symfony\Component\Routing\Annotation\Route;
use Symfony\Component\Security\Core\Exception\AuthenticationException;
use Symfony\Component\Security\Core\User\PasswordAuthenticatedUserInterface;
use Symfony\Component\Security\Core\User\UserInterface;
use Symfony\Component\Security\Http\Attribute\CurrentUser;
use Symfony\Component\Security\Http\Attribute\IsGranted;
use Symfony\Component\Security\Http\Authenticator\JsonLoginAuthenticator;
use Symfony\Component\Uid\Uuid;
use Symfony\Component\Validator\Constraints as Assert;

// --- Domain Schema & DTOs ---

enum Role: string { case ADMIN = 'ROLE_ADMIN'; case USER = 'ROLE_USER'; }
enum PostStatus: string { case DRAFT = 'DRAFT'; case PUBLISHED = 'PUBLISHED'; }

class PostPayload
{
    public function __construct(
        #[Assert\NotBlank]
        #[Assert\Length(min: 3, max: 255)]
        public readonly string $title,

        #[Assert\NotBlank]
        public readonly string $content,
    ) {}
}

#[ORM\Entity]
class User implements UserInterface, PasswordAuthenticatedUserInterface
{
    #[ORM\Id] #[ORM\Column(type: 'uuid')] private Uuid $id;
    #[ORM\Column(length: 180, unique: true)] private string $email;
    #[ORM\Column] private string $password;
    #[ORM\Column(type: 'string', enumType: Role::class)] private Role $role;

    public function __construct(string $email, string $password, Role $role = Role::USER) {
        $this->id = Uuid::v4();
        $this->email = $email;
        $this->password = $password;
        $this->role = $role;
    }
    public function getId(): Uuid { return $this->id; }
    public function getEmail(): string { return $this->email; }
    public function getRoles(): array { return [$this->role->value]; }
    public function getPassword(): string { return $this->password; }
    public function eraseCredentials(): void {}
    public function getUserIdentifier(): string { return $this->email; }
}

#[ORM\Entity]
class Post
{
    #[ORM\Id] #[ORM\Column(type: 'uuid')] private Uuid $id;
    #[ORM\Column(type: 'uuid')] private Uuid $userId;
    #[ORM\Column(length: 255)] private string $title;
    #[ORM\Column(type: 'text')] private string $content;

    public function __construct(Uuid $userId, string $title, string $content) {
        $this->id = Uuid::v4();
        $this->userId = $userId;
        $this->title = $title;
        $this->content = $content;
    }
    public function getId(): Uuid { return $this->id; }
    public function getUserId(): Uuid { return $this->userId; }
    public function isOwnedBy(User $user): bool { return $this->userId->equals($user->getId()); }
}

// --- Mock Repository ---
class UserRepository
{
    private array $users = [];
    public function __construct(UserPasswordHasherInterface $hasher) {
        $this->users['admin@example.com'] = new User('admin@example.com', $hasher->hashPassword(new User('',''), 'pass'), Role::ADMIN);
        $this->users['user@example.com'] = new User('user@example.com', $hasher->hashPassword(new User('',''), 'pass'));
    }
    public function findOneBy(array $criteria): ?User { return $this->users[$criteria['email']] ?? null; }
}

// --- Security Controller ---

#[Route('/api/auth')]
class SecurityController extends AbstractController
{
    // The JsonLoginAuthenticator is configured in security.php and handles the /api/auth/login route.
    // This controller is for OAuth and other auth-related endpoints.

    public function __construct(
        private readonly ClientRegistry $clientRegistry,
        private readonly JWTTokenManagerInterface $jwtManager
    ) {}

    #[Route('/oauth/google', name: 'oauth_google_connect')]
    public function connectGoogle(): Response
    {
        return $this->clientRegistry->getClient('google')->redirect(['profile', 'email']);
    }

    #[Route('/oauth/google/check', name: 'oauth_google_check')]
    public function checkGoogle(#[CurrentUser] ?User $user): JsonResponse
    {
        if (!$user) {
            throw new AuthenticationException('OAuth authentication failed.');
        }
        return new JsonResponse(['token' => $this->jwtManager->create($user)]);
    }
}

// --- API Resource Controller ---

#[Route('/api/posts')]
class PostController extends AbstractController
{
    // Mock storage
    private static array $posts = [];

    public function __construct(UserRepository $userRepo)
    {
        if (empty(self::$posts)) {
            $user = $userRepo->findOneBy(['email' => 'user@example.com']);
            $post = new Post($user->getId(), 'My First Post', 'Hello world!');
            self::$posts[$post->getId()->toRfc4122()] = $post;
        }
    }

    #[Route('', name: 'post_new', methods: ['POST'])]
    #[IsGranted('ROLE_USER')]
    public function new(
        #[MapRequestPayload] PostPayload $payload,
        #[CurrentUser] User $user
    ): JsonResponse {
        $post = new Post($user->getId(), $payload->title, $payload->content);
        self::$posts[$post->getId()->toRfc4122()] = $post;

        return $this->json(['id' => $post->getId()], Response::HTTP_CREATED);
    }

    #[Route('/{id}', name: 'post_show', methods: ['GET'])]
    public function show(string $id): JsonResponse
    {
        $post = self::$posts[$id] ?? null;
        return $post ? $this->json($post) : $this->json(['error' => 'Not Found'], Response::HTTP_NOT_FOUND);
    }

    #[Route('/{id}', name: 'post_edit', methods: ['PUT'])]
    #[IsGranted('ROLE_ADMIN')] // Admins can edit any post
    public function editAsAdmin(string $id, #[MapRequestPayload] PostPayload $payload): JsonResponse
    {
        // ... update logic ...
        return $this->json(['status' => 'Updated by admin']);
    }

    #[Route('/my/{id}', name: 'post_edit_own', methods: ['PUT'])]
    #[IsGranted('ROLE_USER')]
    public function editOwn(string $id, #[MapRequestPayload] PostPayload $payload, #[CurrentUser] User $user): JsonResponse
    {
        $post = self::$posts[$id] ?? null;
        if (!$post || !$post->isOwnedBy($user)) {
            return $this->json(['error' => 'Forbidden or Not Found'], Response::HTTP_FORBIDDEN);
        }
        // ... update logic ...
        return $this->json(['status' => 'Updated by owner']);
    }
}

/*
--- REQUIRED CONFIGURATION ---

// config/packages/security.php
use App\Variation4\User;

return static function (Symfony\Config\SecurityConfig $security) {
    $security->passwordHasher(User::class)->algorithm('auto');

    $security->provider('app_user_provider')
        ->entity()
        ->class(User::class)
        ->property('email');

    // Firewall for standard JWT-based API access
    $security->firewall('api')
        ->pattern('^/api')
        ->stateless(true)
        ->provider('app_user_provider')
        ->jsonLogin()
            ->checkPath('/api/auth/login') // Built-in JsonLoginAuthenticator
            ->usernamePath('email')
            ->passwordPath('password')
            ->successHandler('lexik_jwt_authentication.handler.authentication_success')
            ->failureHandler('lexik_jwt_authentication.handler.authentication_failure')
        ->and()
        ->jwt();

    // Firewall for OAuth handling
    $security->firewall('oauth')
        ->pattern('^/api/auth/oauth')
        ->stateless(true)
        ->customAuthenticator(KnpU\OAuth2ClientBundle\Security\Authenticator\OAuth2Authenticator::class);

    $security->accessControl()
        ->path('^/api/auth/login')->roles(['PUBLIC_ACCESS'])
        ->path('^/api/auth/oauth')->roles(['PUBLIC_ACCESS']);
};

// config/packages/lexik_jwt_authentication.php
// Same as Variation 1

// config/packages/knpu_oauth2_client.yaml
knpu_oauth2_client:
    clients:
        google:
            type: google
            client_id: '%env(GOOGLE_CLIENT_ID)%'
            client_secret: '%env(GOOGLE_CLIENT_SECRET)%'
            redirect_route: oauth_google_check
            redirect_params: {}
*/