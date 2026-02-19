<?php

// Variation 2: The "Service-Oriented" Developer
// This developer abstracts business logic into dedicated service classes,
// keeping controllers thin and focused on handling HTTP requests/responses.
// Uses DTOs for data transfer.

namespace App\Variation2;

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
use Symfony\Component\Security\Core\Authentication\Token\TokenInterface;
use Symfony\Component\Security\Core\Exception\AccessDeniedException;
use Symfony\Component\Security\Core\Exception\AuthenticationException;
use Symfony\Component\Security\Core\Exception\BadCredentialsException;
use Symfony\Component\Security\Core\Exception\UserNotFoundException;
use Symfony\Component\Security\Core\User\PasswordAuthenticatedUserInterface;
use Symfony\Component\Security\Core\User\UserInterface;
use Symfony\Component\Security\Http\Authenticator\AbstractAuthenticator;
use Symfony\Component\Security\Http\Authenticator\Passport\Badge\UserBadge;
use Symfony\Component\Security\Http\Authenticator\Passport\Passport;
use Symfony\Component\Security\Http\Authenticator\Passport\SelfValidatingPassport;
use Symfony\Component\Security\Core\Security;
use Symfony\Component\Uid\Uuid;
use Symfony\Component\Validator\Constraints as Assert;

// --- Domain Schema & DTOs ---

enum Role: string { case ADMIN = 'ROLE_ADMIN'; case USER = 'ROLE_USER'; }
enum PostStatus: string { case DRAFT = 'DRAFT'; case PUBLISHED = 'PUBLISHED'; }

class LoginRequestDto
{
    #[Assert\NotBlank]
    #[Assert\Email]
    public string $email;

    #[Assert\NotBlank]
    public string $password;
}

class PostDto
{
    #[Assert\NotBlank]
    #[Assert\Length(min: 5)]
    public string $title;

    #[Assert\NotBlank]
    public string $content;
}

#[ORM\Entity]
class User implements UserInterface, PasswordAuthenticatedUserInterface
{
    #[ORM\Id] #[ORM\Column(type: 'uuid')] private Uuid $id;
    #[ORM\Column(length: 180, unique: true)] private string $email;
    #[ORM\Column] private string $password_hash;
    #[ORM\Column(type: 'string', enumType: Role::class)] private Role $role;
    public function __construct(string $email, Role $role = Role::USER) { $this->id = Uuid::v4(); $this->email = $email; $this->role = $role; }
    public function getId(): Uuid { return $this->id; }
    public function getEmail(): string { return $this->email; }
    public function getRoles(): array { return [$this->role->value]; }
    public function getPassword(): string { return $this->password_hash; }
    public function setPassword(string $hash): void { $this->password_hash = $hash; }
    public function eraseCredentials(): void {}
    public function getUserIdentifier(): string { return $this->email; }
}

#[ORM\Entity]
class Post
{
    #[ORM\Id] #[ORM\Column(type: 'uuid')] private Uuid $id;
    #[ORM\Column(type: 'uuid')] private Uuid $user_id;
    #[ORM\Column(length: 255)] private string $title;
    #[ORM\Column(type: 'text')] private string $content;
    public function __construct(Uuid $userId, string $title, string $content) { $this->id = Uuid::v4(); $this->user_id = $userId; $this->title = $title; $this->content = $content; }
    public function getId(): Uuid { return $this->id; }
    public function getUserId(): Uuid { return $this->user_id; }
    public function update(string $title, string $content): void { $this->title = $title; $this->content = $content; }
}

// --- Mock Repository ---

class UserRepository
{
    private array $users = [];
    public function __construct(UserPasswordHasherInterface $hasher) {
        $admin = new User('admin@example.com', Role::ADMIN);
        $admin->setPassword($hasher->hashPassword($admin, 'securepass'));
        $user = new User('user@example.com');
        $user->setPassword($hasher->hashPassword($user, 'securepass'));
        $this->users[$admin->getEmail()] = $admin;
        $this->users[$user->getEmail()] = $user;
    }
    public function findByEmail(string $email): ?User { return $this->users[$email] ?? null; }
}

// --- Services ---

class AuthenticationService
{
    private UserRepository $userRepository;
    private UserPasswordHasherInterface $passwordHasher;
    private JWTTokenManagerInterface $jwtManager;

    public function __construct(UserRepository $userRepository, UserPasswordHasherInterface $passwordHasher, JWTTokenManagerInterface $jwtManager)
    {
        $this->userRepository = $userRepository;
        $this->passwordHasher = $passwordHasher;
        $this->jwtManager = $jwtManager;
    }

    public function login(LoginRequestDto $credentials): string
    {
        $user = $this->userRepository->findByEmail($credentials->email);
        if (!$user) {
            throw new UserNotFoundException('User not found.');
        }

        if (!$this->passwordHasher->isPasswordValid($user, $credentials->password)) {
            throw new BadCredentialsException('Invalid credentials.');
        }

        return $this->jwtManager->create($user);
    }
}

class PostService
{
    private Security $security;
    // In a real app, this would be an EntityManagerInterface
    private static array $posts = [];

    public function __construct(Security $security, UserRepository $userRepo)
    {
        $this->security = $security;
        if (empty(self::$posts)) {
            $user = $userRepo->findByEmail('user@example.com');
            $post = new Post($user->getId(), 'Initial Post', 'Content');
            self::$posts[$post->getId()->toRfc4122()] = $post;
        }
    }

    public function createPost(PostDto $postData): Post
    {
        if (!$this->security->isGranted('ROLE_USER')) {
            throw new AccessDeniedException();
        }
        /** @var User $user */
        $user = $this->security->getUser();
        $post = new Post($user->getId(), $postData->title, $postData->content);
        self::$posts[$post->getId()->toRfc4122()] = $post;
        return $post;
    }

    public function updatePost(string $postId, PostDto $postData): Post
    {
        $post = self::$posts[$postId] ?? null;
        if (!$post) {
            // throw NotFoundHttpException
            return null;
        }

        if (!$this->security->isGranted('ROLE_ADMIN') && $post->getUserId() != $this->security->getUser()->getId()) {
            throw new AccessDeniedException('You are not allowed to edit this post.');
        }

        $post->update($postData->title, $postData->content);
        return $post;
    }
    
    public function findPost(string $id): ?Post
    {
        return self::$posts[$id] ?? null;
    }
}

// --- Security: Custom Authenticator ---

class ApiTokenAuthenticator extends AbstractAuthenticator
{
    private AuthenticationService $authService;

    public function __construct(AuthenticationService $authService)
    {
        $this->authService = $authService;
    }

    public function supports(Request $request): ?bool
    {
        return $request->isMethod('POST') && $request->getPathInfo() === '/api/auth/token';
    }

    public function authenticate(Request $request): Passport
    {
        try {
            $dto = new LoginRequestDto();
            $data = json_decode($request->getContent(), true);
            $dto->email = $data['email'] ?? '';
            $dto->password = $data['password'] ?? '';
            
            // The service handles validation and returns a JWT.
            // We don't need to proceed with Symfony's full passport process,
            // as we are not managing a session. We just need to signal success.
            // A SelfValidatingPassport is good for this.
            $userBadge = new UserBadge($dto->email, function($userIdentifier) {
                // This lookup is just to satisfy the UserBadge, the real auth happens in the service.
                return new User($userIdentifier);
            });
            return new SelfValidatingPassport($userBadge);

        } catch (\Exception $e) {
            throw new AuthenticationException($e->getMessage());
        }
    }

    public function onAuthenticationSuccess(Request $request, TokenInterface $token, string $firewallName): ?Response
    {
        // The actual token generation is in the controller, which calls the service.
        // This authenticator is just a formality for the firewall.
        // In a more integrated approach, the token could be added to the response here.
        return null;
    }

    public function onAuthenticationFailure(Request $request, AuthenticationException $exception): ?Response
    {
        return new JsonResponse(['error' => $exception->getMessage()], Response::HTTP_UNAUTHORIZED);
    }
}


// --- Controllers (Thin) ---

#[Route('/api')]
class SecurityController extends AbstractController
{
    private AuthenticationService $authService;

    public function __construct(AuthenticationService $authService)
    {
        $this->authService = $authService;
    }

    #[Route('/auth/token', name: 'api_login', methods: ['POST'])]
    public function login(#[MapRequestPayload] LoginRequestDto $credentials): JsonResponse
    {
        try {
            $token = $this->authService->login($credentials);
            return $this->json(['token' => $token]);
        } catch (UserNotFoundException | BadCredentialsException $e) {
            return $this->json(['error' => 'Invalid credentials'], Response::HTTP_UNAUTHORIZED);
        }
    }
}

#[Route('/api/posts')]
class PostController extends AbstractController
{
    private PostService $postService;

    public function __construct(PostService $postService)
    {
        $this->postService = $postService;
    }

    #[Route('', name: 'api_post_create', methods: ['POST'])]
    public function create(#[MapRequestPayload] PostDto $postData): JsonResponse
    {
        try {
            $post = $this->postService->createPost($postData);
            return $this->json(['id' => $post->getId()], Response::HTTP_CREATED);
        } catch (AccessDeniedException $e) {
            return $this->json(['error' => 'Access Denied'], Response::HTTP_FORBIDDEN);
        }
    }

    #[Route('/{id}', name: 'api_post_update', methods: ['PUT'])]
    public function update(string $id, #[MapRequestPayload] PostDto $postData): JsonResponse
    {
        try {
            $post = $this->postService->updatePost($id, $postData);
            if (!$post) {
                return $this->json(['error' => 'Post not found'], Response::HTTP_NOT_FOUND);
            }
            return $this->json(['id' => $post->getId()]);
        } catch (AccessDeniedException $e) {
            return $this->json(['error' => 'Access Denied'], Response::HTTP_FORBIDDEN);
        }
    }
}

/*
--- REQUIRED CONFIGURATION ---

// config/packages/security.php
use App\Variation2\User;

return static function (Symfony\Config\SecurityConfig $security) {
    $security->passwordHasher(User::class)->algorithm('auto');

    $security->provider('app_user_provider')
        ->entity()
        ->class(User::class)
        ->property('email');

    // This firewall is just to trigger the authenticator, but the controller handles the response.
    $security->firewall('login')
        ->pattern('^/api/auth/token')
        ->stateless(true)
        ->customAuthenticator(App\Variation2\ApiTokenAuthenticator::class);

    $security->firewall('api')
        ->pattern('^/api')
        ->stateless(true)
        ->jwt();

    $security->accessControl()
        ->path('^/api/auth/token')->roles(['PUBLIC_ACCESS'])
        ->path('^/api')->roles(['IS_AUTHENTICATED_FULLY']);
};

// config/packages/lexik_jwt_authentication.php
// Same as Variation 1
*/