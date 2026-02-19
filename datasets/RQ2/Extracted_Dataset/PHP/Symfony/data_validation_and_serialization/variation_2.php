<?php

// This variation represents a "service-oriented" developer who prefers explicit control.
// Logic is encapsulated in a dedicated service layer. The controller is thin and acts as a delegator.
// This example handles XML input and output, manually invoking the serializer and validator.
// Error messages are formatted manually for a custom XML error structure.

// --- Mocks and Stubs for self-containment ---
namespace App\Model {
    use Symfony\Component\Serializer\Annotation\SerializedName;
    enum UserRole: string { case ADMIN = 'admin'; case USER = 'user'; }
    // DTO for XML data. Note the SerializedName attributes for mapping.
    class UserRegistrationData {
        #[SerializedName("email_address")]
        public string $email;
        #[SerializedName("secret_key")]
        public string $password;
        #[SerializedName("access_level")]
        public UserRole $role;
    }
}

namespace App\Validator {
    use Symfony\Component\Validator\Constraint;
    #[\Attribute]
    class StrongPassword extends Constraint {
        public string $message = 'Password is not strong enough. It must contain at least one uppercase letter, one lowercase letter, and one number.';
    }

    use Symfony\Component\Validator\ConstraintValidator;
    class StrongPasswordValidator extends ConstraintValidator {
        public function validate(mixed $value, Constraint $constraint): void {
            if (null === $value || '' === $value) { return; }
            if (!preg_match('/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).+$/', $value)) {
                $this->context->buildViolation($constraint->message)->addViolation();
            }
        }
    }
}

namespace App\Service {
    use App\Model\UserRegistrationData;
    use App\Validator\StrongPassword;
    use Symfony\Component\Validator\Constraints as Assert;
    use Symfony\Component\Validator\Validator\ValidatorInterface;
    use Symfony\Component\Validator\ConstraintViolationListInterface;

    class RegistrationService
    {
        public function __construct(
            private readonly ValidatorInterface $validator
        ) {}

        public function validateRegistrationData(UserRegistrationData $data): ConstraintViolationListInterface
        {
            $constraints = new Assert\Collection([
                'email' => [new Assert\NotBlank(), new Assert\Email()],
                'password' => [new Assert\NotBlank(), new Assert\Length(['min' => 10]), new StrongPassword()],
                'role' => [new Assert\NotNull()],
            ]);

            // We validate the object's public properties against the constraint collection.
            return $this->validator->validate((array) $data, $constraints);
        }

        public function formatErrorsAsXmlArray(ConstraintViolationListInterface $violations): array
        {
            $errors = [];
            foreach ($violations as $violation) {
                $errors[] = [
                    'field' => trim($violation->getPropertyPath(), '[]'),
                    'message' => $violation->getMessage()
                ];
            }
            return ['error' => $errors];
        }
    }
}

namespace App\Controller {
    use App\Model\UserRegistrationData;
    use App\Service\RegistrationService;
    use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
    use Symfony\Component\HttpFoundation\Request;
    use Symfony\Component\HttpFoundation\Response;
    use Symfony\Component\Routing\Annotation\Route;
    use Symfony\Component\Serializer\Encoder\XmlEncoder;
    use Symfony\Component\Serializer\SerializerInterface;

    class RegistrationController extends AbstractController
    {
        public function __construct(
            private readonly RegistrationService $registrationService,
            private readonly SerializerInterface $serializer
        ) {}

        #[Route('/api/register.xml', methods: ['POST'])]
        public function handleXmlRegistration(Request $request): Response
        {
            try {
                /** @var UserRegistrationData $userData */
                $userData = $this->serializer->deserialize(
                    $request->getContent(),
                    UserRegistrationData::class,
                    'xml'
                );
            } catch (\Exception $e) {
                $errorXml = $this->serializer->serialize(['error' => 'Invalid XML format.'], 'xml', ['xml_root_node_name' => 'response']);
                return new Response($errorXml, Response::HTTP_BAD_REQUEST, ['Content-Type' => 'application/xml']);
            }

            $violations = $this->registrationService->validateRegistrationData($userData);

            if (count($violations) > 0) {
                $errorsArray = $this->registrationService->formatErrorsAsXmlArray($violations);
                $errorXml = $this->serializer->serialize($errorsArray, 'xml', [
                    XmlEncoder::ROOT_NODE_NAME => 'response',
                    XmlEncoder::ENCODING => 'utf-8'
                ]);
                return new Response($errorXml, Response::HTTP_UNPROCESSABLE_ENTITY, ['Content-Type' => 'application/xml']);
            }

            // --- Business Logic: Create User ---
            // $user = $this->userService->createFromDto($userData);

            $responseData = [
                'status' => 'success',
                'message' => 'User registered successfully.',
                'user' => ['email' => $userData->email, 'assigned_role' => $userData->role->value]
            ];

            $responseXml = $this->serializer->serialize($responseData, 'xml', [
                XmlEncoder::ROOT_NODE_NAME => 'response',
                XmlEncoder::ENCODING => 'utf-8'
            ]);

            return new Response($responseXml, Response::HTTP_CREATED, ['Content-Type' => 'application/xml']);
        }
    }
}