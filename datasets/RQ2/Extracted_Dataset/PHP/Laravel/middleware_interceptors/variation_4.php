<php
/**
 * Variation 4: The "Pattern-Heavy/Enterprise" Developer
 * Style: Uses invokable classes, interfaces, and factories for maximum structure and testability.
 * Organization: Follows SOLID principles, using contracts (interfaces) and dependency injection heavily.
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


// --- Contracts and Factories ---

// File: app/Support/ApiResponseFactory.php
namespace App\Support;

use Illuminate\Http\JsonResponse;
use Symfony\Component\HttpFoundation\Response;

class ApiResponseFactory
{
    public static function success($data = [], int $status = Response::HTTP_OK): JsonResponse
    {
        return new JsonResponse([
            'success' => true,
            'data' => $data,
        ], $status);
    }

    public static function error(string $message, int $status, ?array $errors = null): JsonResponse
    {
        $payload = [
            'success' => false,
            'message' => $message,
        ];
        if ($errors) {
            $payload['errors'] = $errors;
        }
        return new JsonResponse($payload, $status);
    }
}


// --- Middleware Implementations (Invokable) ---

// File: app/Http/Middleware/RequestLifecycleLogger.php
namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Illuminate\Log\Logger;

class RequestLifecycleLogger
{
    private $logger;

    public function __construct(Logger $logger)
    {
        $this->logger = $logger;
    }

    public function __invoke(Request $request, Closure $next)
    {
        $this->logger->info("Request starting: {$request->method()} {$request->path()}");
        $response = $next($request);
        $this->logger->info("Request finished with status: {$response->getStatusCode()}");
        return $response;
    }
}

// File: app/Http/Middleware/ApplyCorsPolicy.php
namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;

class ApplyCorsPolicy
{
    public function __invoke(Request $request, Closure $next)
    {
        return $next($request)
            ->header('Access-Control-Allow-Origin', '*')
            ->header('Access-Control-Allow-Methods', 'GET, POST, PUT, PATCH, DELETE, OPTIONS')
            ->header('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-Requested-With');
    }
}

// File: app/Http/Middleware/EnforceApiJsonResponse.php
namespace App\Http\Middleware;

use App\Support\ApiResponseFactory;
use Closure;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class EnforceApiJsonResponse
{
    public function __invoke(Request $request, Closure $next)
    {
        $response = $next($request);

        // If it's already a structured JsonResponse from our factory, pass it through.
        if ($response instanceof JsonResponse && isset($response->getData()->success)) {
            return $response;
        }

        // Otherwise, wrap it.
        if ($response instanceof JsonResponse && $response->isSuccessful()) {
            return ApiResponseFactory::success($response->getData(true));
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
        'api' => [
            \App\Http\Middleware\RequestLifecycleLogger::class,
            \App\Http\Middleware\ApplyCorsPolicy::class,
            \Illuminate\Routing\Middleware\ThrottleRequests::class . ':api', // Rate Limiting
            \Illuminate\Routing\Middleware\SubstituteBindings::class,
            \App\Http\Middleware\EnforceApiJsonResponse::class,
        ],
    ];
}

// File: app/Exceptions/Handler.php (Snippet)
namespace App\Exceptions;

use App\Support\ApiResponseFactory;
use Illuminate\Foundation\Exceptions\Handler as ExceptionHandler;
use Illuminate\Validation\ValidationException;
use Symfony\Component\HttpKernel\Exception\HttpException;
use Throwable;

class Handler extends ExceptionHandler
{
    public function register()
    {
        $this->renderable(function (Throwable $e, $request) {
            if ($request->is('api/*')) {
                if ($e instanceof ValidationException) {
                    return ApiResponseFactory::error(
                        $e->getMessage(),
                        $e->status,
                        $e->errors()
                    );
                }

                if ($e instanceof HttpException) {
                    return ApiResponseFactory::error(
                        $e->getMessage() ?: 'Request Error',
                        $e->getStatusCode()
                    );
                }

                // Default server error
                return ApiResponseFactory::error(
                    config('app.debug') ? $e->getMessage() : 'Internal Server Error',
                    500
                );
            }
        });
    }
}
?>