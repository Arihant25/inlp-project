<?php

// Variation 3: The "Explicit & Verbose" Developer
// This developer avoids "magic" where possible. Security checks are performed
// explicitly in controller methods using the Security service. JWTs are also
// generated manually in the controller to show the underlying mechanism.

namespace App\Variation3;

use Doctrine\ORM\Mapping as ORM;
use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\PasswordHasher\Hasher\UserPasswordHasherInterface;
use Symfony\Component\Routing\Annotation\Route;
use Symfony\Component\Security\Core\Authentication\Token\TokenInterface;
use Symfony\Component\Security\Core\Exception\AuthenticationException;
use Symfony\Component\Security\Core\Exception\UserNotFoundException;
use Symfony\Component\Security\Core\User\PasswordAuthenticatedUserInterface;
use Symfony\Component\Security\Core\User\UserInterface;
use Symfony\Component\Security\Core\User\UserProviderInterface;
use Symfony\Component\Security\Http\Authenticator\AbstractLoginFormAuthenticator;
use Symfony\Component\Security\Http\Authenticator\Passport\Badge\UserBadge;
use Symfony\Component\Security\Http\Authenticator\Passport\Credentials\PasswordCredentials;
use Symfony\Component\Security\Http\Authenticator\Passport\Passport;
use Symfony\Component\Security\Http\Util\TargetPathTrait;
use Symfony\Component\Security\Core\Security;
use Symfony\Component\Uid\Uuid;

// --- Domain Schema ---

enum UserRole: string { case ROLE_ADMIN = 'ROLE_ADMIN'; case ROLE_USER = 'ROLE_USER'; }
enum PublicationStatus: string { case DRAFT = 'DRAFT'; case PUBLISHED = 'PUBLISHED'; }

#[ORM\Entity]
class User implements UserInterface, PasswordAuthenticatedUserInterface
{
    #[ORM\Id] #[ORM\Column(type: 'uuid')] private Uuid $id;
    #[ORM\Column(length: 180, unique: true)] private string $email_address;
    #[ORM\Column] private string $password_hash;
    #[ORM\Column(type: 'string', enumType: UserRole::class)] private UserRole $role;
    public function __construct() { $this->id = Uuid::v4(); }
    public function getId(): Uuid { return $this->id; }
    public function getEmail(): string { return $this->email_address; }
    public function setEmail(string $email): self { $this->email_address = $email; return $this; }
    public function getRoles(): array { return [$this->role->value]; }
    public function setRole(UserRole $role): self { $this->role = $role; return $this; }
    public function getPassword(): string { return $this->password_hash; }
    public function setPassword(string $hash): self { $this->password_hash = $hash; return $this; }
    public function eraseCredentials(): void {}
    public function getUserIdentifier(): string { return $this->email_address; }
}

#[ORM\Entity]
class Post
{
    #[ORM\Id] #[ORM\Column(type: 'uuid')] private Uuid $id;
    #[ORM\Column(type: 'uuid')] private Uuid $author_id;
    #[ORM\Column(length: 255)] private string $title;
    public function __construct(Uuid $authorId, string $title) { $this->id = Uuid::v4(); $this->author_id = $authorId; $this->title = $title; }
    public function getId(): Uuid { return $this->id; }
    public function getAuthorId(): Uuid { return $this->author_id; }
}

// --- Mock User Provider ---

class AppUserProvider implements UserProviderInterface
{
    private array $inMemoryUsers = [];
    private UserPasswordHasherInterface $hasher;

    public function __construct(UserPasswordHasherInterface $hasher)
    {
        $this->hasher = $hasher;
        $this->loadUsers();
    }

    private function loadUsers(): void
    {
        $admin = (new User())
            ->setEmail('admin@example.com')
            ->setRole(UserRole::ROLE_ADMIN);
        $admin->setPassword($this->hasher->hashPassword($admin, 'adminpass'));

        $user = (new User())
            ->setEmail('user@example.com')
            ->setRole(UserRole::ROLE_USER);
        $user->setPassword($this->hasher->hashPassword($user, 'userpass'));

        $this->inMemoryUsers[$admin->getEmail()] = $admin;
        $this->inMemoryUsers[$user->getEmail()] = $user;
    }

    public function loadUserByIdentifier(string $identifier): UserInterface
    {
        $user = $this->inMemoryUsers[$identifier] ?? null;
        if (null === $user) {
            throw new UserNotFoundException(sprintf('User with email "%s" not found.', $identifier));
        }
        return $user;
    }

    public function refreshUser(UserInterface $user): UserInterface { return $this->loadUserByIdentifier($user->getUserIdentifier()); }
    public function supportsClass(string $class): bool { return $class === User::class; }
}

// --- Security: Session-based Authenticator ---

class SessionFormAuthenticator extends AbstractLoginFormAuthenticator
{
    use TargetPathTrait;
    public const LOGIN_ROUTE = 'app_login';

    public function authenticate(Request $request): Passport
    {
        $email = $request->request->get('email', '');
        $password = $request->request->get('password', '');
        $request->getSession()->set(Security::LAST_USERNAME, $email);

        return new Passport(
            new UserBadge($email),
            new PasswordCredentials($password)
        );
    }

    public function onAuthenticationSuccess(Request $request, TokenInterface $token, string $firewallName): ?Response
    {
        if ($targetPath = $this->getTargetPath($request->getSession(), $firewallName)) {
            return new \Symfony\Component\HttpFoundation\RedirectResponse($targetPath);
        }
        return new \Symfony\Component\HttpFoundation\RedirectResponse('/profile');
    }

    protected function getLoginUrl(Request $request): string
    {
        return '/login'; // Assuming a router generates this URL from 'app_login'
    }
}

// --- Controllers ---

#[Route('/')]
class AuthController extends AbstractController
{
    private AppUserProvider $userProvider;
    private UserPasswordHasherInterface $passwordHasher;
    private JWTTokenManagerInterface $jwtManager;

    public function __construct(
        AppUserProvider $userProvider,
        UserPasswordHasherInterface $passwordHasher,
        JWTTokenManagerInterface $jwtManager
    ) {
        $this->userProvider = $userProvider;
        $this->passwordHasher = $passwordHasher;
        $this->jwtManager = $jwtManager;
    }

    /**
     * This action explicitly validates credentials and generates a JWT.
     * It does not use the security system's authenticator, demonstrating a manual approach.
     */
    #[Route('api/token', name: 'api_get_token', methods: ['POST'])]
    public function getApiToken(Request $request): JsonResponse
    {
        $data = json_decode($request->getContent(), true);
        $email = $data['email'] ?? null;
        $password = $data['password'] ?? null;

        if (!$email || !$password) {
            return new JsonResponse(['message' => 'Email and password are required'], Response::HTTP_BAD_REQUEST);
        }

        try {
            $user = $this->userProvider->loadUserByIdentifier($email);
        } catch (UserNotFoundException $e) {
            return new JsonResponse(['message' => 'Invalid credentials'], Response::HTTP_UNAUTHORIZED);
        }

        if (!$this->passwordHasher->isPasswordValid($user, $password)) {
            return new JsonResponse(['message' => 'Invalid credentials'], Response::HTTP_UNAUTHORIZED);
        }

        // Manually create the JWT token
        $token = $this->jwtManager->create($user);

        return new JsonResponse(['token' => $token]);
    }
}

#[Route('/api/posts')]
class PostApiController extends AbstractController
{
    // Mock storage
    private static array $posts = [];

    public function __construct(AppUserProvider $userProvider)
    {
        if (empty(self::$posts)) {
            $user = $userProvider->loadUserByIdentifier('user@example.com');
            $post = new Post($user->getId(), 'A User\'s Post');
            self::$posts[$post->getId()->toRfc4122()] = $post;
        }
    }

    #[Route('/{id}', name: 'api_post_update', methods: ['PUT'])]
    public function updatePost(string $id, Request $request, Security $security): JsonResponse
    {
        // Step 1: Ensure user is authenticated. The firewall does this.
        $currentUser = $security->getUser();
        if (null === $currentUser) {
            // This case is typically handled by the firewall, but we check explicitly.
            return new JsonResponse(['message' => 'Authentication required'], Response::HTTP_UNAUTHORIZED);
        }

        $post = self::$posts[$id] ?? null;
        if (null === $post) {
            return new JsonResponse(['message' => 'Post not found'], Response::HTTP_NOT_FOUND);
        }

        // Step 2: Explicitly check for authorization.
        $isAuthor = $post->getAuthorId()->equals($currentUser->getId());
        $isAdmin = $security->isGranted(UserRole::ROLE_ADMIN->value);

        if (!$isAuthor && !$isAdmin) {
            return new JsonResponse(['message' => 'Access Denied. You are not the author or an admin.'], Response::HTTP_FORBIDDEN);
        }

        // ... update logic ...
        return new JsonResponse(['status' => 'Post updated successfully']);
    }
}

/*
--- REQUIRED CONFIGURATION ---

// config/packages/security.php
use App\Variation3\User;
use App\Variation3\AppUserProvider;
use App\Variation3\SessionFormAuthenticator;

return static function (Symfony\Config\SecurityConfig $security) {
    $security->passwordHasher(User::class)->algorithm('auto');

    // Custom user provider service definition
    $security->provider('app_user_provider_service')
        ->id(AppUserProvider::class);

    // Firewall for traditional session-based login
    $security->firewall('main')
        ->pattern('^/')
        ->provider('app_user_provider_service')
        ->customAuthenticator(SessionFormAuthenticator::class)
        ->logout()->path('app_logout');

    // Stateless firewall for the API
    $security->firewall('api')
        ->pattern('^/api')
        ->stateless(true)
        ->provider('app_user_provider_service')
        ->jwt();

    // The /api/token route is public as it handles its own auth logic
    $security->accessControl()
        ->path('^/api/token')->roles(['PUBLIC_ACCESS'])
        ->path('^/api')->roles(['IS_AUTHENTICATED_FULLY'])
        ->path('^/login')->roles(['PUBLIC_ACCESS'])
        ->path('^/profile')->roles(['ROLE_USER']);
};

// config/packages/lexik_jwt_authentication.php
// Same as Variation 1
*/