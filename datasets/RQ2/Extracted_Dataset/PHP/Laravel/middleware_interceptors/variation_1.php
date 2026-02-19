<php
/**
 * Variation 1: The "By-the-Book" Developer
 * Style: Follows standard Laravel conventions closely. Each responsibility is in its own class.
 * Organization: Separate, single-purpose middleware classes.
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


// --- Middleware Implementations ---

// File: app/Http/Middleware/LogRequest.php
namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Log;

class LogRequest
{
    public function handle(Request $request, Closure $next)
    {
        $start = microtime(true);

        $response = $next($request);

        $end = microtime(true);
        $duration = ($end - $start) * 1000;

        Log::info('Request Handled', [
            'method' => $request->getMethod(),
            'url' => $request->fullUrl(),
            'ip' => $request->ip(),
            'status' => $response->getStatusCode(),
            'duration_ms' => round($duration, 2),
        ]);

        return $response;
    }
}

// File: app/Http/Middleware/HandleCors.php
namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;

class HandleCors
{
    public function handle(Request $request, Closure $next)
    {
        $response = $next($request);
        $response->headers->set('Access-Control-Allow-Origin', '*');
        $response->headers->set('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
        $response->headers->set('Access-Control-Allow-Headers', 'Content-Type, Authorization');

        if ($request->isMethod('OPTIONS')) {
            return response('', 204)->withHeaders($response->headers);
        }

        return $response;
    }
}

// File: app/Http/Middleware/TransformResponse.php
namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class TransformResponse
{
    public function handle(Request $request, Closure $next)
    {
        $response = $next($request);

        if ($response instanceof JsonResponse && $response->isSuccessful() && !isset($response->getData()->meta)) {
            $originalData = $response->getData(true);
            $response->setData([
                'data' => $originalData
            ]);
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
    /**
     * The application's global HTTP middleware stack.
     * These middleware are run during every request to your application.
     */
    protected $middleware = [
        \App\Http\Middleware\HandleCors::class,
        \Illuminate\Foundation\Http\Middleware\ValidatePostSize::class,
        \Illuminate\Foundation\Http\Middleware\ConvertEmptyStringsToNull::class,
    ];

    /**
     * The application's route middleware groups.
     */
    protected $middlewareGroups = [
        'web' => [
            // ...
        ],

        'api' => [
            \App\Http\Middleware\LogRequest::class,
            \Illuminate\Routing\Middleware\ThrottleRequests::class . ':api',
            \Illuminate\Routing\Middleware\SubstituteBindings::class,
            \App\Http\Middleware\TransformResponse::class,
        ],
    ];
}

// File: app/Exceptions/Handler.php (Snippet)
namespace App\Exceptions;

use Illuminate\Foundation\Exceptions\Handler as ExceptionHandler;
use Illuminate\Database\Eloquent\ModelNotFoundException;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;
use Throwable;

class Handler extends ExceptionHandler
{
    public function register()
    {
        $this->reportable(function (Throwable $e) {
            // Can send to Sentry, etc.
        });

        $this->renderable(function (NotFoundHttpException $e, $request) {
            if ($request->is('api/*')) {
                return response()->json([
                    'error' => 'Resource not found.'
                ], 404);
            }
        });

        $this->renderable(function (ModelNotFoundException $e, $request) {
            if ($request->is('api/*')) {
                return response()->json([
                    'error' => 'The requested record does not exist.'
                ], 404);
            }
        });

        $this->renderable(function (Throwable $e, $request) {
            if ($request->is('api/*')) {
                return response()->json([
                    'error' => 'An unexpected server error occurred.'
                ], 500);
            }
        });
    }
}
?>