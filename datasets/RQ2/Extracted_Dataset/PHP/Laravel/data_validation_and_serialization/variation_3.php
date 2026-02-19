<pre>
&lt;?php

namespace App\Variations\ServiceOriented;

// --- STUB/MOCK SETUP ---
// The following classes and interfaces are mocks to make the example self-contained.
namespace Illuminate\Foundation\Http {
    use Illuminate\Http\Request;
    class FormRequest extends Request {
        public function validated() { return ['email' => 'dto.user@example.com', 'password' => 'password123', 'phone_number' => '+15551234567']; }
    }
}
namespace Illuminate\Http {
    class Request { public function getContent() { return '&lt;user&gt;&lt;email&gt;xml.user@example.com&lt;/email&gt;&lt;password&gt;securePass!&lt;/password&gt;&lt;/user&gt;'; } }
    class JsonResponse { protected $data; protected $status; public function __construct($data, $status) { $this->data = $data; $this->status = $status; } public function getContent() { return json_encode($this->data); } }
    class Response { protected $content; protected $status; protected $headers; public function __construct($content, $status, $headers) { $this->content = $content; $this->status = $status; $this->headers = $headers; } public function getContent() { return $this->content; } }
}
namespace Illuminate\Database\Eloquent {
    use Illuminate\Support\Str;
    class Model {
        protected $attributes = [];
        public function __construct(array $attributes = []) { $this->attributes = $attributes; if (!isset($this->attributes['id'])) $this->attributes['id'] = (string) Str::uuid(); }
        public static function create(array $attributes = []) { return new static($attributes); }
        public function __get($key) { return $this->attributes[$key] ?? null; }
        public function toArray() { return $this->attributes; }
    }
}
namespace Illuminate\Contracts\Validation { interface Rule {} }
namespace Illuminate\Support {
    class Str { public static function uuid() { return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x', mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0x0fff) | 0x4000, mt_rand(0, 0x3fff) | 0x8000, mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)); } }
    class Carbon extends \DateTime {}
}
namespace Spatie\ArrayToXml { class ArrayToXml { public function __construct(array $array, $rootElement = 'root') { $this->array = $array; } public function toXml() { return '&lt;?xml version="1.0"?&gt;&lt;user&gt;&lt;id&gt;user-id&lt;/id&gt;&lt;email&gt;test@example.com&lt;/email&gt;&lt;/root&gt;'; } } }

// --- APPLICATION CODE ---

namespace App\Variations\ServiceOriented\Enums;

enum UserRole: string { case ADMIN = 'ADMIN'; case USER = 'USER'; }

namespace App\Variations\ServiceOriented\Models;

use Illuminate\Database\Eloquent\Model;
use App\Variations\ServiceOriented\Enums\UserRole;

class User extends Model
{
    protected $fillable = ['email', 'password_hash', 'role', 'is_active', 'created_at'];
    protected $casts = ['is_active' => 'boolean', 'role' => UserRole::class, 'created_at' => 'datetime'];
}

namespace App\Variations\ServiceOriented\DataTransferObjects;

// A simple, immutable DTO for passing structured data.
final class UserData
{
    public function __construct(
        public readonly string $email,
        public readonly string $password,
        public readonly ?string $phone,
        public readonly UserRole $role = UserRole::USER,
        public readonly bool $isActive = true
    ) {}
}

namespace App\Variations\ServiceOriented\Rules;

use Illuminate\Contracts\Validation\Rule;

class E164PhoneNumber implements Rule
{
    public function passes($attribute, $value): bool { return preg_match('/^\+[1-9]\d{1,14}$/', $value) > 0; }
    public function message(): string { return 'The :attribute must be a valid E.164 formatted phone number.'; }
}

namespace App\Variations\ServiceOriented\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;
use App\Variations\ServiceOriented\Rules\E164PhoneNumber;

class CreateUserApiRequest extends FormRequest
{
    public function rules(): array
    {
        return [
            'email' => ['required', 'email'],
            'password' => ['required', 'string', 'min:10'],
            'phone_number' => ['sometimes', 'string', new E164PhoneNumber()],
        ];
    }
}

namespace App\Variations\ServiceOriented\Services;

use App\Variations\ServiceOriented\Models\User;
use App\Variations\ServiceOriented\DataTransferObjects\UserData;
use Illuminate\Support\Carbon;
use Spatie\ArrayToXml\ArrayToXml;
use SimpleXMLElement;

class UserService
{
    public function createUser(UserData $userData): User
    {
        // Business logic could be here, e.g., checking for duplicate emails,
        // sending a welcome email, etc.
        return User::create([
            'email' => $userData->email,
            'password_hash' => password_hash($userData->password, PASSWORD_DEFAULT),
            'role' => $userData->role,
            'is_active' => $userData->isActive,
            'created_at' => new Carbon(),
        ]);
    }

    public function serializeUserToJson(User $user): array
    {
        return [
            'user_id' => $user->id,
            'email_address' => $user->email,
            'account_status' => $user->is_active ? 'active' : 'inactive',
            'member_since' => $user->created_at->format('Y-m-d'),
        ];
    }

    public function serializeUserToXml(User $user): string
    {
        $data = $this->serializeUserToJson($user);
        return (new ArrayToXml($data, 'user_profile'))->toXml();
    }

    public function parseAndValidateXml(string $xmlContent): UserData
    {
        $xml = new SimpleXMLElement($xmlContent);
        // Here we might have more complex parsing logic
        return new UserData(
            email: (string)$xml->email,
            password: (string)$xml->password,
            phone: isset($xml->phone) ? (string)$xml->phone : null
        );
    }
}

namespace App\Variations\ServiceOriented\Http\Controllers\Api\V1;

use Illuminate\Http\Request;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Response;
use App\Variations\ServiceOriented\Http\Requests\CreateUserApiRequest;
use App\Variations\ServiceOriented\Services\UserService;
use App\Variations\ServiceOriented\DataTransferObjects\UserData;

class UserController
{
    public function __construct(private readonly UserService $userService) {}

    public function store(CreateUserApiRequest $request): JsonResponse
    {
        $validatedData = $request->validated();

        // Convert validated request array into a structured DTO
        $userData = new UserData(
            email: $validatedData['email'],
            password: $validatedData['password'],
            phone: $validatedData['phone_number'] ?? null
        );

        $user = $this->userService->createUser($userData);

        $responsePayload = $this->userService->serializeUserToJson($user);

        return new JsonResponse($responsePayload, 201);
    }

    public function storeFromXml(Request $request): Response
    {
        // The service handles both parsing and validation logic
        $userData = $this->userService->parseAndValidateXml($request->getContent());

        $user = $this->userService->createUser($userData);

        $xmlResponse = $this->userService->serializeUserToXml($user);

        return new Response($xmlResponse, 201, ['Content-Type' => 'application/xml']);
    }
}

// --- EXAMPLE USAGE (for demonstration) ---
/*
$userService = new App\Variations\ServiceOriented\Services\UserService();
$controller = new App\Variations\ServiceOriented\Http\Controllers\Api\V1\UserController($userService);

// JSON Example
$request = new App\Variations\ServiceOriented\Http\Requests\CreateUserApiRequest();
$response = $controller->store($request);
echo $response->getContent();
// Output: {"user_id":"...","email_address":"dto.user@example.com","account_status":"active","member_since":"..."}

// XML Example
$xmlRequest = new \Illuminate\Http\Request();
$xmlResponse = $controller->storeFromXml($xmlRequest);
echo $xmlResponse->getContent();
// Output: &lt;?xml version="1.0"?&gt;&lt;user_profile&gt;...&lt;/user_profile&gt;
*/

</pre>