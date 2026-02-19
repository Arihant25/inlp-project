<?php

// This variation represents a "by-the-book" Symfony developer.
// It uses modern PHP 8 attributes, a dedicated Data Transfer Object (DTO) for the request payload,
// and leverages the `MapRequestPayload` attribute for automatic deserialization and validation.
// A custom validator is implemented as a separate class for clean separation of concerns.

// --- Mocks and Stubs for self-containment ---
namespace App\Entity {
    use Symfony\Component\Uid\Uuid;
    enum UserRole: string { case ADMIN = 'admin'; case USER = 'user'; }
    class User {
        public function __construct(
            public Uuid $id,
            public string $email,
            public string $password_hash,
            public UserRole $role,
            public bool $is_active,
            public \DateTimeImmutable $created_at
        ) {}
    }
}

namespace App\Validator\Constraints {
    use Symfony\Component\Validator\Constraint;
    #[\Attribute]
    class NotDisallowedDomain extends Constraint {
        public string $message = 'The domain "{{ domain }}" is not allowed for registration.';
        public array $disallowedDomains = ['spam.com', 'fake.net'];
    }
}

namespace App\Validator\Constraints {
    use Symfony\Component\Validator\Constraint;
    use Symfony\Component\Validator\ConstraintValidator;
    use Symfony\Component\Validator\Exception\UnexpectedValueException;

    class NotDisallowedDomainValidator extends ConstraintValidator
    {
        public function validate(mixed $value, Constraint $constraint): void
        {
            if (!$constraint instanceof NotDisallowedDomain) {
                throw new \InvalidArgumentException(sprintf('The constraint must be an instance of "%s".', NotDisallowedDomain::class));
            }
            if (null === $value || '' === $value) {
                return;
            }
            if (!is_string($value)) {
                throw new UnexpectedValueException($value, 'string');
            }
            if (false === strpos($value, '@')) {
                return; // Not a valid email format, let the Email constraint handle this.
            }
            $domain = substr($value, strpos($value, '@') + 1);
            if (in_array($domain, $constraint->disallowedDomains, true)) {
                $this->context->buildViolation($constraint->message)
                    ->setParameter('{{ domain }}', $this->formatValue($domain))
                    ->addViolation();
            }
        }
    }
}

namespace App\DTO {
    use Symfony\Component\Validator\Constraints as Assert;
    use App\Validator\Constraints as CustomAssert;
    use App\Entity\UserRole;

    // DTO for creating a new user.
    class UserCreateRequest
    {
        #[Assert\NotBlank]
        #[Assert\Email(message: "The email '{{ value }}' is not a valid email.")]
        #[CustomAssert\NotDisallowedDomain]
        public string $email;

        #[Assert\NotBlank]
        #[Assert\Length(min: 8, minMessage: "Your password must be at least {{ limit }} characters long.")]
        public string $password;

        // Type coercion: The request can send "user" or "admin", which will be automatically
        // converted to the UserRole enum instance.
        #[Assert\NotNull]
        public UserRole $role = UserRole::USER;

        // Type coercion: The request can send 1, "1", true, "true" which will be coerced to a boolean.
        #[Assert\Type('bool')]
        public bool $isActive = true;
    }
}

namespace App\Controller {
    use App\DTO\UserCreateRequest;
    use App\Entity\User;
    use App\Entity\UserRole;
    use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
    use Symfony\Component\HttpFoundation\JsonResponse;
    use Symfony\Component\HttpFoundation\Response;
    use Symfony\Component\HttpKernel\Attribute\MapRequestPayload;
    use Symfony\Component\Routing\Annotation\Route;
    use Symfony\Component\Serializer\SerializerInterface;
    use Symfony\Component\Uid\Uuid;

    class UserController extends AbstractController
    {
        /**
         * Creates a new user.
         * The #[MapRequestPayload] attribute automatically:
         * 1. Deserializes the JSON request body into the UserCreateRequest DTO.
         * 2. Validates the DTO. If validation fails, it throws an HttpException,
         *    which results in a 422 Unprocessable Entity response with detailed errors.
         */
        #[Route('/api/users', methods: ['POST'])]
        public function createUser(
            #[MapRequestPayload] UserCreateRequest $userDto,
            SerializerInterface $serializer
        ): JsonResponse {
            // At this point, $userDto is a valid object.
            // Business logic would go here (e.g., hashing password, saving to DB).
            $passwordHash = password_hash($userDto->password, PASSWORD_DEFAULT);
            $newUser = new User(
                Uuid::v4(),
                $userDto->email,
                $passwordHash,
                $userDto->role,
                $userDto->isActive,
                new \DateTimeImmutable()
            );

            // Mock saving the user
            // $this->userService->save($newUser);

            // Serialize the created user entity for the response.
            // We exclude the password_hash for security.
            $jsonResponse = $serializer->serialize($newUser, 'json', ['ignored_attributes' => ['password_hash']]);

            return new JsonResponse($jsonResponse, Response::HTTP_CREATED, [], true);
        }
    }
}