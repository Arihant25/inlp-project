<pre>
&lt;?php

namespace App\Variations\Modern;

// --- STUB/MOCK SETUP ---
// The following classes and interfaces are mocks to make the example self-contained.
namespace Illuminate\Foundation\Http {
    use Illuminate\Http\Request;
    class FormRequest extends Request {
        public function validated() { return ['email' => 'test@example.com', 'password' => 'password123', 'phone' => '+15551234567', 'is_active' => true, 'role' => 'USER']; }
    }
}
namespace Illuminate\Http {
    class Request {
        protected $content;
        public function __construct($content = '') { $this->content = $content; }
        public function getContent() { return $this->content; }
        public function expectsJson() { return false; }
    }
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
namespace Illuminate\Contracts\Support { interface Responsable { public function toResponse($request); } }
namespace Illuminate\Support {
    class Str { public static function uuid() { return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x', mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0x0fff) | 0x4000, mt_rand(0, 0x3fff) | 0x8000, mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)); } }
    class Carbon extends \DateTime {}
}
namespace Spatie\ArrayToXml { class ArrayToXml { public function __construct(array $array, $rootElement = 'root') { $this->array = $array; } public function toXml() { return '&lt;?xml version="1.0"?&gt;&lt;user&gt;&lt;id&gt;user-id&lt;/id&gt;&lt;email&gt;test@example.com&lt;/email&gt;&lt;/user&gt;'; } } }

// --- APPLICATION CODE ---

// Using PHP 8.1+ backed Enums for strong typing
namespace App\Variations\Modern\Enums;

enum UserRole: string
{
    case ADMIN = 'ADMIN';
    case USER = 'USER';
}

enum PostStatus: string
{
    case DRAFT = 'DRAFT';
    case PUBLISHED = 'PUBLISHED';
}

namespace App\Variations\Modern\Models;

use Illuminate\Database\Eloquent\Model;
use App\Variations\Modern\Enums\UserRole;

class User extends Model
{
    protected $fillable = ['id', 'email', 'password_hash', 'role', 'is_active', 'created_at'];

    // Laravel 9+ Enum casting
    protected $casts = [
        'is_active' => 'boolean',
        'role' => UserRole::class,
        'created_at' => 'immutable_datetime',
    ];
}

namespace App\Variations\Modern\Data;

use App\Variations\Modern\Enums\UserRole;
use App\Variations\Modern\Http\Requests\ProcessUserRequest;
use SimpleXMLElement;

// A more advanced DTO with a static factory for construction and type safety.
readonly class CreateUserDto
{
    public function __construct(
        public string $email,
        public string $password,
        public UserRole $role,
        public bool $isActive,
        public ?string $phone
    ) {}

    public static function fromRequest(ProcessUserRequest $request): self
    {
        $validated = $request->validated();
        return new self(
            email: $validated['email'],
            password: $validated['password'],
            role: UserRole::tryFrom($validated['role']) ?? UserRole::USER,
            isActive: $validated['is_active'] ?? true,
            phone: $validated['phone'] ?? null
        );
    }

    public static function fromXml(string $xmlString): self
    {
        $xml = new SimpleXMLElement($xmlString);
        // Type coercion happens here during object construction
        return new self(
            email: (string)$xml->email,
            password: (string)$xml->password,
            role: UserRole::tryFrom((string)$xml->role) ?? UserRole::USER,
            isActive: filter_var((string)$xml->is_active, FILTER_VALIDATE_BOOLEAN),
            phone: (string)($xml->phone ?? null)
        );
    }
}

namespace App\Variations\Modern\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rules\Enum;
use Illuminate\Validation\Rules\Password;
use App\Variations\Modern\Enums\UserRole;

class ProcessUserRequest extends FormRequest
{
    public function rules(): array
    {
        return [
            'email' => ['required', 'email:filter'],
            'password' => ['required', Password::min(8)->mixedCase()->numbers()],
            'role' => ['sometimes', 'string', new Enum(UserRole::class)],
            'is_active' => ['sometimes', 'boolean'],
            'phone' => ['nullable', 'string', 'regex:/^\+[1-9]\d{1,14}$/'],
        ];
    }

    public function messages(): array
    {
        return [
            'phone.regex' => 'The phone number must be in E.164 format.',
        ];
    }
}

namespace App\Variations\Modern\Http\Resources;

use Illuminate\Contracts\Support\Responsable;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Spatie\ArrayToXml\ArrayToXml;

// This resource can respond with JSON or XML based on the request.
class UserResponse implements Responsable
{
    public function __construct(private readonly \App\Variations\Modern\Models\User $user, private int $statusCode = 200) {}

    public function toResponse($request): JsonResponse|Response
    {
        $data = $this->transform();

        if ($request->expectsJson()) {
            return new JsonResponse(['data' => $data], $this->statusCode);
        }

        $xml = ArrayToXml::convert($data, 'user');
        return new Response($xml, $this->statusCode, ['Content-Type' => 'application/xml']);
    }

    private function transform(): array
    {
        return [
            'uuid' => $this->user->id,
            'email' => $this->user->email,
            'role' => $this->user->role->value,
            'active' => $this->user->is_active,
            'registered_at' => $this->user->created_at->toAtomString(),
        ];
    }
}

namespace App\Variations\Modern\Http\Controllers;

use App\Variations\Modern\Data\CreateUserDto;
use App\Variations\Modern\Http\Requests\ProcessUserRequest;
use App\Variations\Modern\Http\Resources\UserResponse;
use App\Variations\Modern\Models\User;
use Illuminate\Http\Request;
use Illuminate\Support\Carbon;

// An invokable controller for a single, focused action.
class ProcessUserController
{
    public function __invoke(Request $request): UserResponse
    {
        if ($request->expectsJson()) {
            // For JSON, we can use the FormRequest for validation.
            $formRequest = ProcessUserRequest::createFrom($request);
            $dto = CreateUserDto::fromRequest($formRequest);
        } else {
            // For XML, we parse first, then can manually validate if needed.
            $dto = CreateUserDto::fromXml($request->getContent());
            // In a real app, you'd run validation on the DTO's properties here.
        }

        $user = User::create([
            'email' => $dto->email,
            'password_hash' => hash('sha256', $dto->password),
            'role' => $dto->role,
            'is_active' => $dto->isActive,
            'created_at' => new Carbon(),
        ]);

        return new UserResponse($user, 201);
    }
}

// --- EXAMPLE USAGE (for demonstration) ---
/*
$controller = new App\Variations\Modern\Http\Controllers\ProcessUserController();

// XML Example
$xmlInput = '&lt;user&gt;&lt;email&gt;xml.user@example.com&lt;/email&gt;&lt;password&gt;Password123&lt;/password&gt;&lt;role&gt;ADMIN&lt;/role&gt;&lt;is_active&gt;false&lt;/is_active&gt;&lt;/user&gt;';
$xmlRequest = new \Illuminate\Http\Request($xmlInput);
$response = $controller($xmlRequest);
echo $response->getContent();
// Output: &lt;?xml version="1.0"?&gt;&lt;user&gt;&lt;uuid&gt;...&lt;/uuid&gt;...&lt;/user&gt;

// JSON Example (would require mocking expectsJson() to true)
// $jsonRequest = new ProcessUserRequest(); // Assume it's populated
// $response = $controller($jsonRequest);
// echo $response->getContent();
// Output: {"data":{"uuid":"...","email":"...","role":"ADMIN","active":false,"registered_at":"..."}}
*/

</pre>