<?php

// This variation represents a developer with a more procedural or functional leaning.
// They avoid creating a dedicated DTO class for the request, instead defining validation
// rules on the fly using a `Collection` constraint. The controller action contains more
// direct logic for decoding, validation, and error formatting. It also demonstrates
// the use of serialization groups to control the JSON output.

// --- Mocks and Stubs for self-containment ---
namespace App\Domain\Model {
    use Symfony\Component\Serializer\Annotation\Groups;
    use Symfony\Component\Uid\Uuid;
    enum UserRole: string { case ADMIN = 'admin'; case USER = 'user'; }
    class User {
        public function __construct(
            #[Groups(["user:read", "user:admin"])] public Uuid $id,
            #[Groups(["user:read", "user:admin"])] public string $email,
            public string $password_hash, // Not in any group, so never serialized
            #[Groups("user:admin")] public UserRole $role,
            #[Groups("user:admin")] public bool $is_active,
            #[Groups("user:read")] public \DateTimeImmutable $created_at
        ) {}
    }
}

namespace App\Controller {
    use App\Domain\Model\User;
    use App\Domain\Model\UserRole;
    use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
    use Symfony\Component\HttpFoundation\JsonResponse;
    use Symfony\Component\HttpFoundation\Request;
    use Symfony\Component\HttpFoundation\Response;
    use Symfony\Component\Routing\Annotation\Route;
    use Symfony\Component\Serializer\SerializerInterface;
    use Symfony\Component\Uid\Uuid;
    use Symfony\Component\Validator\Constraints as Assert;
    use Symfony\Component\Validator\Validator\ValidatorInterface;

    class ApiController extends AbstractController
    {
        private ValidatorInterface $validator;
        private SerializerInterface $serializer;

        public function __construct(ValidatorInterface $validator, SerializerInterface $serializer)
        {
            $this->validator = $validator;
            $this->serializer = $serializer;
        }

        #[Route('/api/submit/user', methods: ['POST'])]
        public function handleUserSubmission(Request $request): JsonResponse
        {
            $payload = json_decode($request->getContent(), true);
            if (json_last_error() !== JSON_ERROR_NONE) {
                return new JsonResponse(['error' => 'Invalid JSON payload'], Response::HTTP_BAD_REQUEST);
            }

            // Define validation rules directly in the controller.
            $validationRules = new Assert\Collection([
                'fields' => [
                    'email' => [new Assert\NotBlank(), new Assert\Email()],
                    'password' => [new Assert\NotBlank(), new Assert\Length(['min' => 8])],
                    'role' => [new Assert\Optional(new Assert\Choice(['choices' => ['ADMIN', 'USER']]))],
                    'is_active' => [new Assert\Optional(new Assert\Type('bool'))],
                ],
                'allowExtraFields' => true,
            ]);

            $violations = $this->validator->validate($payload, $validationRules);

            if (count($violations) > 0) {
                $errorMessages = [];
                foreach ($violations as $violation) {
                    // Manually format a simple error message structure.
                    $field = trim($violation->getPropertyPath(), '[]' );
                    $errorMessages[$field][] = $violation->getMessage();
                }
                return new JsonResponse(['errors' => $errorMessages], Response::HTTP_UNPROCESSABLE_ENTITY);
            }

            // Manual type coercion/conversion for fields
            $role_str = $payload['role'] ?? 'USER';
            $role_enum = UserRole::from(strtolower($role_str));
            $is_active_bool = filter_var($payload['is_active'] ?? true, FILTER_VALIDATE_BOOLEAN);

            // --- Business Logic: Create User ---
            $mockUser = new User(
                Uuid::v4(),
                $payload['email'],
                'hashed_password_placeholder',
                $role_enum,
                $is_active_bool,
                new \DateTimeImmutable()
            );

            // Serialize the response using a specific group "user:read" to only expose public fields.
            // This prevents leaking sensitive data like role or active status.
            return $this->json(
                $mockUser,
                Response::HTTP_CREATED,
                [],
                ['groups' => 'user:read']
            );
        }
    }
}