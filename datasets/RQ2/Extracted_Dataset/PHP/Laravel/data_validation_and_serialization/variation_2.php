<pre>
&lt;?php

namespace App\Variations\AllInController;

// --- STUB/MOCK SETUP ---
// The following classes and interfaces are mocks to make the example self-contained.
namespace Illuminate\Http\Request {
    class Request {
        protected $json;
        public function __construct($jsonPayload = null) { $this->json = $jsonPayload; }
        public function json($key = null, $default = null) { return $this->json; }
        public function all() { return (array) $this->json; }
        public function getContent() { return '&lt;data&gt;&lt;email&gt;xml.user@example.com&lt;/email&gt;&lt;pass&gt;securePass!&lt;/pass&gt;&lt;/data&gt;'; }
    }
}
namespace Illuminate\Http\JsonResponse { class JsonResponse { protected $data; protected $status; public function __construct($data, $status) { $this->data = $data; $this->status = $status; } public function getContent() { return json_encode($this->data); } } }
namespace Illuminate\Http\Response { class Response { protected $content; protected $status; protected $headers; public function __construct($content, $status, $headers) { $this->content = $content; $this->status = $status; $this->headers = $headers; } public function getContent() { return $this->content; } } }
namespace Illuminate\Support\Facades {
    class Validator {
        public static function make(array $data, array $rules, array $messages = []) {
            // A simplified mock validator
            $errors = [];
            if (empty($data['email'])) $errors['email'][] = $messages['email.required'] ?? 'The email field is required.';
            if (strlen($data['password']) &lt; 8) $errors['password'][] = $messages['password.min'] ?? 'The password must be at least 8 characters.';
            return new class($errors) {
                public $errors;
                public function __construct($errors) { $this->errors = $errors; }
                public function fails() { return !empty($this->errors); }
                public function errors() { return $this->errors; }
                public function validated() { return ['email' => 'validated@example.com', 'password' => 'validated_password']; }
            };
        }
    }
}
namespace Illuminate\Database\Eloquent {
    use Illuminate\Support\Str;
    class Model {
        protected $attributes = [];
        public function __construct(array $attributes = []) { $this->attributes = $attributes; if (!isset($this->attributes['id'])) $this->attributes['id'] = (string) Str::uuid(); }
        public static function create(array $attributes = []) { return new static($attributes); }
        public function __get($key) { return $this->attributes[$key] ?? null; }
        public function only(array $keys) { return array_intersect_key($this->attributes, array_flip($keys)); }
    }
}
namespace Illuminate\Support {
    class Str { public static function uuid() { return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x', mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0x0fff) | 0x4000, mt_rand(0, 0x3fff) | 0x8000, mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)); } }
    class Carbon extends \DateTime {}
}

// --- APPLICATION CODE ---

namespace App\Variations\AllInController\Models;

use Illuminate\Database\Eloquent\Model;

class User extends Model
{
    protected $fillable = [
        'id', 'email', 'password_hash', 'role', 'is_active', 'created_at'
    ];
}

namespace App\Variations\AllInController\Http\Controllers;

use Illuminate\Http\Request\Request;
use Illuminate\Http\JsonResponse\JsonResponse;
use Illuminate\Http\Response\Response;
use Illuminate\Support\Facades\Validator;
use App\Variations\AllInController\Models\User;
use Illuminate\Support\Carbon;
use Closure;
use SimpleXMLElement;
use DOMDocument;

class LegacyUserController
{
    /**
     * Create a user. All logic is self-contained in this method.
     */
    public function createUser(Request $req)
    {
        // --- Type Coercion & Pre-validation Logic ---
        $data = $req->all();
        $data['is_active'] = isset($data['is_active']) ? (bool)$data['is_active'] : true;

        // --- Custom Validator (as a closure) & Standard Validation ---
        $validator = Validator::make($data, [
            'email' => 'required|email',
            'password' => 'required|string|min:8',
            'phone' => [
                'nullable',
                'string',
                // Custom validator implemented as a closure
                function (string $attribute, mixed $value, Closure $fail) {
                    if (!preg_match('/^\+1[2-9]\d{2}[2-9]\d{2}\d{4}$/', $value)) {
                        $fail("The {$attribute} format is invalid for NA numbers.");
                    }
                },
            ],
            'is_active' => 'boolean',
        ], [
            // --- Custom Error Messages ---
            'email.required' => 'We need to know your email address!',
            'password.min' => 'Your password is too short, must be at least 8 characters.',
        ]);

        if ($validator->fails()) {
            return new JsonResponse(['errors' => $validator->errors()], 422);
        }

        $validated_data = $validator->validated();

        // --- Model Creation ---
        $u = User::create([
            'email' => $validated_data['email'],
            'password_hash' => hash('sha256', $validated_data['password']),
            'role' => 'USER',
            'is_active' => $data['is_active'],
            'created_at' => new Carbon(),
        ]);

        // --- JSON Serialization (manual) ---
        $response_data = $u->only(['id', 'email', 'role', 'is_active']);

        return new JsonResponse($response_data, 201);
    }

    /**
     * Process an XML payload to create a user.
     */
    public function processXmlUser(Request $req)
    {
        // --- XML Parsing (Deserialization) ---
        $xml_string = $req->getContent();
        try {
            $xml = new SimpleXMLElement($xml_string);
            $email = (string)$xml->email;
            $password = (string)$xml->pass; // Note the different field name 'pass'
        } catch (\Exception $e) {
            return new Response('&lt;error&gt;Invalid XML format&lt;/error&gt;', 400, ['Content-Type' => 'application/xml']);
        }

        // --- Validation (re-using Validator facade) ---
        $v = Validator::make(['email' => $email, 'password' => $password], ['email' => 'required|email', 'password' => 'required|min:8']);
        if ($v->fails()) {
            // --- XML Error Generation (manual) ---
            $doc = new DOMDocument('1.0');
            $root = $doc->createElement('errors');
            $doc->appendChild($root);
            foreach ($v->errors() as $field => $messages) {
                foreach ($messages as $message) {
                    $fieldNode = $doc->createElement($field, htmlspecialchars($message));
                    $root->appendChild($fieldNode);
                }
            }
            return new Response($doc->saveXML(), 422, ['Content-Type' => 'application/xml']);
        }

        // --- Model Creation ---
        $user = User::create([
            'email' => $email,
            'password_hash' => hash('sha256', $password),
            'role' => 'USER',
            'is_active' => true,
            'created_at' => new Carbon(),
        ]);

        // --- XML Generation (Serialization) ---
        $doc = new DOMDocument('1.0');
        $root = $doc->createElement('user');
        $doc->appendChild($root);
        $root->appendChild($doc->createElement('id', $user->id));
        $root->appendChild($doc->createElement('email_address', $user->email)); // Note different output field name
        $root->appendChild($doc->createElement('status', 'created'));

        return new Response($doc->saveXML(), 201, ['Content-Type' => 'application/xml']);
    }
}

// --- EXAMPLE USAGE (for demonstration) ---
/*
$controller = new App\Variations\AllInController\Http\Controllers\LegacyUserController();

// JSON Example
$jsonPayload = new \stdClass();
$jsonPayload->email = 'test.user@example.com';
$jsonPayload->password = 'a-very-long-password';
$jsonPayload->phone = '+15558675309';
$request = new App\Variations\AllInController\Http\Request\Request($jsonPayload);
$response = $controller->createUser($request);
echo $response->getContent();
// Output: {"id":"...","email":"test.user@example.com","role":"USER","is_active":true}

// XML Example
$xmlRequest = new App\Variations\AllInController\Http\Request\Request();
$xmlResponse = $controller->processXmlUser($xmlRequest);
echo $xmlResponse->getContent();
// Output: &lt;?xml version="1.0"?&gt;&lt;user&gt;&lt;id&gt;...&lt;/id&gt;&lt;email_address&gt;xml.user@example.com&lt;/email_address&gt;&lt;status&gt;created&lt;/status&gt;&lt;/user&gt;
*/

</pre>