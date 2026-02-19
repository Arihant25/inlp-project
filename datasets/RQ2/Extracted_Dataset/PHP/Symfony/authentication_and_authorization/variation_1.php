<?php

// Variation 1: The "By-the-Book" Symfony Developer
// This developer follows official documentation closely, using attributes,
// a dedicated Voter for authorization, and standard dependency injection.

namespace App\Variation1;

use Doctrine\ORM\Mapping as ORM;
use KnpU\OAuth2ClientBundle\Client\ClientRegistry;
use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\PasswordHasher\Hasher\UserPasswordHasherInterface;
use Symfony\Component\Routing\Annotation\Route;
use Symfony\Component\Security\Core\Authentication\Token\TokenInterface;
use Symfony\Component\Security\Core\Exception\AuthenticationException;
use Symfony\Component\Security\Core\Exception\CustomUserMessageAuthenticationException;
use Symfony\Component\Security\Core\User\PasswordAuthenticatedUserInterface;
use Symfony\Component\Security\Core\User\UserInterface;
use Symfony\Component\Security\Core\User\UserProviderInterface;
use Symfony\Component\Security\Http\Authenticator\AbstractAuthenticator;
use Symfony\Component\Security\Http\Authenticator\Passport\Badge\UserBadge;
use Symfony\Component\Security\Http\Authenticator\Passport\Credentials\PasswordCredentials;
use Symfony\Component\Security\Http\Authenticator\Passport\Passport;
use Symfony\Component\Security\Core\Authorization\Voter\Voter;
use Symfony\Component\Security\Core\Security;
use Symfony\Component\Uid\Uuid;

// --- Domain Schema ---

enum Role: string
{
    case ADMIN = 'ROLE_ADMIN';
    case USER = 'ROLE_USER';
}

enum PostStatus: string
{
    case DRAFT = 'DRAFT';
    case PUBLISHED = 'PUBLISHED';
}

#[ORM\Entity(repositoryClass: UserRepository::class)]
class User implements UserInterface, PasswordAuthenticatedUserInterface
{
    #[ORM\Id]
    #[ORM\Column(type: 'uuid', unique: true)]
    private Uuid $id;

    #[ORM\Column(length: 180, unique: true)]
    private string $email;

    #[ORM\Column]
    private string $password_hash;

    #[ORM\Column(type: 'string', enumType: Role::class)]
    private Role $role;
    
    #[ORM\Column]
    private bool $is_active = true;

    #[ORM\Column]
    private \DateTimeImmutable $created_at;

    public function __construct(string $email, string $hashedPassword)
    {
        $this->id = Uuid::v4();
        $this->email = $email;
        $this->password_hash = $hashedPassword;
        $this->role = Role::USER;
        $this->created_at = new \DateTimeImmutable();
    }

    public function getId(): Uuid { return $this->id; }
    public function getEmail(): string { return $this->email; }
    public function getRoles(): array { return [$this->role->value]; }
    public function getPassword(): string { return $this->password_hash; }
    public function eraseCredentials(): void {}
    public function getUserIdentifier(): string { return $this->email; }
}

#[ORM\Entity]
class Post
{
    #[ORM\Id]
    #[ORM\Column(type: 'uuid', unique: true)]
    private Uuid $id;

    #[ORM\Column(type: 'uuid')]
    private Uuid $user_id;

    #[ORM\Column(length: 255)]
    private string $title;

    #[ORM\Column(type: 'text')]
    private string $content;

    #[ORM\Column(type: 'string', enumType: PostStatus::class)]
    private PostStatus $status;

    public function __construct(Uuid $userId, string $title, string $content)
    {
        $this->id = Uuid::v4();
        $this->user_id = $userId;
        $this->title = $title;
        $this->content = $content;
        $this->status = PostStatus::DRAFT;
    }
    
    public function getId(): Uuid { return $this->id; }
    public function getUserId(): Uuid { return $this->user_id; }
}

// --- Mock Repository ---

class UserRepository
{
    private array $users = [];

    public function __construct(UserPasswordHasherInterface $hasher)
    {
        $admin = new User('admin@example.com', 'admin');
        $admin->role = Role::ADMIN;
        $admin->password_hash = $hasher->hashPassword($admin, 'password123');
        
        $user = new User('user@example.com', 'user');
        $user->password_hash = $hasher->hashPassword($user, 'password123');

        $this->users[$admin->getEmail()] = $admin;
        $this->users[$user->getEmail()] = $user;
    }

    public function findOneByEmail(string $email): ?User
    {
        return $this->users[$email] ?? null;
    }
    
    public function find(Uuid $id): ?User
    {
        foreach ($this->users as $user) {
            if ($user->getId()->equals($id)) {
                return $user;
            }
        }
        return null;
    }
}

// --- Security: Authenticator & Voter ---

class JsonLoginAuthenticator extends AbstractAuthenticator
{
    private UserRepository $userRepository;

    public function __construct(UserRepository $userRepository)
    {
        $this->userRepository = $userRepository;
    }

    public function supports(Request $request): ?bool
    {
        return $request->isMethod('POST') && $request->getPathInfo() === '/api/login_check';
    }

    public function authenticate(Request $request): Passport
    {
        $credentials = json_decode($request->getContent(), true);
        if (json_last_error() !== JSON_ERROR_NONE || !isset($credentials['email']) || !isset($credentials['password'])) {
            throw new CustomUserMessageAuthenticationException('Invalid JSON or missing credentials.');
        }

        $userBadge = new UserBadge($credentials['email'], function ($userIdentifier) {
            $user = $this->userRepository->findOneByEmail($userIdentifier);
            if (!$user) {
                throw new CustomUserMessageAuthenticationException('Invalid credentials.');
            }
            return $user;
        });

        return new Passport($userBadge, new PasswordCredentials($credentials['password']));
    }

    public function onAuthenticationSuccess(Request $request, TokenInterface $token, string $firewallName): ?Response
    {
        // JWT token is generated by LexikJWTAuthenticationBundle's event listener
        return null; // Let the bundle handle the response
    }

    public function onAuthenticationFailure(Request $request, AuthenticationException $exception): ?Response
    {
        return new JsonResponse(['message' => strtr($exception->getMessageKey(), $exception->getMessageData())], Response::HTTP_UNAUTHORIZED);
    }
}

class PostVoter extends Voter
{
    const EDIT = 'POST_EDIT';
    const VIEW = 'POST_VIEW';

    protected function supports(string $attribute, mixed $subject): bool
    {
        return in_array($attribute, [self::EDIT, self::VIEW]) && $subject instanceof Post;
    }

    protected function voteOnAttribute(string $attribute, mixed $subject, TokenInterface $token): bool
    {
        $user = $token->getUser();
        if (!$user instanceof User) {
            return false;
        }

        /** @var Post $post */
        $post = $subject;

        return match ($attribute) {
            self::EDIT => $this->canEdit($post, $user),
            self::VIEW => true, // All authenticated users can view
            default => false,
        };
    }

    private function canEdit(Post $post, User $user): bool
    {
        if (in_array(Role::ADMIN->value, $user->getRoles())) {
            return true;
        }
        return $user->getId()->equals($post->getUserId());
    }
}

// --- Controllers ---

#[Route('/api')]
class PostController extends AbstractController
{
    // Mock Post storage
    private static array $posts = [];

    public function __construct(UserRepository $userRepository)
    {
        if (empty(self::$posts)) {
            $user = $userRepository->findOneByEmail('user@example.com');
            self::$posts[] = new Post($user->getId(), 'User Post', 'Content by user.');
        }
    }

    #[Route('/posts', name: 'post_create', methods: ['POST'])]
    #[Security("is_granted('ROLE_USER')")]
    public function createPost(Request $request): JsonResponse
    {
        /** @var User $user */
        $user = $this->getUser();
        $data = json_decode($request->getContent(), true);
        $post = new Post($user->getId(), $data['title'], $data['content']);
        self::$posts[] = $post;

        return $this->json(['status' => 'Post created!', 'postId' => $post->getId()], Response::HTTP_CREATED);
    }

    #[Route('/posts/{id}', name: 'post_update', methods: ['PUT'])]
    #[Security("is_granted('POST_EDIT', post)")]
    public function updatePost(Post $post, Request $request): JsonResponse
    {
        // The #[Security] attribute uses the PostVoter to check for permission.
        // The 'post' variable name must match the argument name.
        $data = json_decode($request->getContent(), true);
        // ... update logic for $post ...
        return $this->json(['status' => 'Post updated!', 'title' => $data['title']]);
    }
}

#[Route('/oauth')]
class OAuthController extends AbstractController
{
    #[Route('/connect/google', name: 'connect_google_start')]
    public function connectAction(ClientRegistry $clientRegistry): Response
    {
        return $clientRegistry
            ->getClient('google')
            ->redirect(['profile', 'email'], []);
    }

    #[Route('/check/google', name: 'connect_google_check')]
    public function connectCheckAction(Request $request, ClientRegistry $clientRegistry, JWTTokenManagerInterface $jwtManager): Response
    {
        // The KnpUOAuth2ClientBundle handles the user creation/retrieval logic
        // via an authenticator. Here we just generate a JWT for the authenticated user.
        /** @var User $user */
        $user = $this->getUser();
        $token = $jwtManager->create($user);

        return new JsonResponse(['token' => $token]);
    }
}

/*
--- REQUIRED CONFIGURATION ---

// config/packages/security.php
use App\Variation1\JsonLoginAuthenticator;
use App\Variation1\User;

return static function (Symfony\Config\SecurityConfig $security) {
    $security->passwordHasher(User::class)->algorithm('auto');

    $security->provider('app_user_provider')
        ->entity()
        ->class(User::class)
        ->property('email');

    $security->firewall('login')
        ->pattern('^/api/login_check')
        ->stateless(true)
        ->jsonLogin()
            ->checkPath('/api/login_check')
            ->usernamePath('email')
            ->passwordPath('password')
            ->successHandler('lexik_jwt_authentication.handler.authentication_success')
            ->failureHandler('lexik_jwt_authentication.handler.authentication_failure');

    $security->firewall('api')
        ->pattern('^/api')
        ->stateless(true)
        ->jwt();
        
    $security->firewall('oauth')
        ->pattern('^/oauth')
        ->customAuthenticator(KnpU\OAuth2ClientBundle\Security\Authenticator\OAuth2Authenticator::class);

    $security->accessControl()
        ->path('^/api/login_check')->roles(['PUBLIC_ACCESS'])
        ->path('^/api')->roles(['IS_AUTHENTICATED_FULLY']);
};

// config/packages/lexik_jwt_authentication.php
return static function (Lexik\Bundle\JWTAuthenticationBundle\LexikJWTAuthenticationBundle $lexik) {
    // Generate your keys: openssl genpkey -out config/jwt/private.pem -aes256 -algorithm rsa -pkeyopt rsa_keygen_bits:4096
    $lexik->privateKeyPath('%kernel.project_dir%/config/jwt/private.pem');
    $lexik->publicKeyPath('%kernel.project_dir%/config/jwt/public.pem');
    $lexik->passPhrase('%env(JWT_PASSPHRASE)%');
    $lexik->tokenTtl(3600);
};

// config/packages/knpu_oauth2_client.yaml
knpu_oauth2_client:
    clients:
        google:
            type: google
            client_id: '%env(GOOGLE_CLIENT_ID)%'
            client_secret: '%env(GOOGLE_CLIENT_SECRET)%'
            redirect_route: connect_google_check
            redirect_params: {}
*/