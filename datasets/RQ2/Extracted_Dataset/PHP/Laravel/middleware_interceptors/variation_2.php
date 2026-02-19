<php
/**
 * Variation 2: The "Service-Oriented" Developer
 * Style: Prefers thin middleware that delegate logic to dedicated, injectable services.
 * Organization: Middleware classes depend on services for core logic, promoting reusability.
 */

// --- Mock Domain Model (for context and type-hinting) ---

namespace App\Enums;

enum UserRole: string {
    case ADMIN = 'admin';
    case USER = 'user';
}

enum PostStatus: string {
    case DRAFT = 'draft';
    case PUBLISHED = 'published';
}

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use App\Enums\UserRole;

class User extends Model {
    protected $casts = ['role' => UserRole::class, 'is_active' => 'boolean'];
}

class Post extends Model {
    // Model content
}


// --- Service Layer ---

// File: app/Services/RequestLoggingService.php
namespace App\Services;

use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\Log;

class RequestLoggingService
{
    public function log(Request $request, Response $response, float $startTime): void
    {
        $duration = (microtime(true) - $startTime) * 1000;
        Log::channel('daily')->info('API Request', [
            'method' => $request->method(),
            'uri' => $request->getUri(),
            'ip' => $request->ip(),
            'status_code' => $response->getStatusCode(),
            'duration_ms' => $duration,
        ]);
    }
}

// File: app/Services/ApiResponseTransformer.php
namespace App\Services;

use Illuminate\Http\JsonResponse;

class ApiResponseTransformer
{
    public function transform(JsonResponse $response): JsonResponse
    {
        if ($response->isSuccessful()) {
            $originalData = $response->getData(true);
            // Avoid re-wrapping if already structured
            if (!array_key_exists('payload', $originalData) && !array_key_exists('error', $originalData)) {
                return new JsonResponse(['payload' => $originalData]);
            }
        }
        return $response;
    }
}


// --- Middleware Implementations ---

// File: app/Http/Middleware/ApiLoggingMiddleware.php
namespace App\Http\Middleware;

use App\Services\RequestLoggingService;
use Closure;
use Illuminate\Http\Request;

class ApiLoggingMiddleware
{
    protected $loggerService;

    public function __construct(RequestLoggingService $loggerService)
    {
        $this->loggerService = $loggerService;
    }

    public function handle(Request $request, Closure $next)
    {
        $startTime = microtime(true);
        $response = $next($request);
        $this->loggerService->log($request, $response, $startTime);
        return $response;
    }
}

// File: app/Http/Middleware/JsonResponseTransformerMiddleware.php
namespace App\Http\Middleware;

use App\Services\ApiResponseTransformer;
use Closure;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class JsonResponseTransformerMiddleware
{
    protected $transformer;

    public function __construct(ApiResponseTransformer $transformer)
    {
        $this->transformer = $transformer;
    }

    public function handle(Request $request, Closure $next)
    {
        $response = $next($request);

        if ($response instanceof JsonResponse) {
            return $this->transformer->transform($response);
        }

        return $response;
    }
}


// --- Framework Integration ---

// File: app/Http/Kernel.php (Snippet)
namespace App\Http;

use Illuminate\Foundation\Http\Kernel as HttpKernel;

class Kernel extends HttpKernel
{
    protected $middlewareGroups = [
        'web' => [
            // ...
        ],
        'api' => [
            \App\Http\Middleware\ApiLoggingMiddleware::class,
            'throttle:60,1', // Rate limiting
            \Illuminate\Routing\Middleware\SubstituteBindings::class,
            \App\Http\Middleware\JsonResponseTransformerMiddleware::class,
        ],
    ];

    // CORS can be handled by a package like fruitcake/laravel-cors, configured via config/cors.php
    // Or a simple global middleware.
    protected $middleware = [
         \Fruitcake\Cors\HandleCors::class, // Assuming package is installed
         // ... other global middleware
    ];
}

// File: app/Exceptions/Handler.php (Snippet)
namespace App\Exceptions;

use Illuminate\Foundation\Exceptions\Handler as ExceptionHandler;
use Throwable;

class Handler extends ExceptionHandler
{
    public function register()
    {
        $this->renderable(function (\Exception $e, $request) {
            if ($request->wantsJson()) {
                $statusCode = method_exists($e, 'getStatusCode') ? $e->getStatusCode() : 500;
                return response()->json([
                    'error' => [
                        'message' => $e->getMessage() ?: 'Server Error',
                        'code' => $statusCode,
                    ]
                ], $statusCode);
            }
        });
    }
}
?>