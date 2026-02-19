<pre>
&lt;?php

namespace App\Variations\ByTheBook;

// --- STUB/MOCK SETUP ---
// The following classes and interfaces are mocks to make the example self-contained.
// In a real Laravel application, these would be provided by the framework.

namespace Illuminate\Foundation\Http;
use Illuminate\Http\Request;
use Illuminate\Contracts\Validation\Validator;
use Illuminate\Http\Exceptions\HttpResponseException;
use Illuminate\Http\JsonResponse;
class FormRequest extends Request {
    protected $validator;
    public function validated() { return ['email' => 'test@example.com', 'password' => 'password123', 'phone' => '+15551234567', 'is_active' => true]; }
    protected function failedValidation(Validator $validator) { throw new HttpResponseException(new JsonResponse(['message' => 'The given data was invalid.', 'errors' => $validator->errors()], 422)); }
}

namespace Illuminate\Http\Resources\Json;
class JsonResource {
    public $resource;
    public function __construct($resource) { $this->resource = $resource; }
    public static function collection($resource) { return new static($resource); }
    public function toArray($request) { return (array) $this->resource; }
    public function toResponse($request) { return new \Illuminate\Http\JsonResponse($this->toArray($request)); }
}

namespace Illuminate\Database\Eloquent;
use Illuminate\Support\Str;
class Model {
    protected $attributes = [];
    public function __construct(array $attributes = []) { $this->attributes = $attributes; if (!isset($this->attributes['id'])) $this->attributes['id'] = (string) Str::uuid(); }
    public static function create(array $attributes = []) { return new static($attributes); }
    public function __get($key) { return $this->attributes[$key] ?? null; }
    public function __set($key, $value) { $this->attributes[$key] = $value; }
    public function toArray() { return $this->attributes; }
}

namespace Illuminate\Contracts\Validation;
interface Rule { public function passes($attribute, $value); public function message(); }

namespace Illuminate\Support;
class Str { public static function uuid() { return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x', mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0x0fff) | 0x4000, mt_rand(0, 0x3fff) | 0x8000, mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)); } }
class Carbon extends \DateTime {}

namespace Spatie\ArrayToXml;
class ArrayToXml { public function __construct(array $array, $rootElement = 'root') { $this->array = $array; } public function toXml() { return '&lt;?xml version="1.0"?&gt;&lt;root&gt;&lt;id&gt;user-id&lt;/id&gt;&lt;email&gt;test@example.com&lt;/email&gt;&lt;/root&gt;'; } }

// --- APPLICATION CODE ---

namespace App\Variations\ByTheBook\Enums;

enum UserRole: string
{
    case ADMIN = 'admin';
    case USER = 'user';
}

enum PostStatus: string
{
    case DRAFT = 'draft';
    case PUBLISHED = 'published';
}

namespace App\Variations\ByTheBook\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Support\Carbon;
use App\Variations\ByTheBook\Enums\UserRole;

class User extends Model
{
    protected $fillable = [
        'id', 'email', 'password_hash', 'role', 'is_active',
    ];

    protected $casts = [
        'is_active' => 'boolean',
        'role' => UserRole::class,
        'created_at' => 'datetime',
    ];
}

namespace App\Variations\ByTheBook\Rules;

use Illuminate\Contracts\Validation\Rule;

class ValidPhoneNumber implements Rule
{
    public function passes($attribute, $value): bool
    {
        // Simple North American Numbering Plan check
        return preg_match('/^\+1[2-9]\d{2}[2-9]\d{2}\d{4}$/', $value) > 0;
    }

    public function message(): string
    {
        return 'The :attribute must be a valid North American phone number in E.164 format (e.g., +15551234567).';
    }
}

namespace App\Variations\ByTheBook\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rules\Enum;
use App\Variations\ByTheBook\Rules\ValidPhoneNumber;
use App\Variations\ByTheBook\Enums\UserRole;

class StoreUserRequest extends FormRequest
{
    public function authorize(): bool
    {
        return true; // In a real app, you'd check permissions here
    }

    public function rules(): array
    {
        return [
            'email' => ['required', 'email:rfc,dns', 'max:255'],
            'password' => ['required', 'string', 'min:8'],
            'phone' => ['nullable', 'string', new ValidPhoneNumber()],
            'role' => ['sometimes', new Enum(UserRole::class)],
            'is_active' => ['sometimes', 'boolean'],
        ];
    }

    public function messages(): array
    {
        return [
            'email.required' => 'An email address is required to create a user.',
            'password.min' => 'The password must be at least 8 characters long.',
        ];
    }

    protected function prepareForValidation()
    {
        // Example of type coercion before validation
        if ($this->has('is_active')) {
            $this->merge([
                'is_active' => filter_var($this->is_active, FILTER_VALIDATE_BOOLEAN),
            ]);
        }
    }
}

namespace App\Variations\ByTheBook\Http\Resources;

use Illuminate\Http\Resources\Json\JsonResource;
use Illuminate\Http\Request;

class UserResource extends JsonResource
{
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'email' => $this->email,
            'role' => $this->role,
            'is_active' => $this->is_active,
            'created_at' => $this->created_at->toIso8601String(),
        ];
    }
}

namespace App\Variations\ByTheBook\Services;

use App\Variations\ByTheBook\Models\User;
use Spatie\ArrayToXml\ArrayToXml;
use SimpleXMLElement;

class XmlProcessingService
{
    public function generateUserXml(User $user): string
    {
        $data = (new \App\Variations\ByTheBook\Http\Resources\UserResource($user))->toArray(request());
        return (new ArrayToXml($data, 'user'))->toXml();
    }

    public function parseUserData(string $xml): array
    {
        $xmlObject = new SimpleXMLElement($xml);
        return [
            'email' => (string) $xmlObject->email,
            'password' => (string) $xmlObject->password,
            'phone' => (string) $xmlObject->phone,
        ];
    }
}

namespace App\Variations\ByTheBook\Http\Controllers;

use Illuminate\Http\Request;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Response;
use App\Variations\ByTheBook\Models\User;
use App\Variations\ByTheBook\Http\Requests\StoreUserRequest;
use App\Variations\ByTheBook\Http\Resources\UserResource;
use App\Variations\ByTheBook\Services\XmlProcessingService;
use App\Variations\ByTheBook\Enums\UserRole;
use Illuminate\Support\Carbon;

class UserController
{
    protected XmlProcessingService $xmlService;

    public function __construct(XmlProcessingService $xmlService)
    {
        $this->xmlService = $xmlService;
    }

    /**
     * Store a new user from JSON input.
     */
    public function storeFromJson(StoreUserRequest $request): UserResource
    {
        $validatedData = $request->validated();

        $user = User::create([
            'email' => $validatedData['email'],
            'password_hash' => hash('sha256', $validatedData['password']),
            'role' => $validatedData['role'] ?? UserRole::USER,
            'is_active' => $validatedData['is_active'] ?? true,
            'created_at' => new Carbon(),
        ]);

        return new UserResource($user);
    }

    /**
     * Store a new user from XML input and return XML.
     */
    public function storeFromXml(Request $request): Response
    {
        // 1. Deserialize/Parse XML
        $userData = $this->xmlService->parseUserData($request->getContent());

        // 2. Manually create a request to use the FormRequest for validation
        $formRequest = StoreUserRequest::createFrom($request, $userData);
        $formRequest->setContainer(app())->validateResolved();
        $validatedData = $formRequest->validated();

        // 3. Create User
        $user = User::create([
            'email' => $validatedData['email'],
            'password_hash' => hash('sha256', $validatedData['password']),
            'role' => UserRole::USER,
            'is_active' => true,
            'created_at' => new Carbon(),
        ]);

        // 4. Serialize to XML
        $xmlResponse = $this->xmlService->generateUserXml($user);

        return new Response($xmlResponse, 201, ['Content-Type' => 'application/xml']);
    }
}

// --- EXAMPLE USAGE (for demonstration) ---
/*
$controller = new App\Variations\ByTheBook\Http\Controllers\UserController(new App\Variations\ByTheBook\Services\XmlProcessingService());

// JSON Example
$jsonRequest = new App\Variations\ByTheBook\Http\Requests\StoreUserRequest();
$jsonResponse = $controller->storeFromJson($jsonRequest);
echo $jsonResponse->toResponse(request())->getContent();
// Output: {"data":{"id":"...","email":"test@example.com","role":"user","is_active":true,"created_at":"..."}}

// XML Example
$xmlInput = '&lt;?xml version="1.0"?&gt;&lt;user&gt;&lt;email&gt;test@example.com&lt;/email&gt;&lt;password&gt;password123&lt;/password&gt;&lt;phone&gt;+15551234567&lt;/phone&gt;&lt;/user&gt;';
$xmlRequest = new \Illuminate\Http\Request([], [], [], [], [], [], $xmlInput);
$xmlResponse = $controller->storeFromXml($xmlRequest);
echo $xmlResponse->getContent();
// Output: &lt;?xml version="1.0"?&gt;&lt;user&gt;...&lt;/user&gt;
*/

</pre>