<php
/**
 * Variation 3: The "Pragmatic/Functional" Developer
 * Style: Prefers closures for simple, route-specific middleware and combines related concerns.
 * Organization: A mix of a single "gateway" middleware class for common checks and inline closures in route files.
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


// --- Middleware Implementation ---

// File: app/Http/Middleware/ApiGateway.php
namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Log;
use Symfony\Component\HttpFoundation\Response;

class ApiGateway
{
    /**
     * A combined middleware for initial API request processing.
     */
    public function handle(Request $request, Closure $next): Response
    {
        // 1. Request Logging (simple version)
        Log::debug('API Request started', ['path' => $request->path(), 'ip' => $request->ip()]);

        // 2. Request Transformation/Validation (example: ensure API key header exists)
        if (!$request->hasHeader('X-API-KEY')) {
            return response()->json(['message' => 'API Key is missing.'], 401);
        }

        $response = $next($request);

        // 3. Add a custom header to all responses
        $response->headers->set('X-API-Version', 'v1.0');

        return $response;
    }
}


// --- Framework Integration ---

// File: routes/api.php (Snippet)
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Route;

Route::middleware([
    'throttle:api', // Built-in Rate Limiting
    \App\Http\Middleware\ApiGateway::class
])->prefix('v1')->group(function () {

    // 3. CORS Handling via closure middleware
    $corsMiddleware = function (Request $request, Closure $next) {
        return $next($request)
            ->header('Access-Control-Allow-Origin', 'https://example.com')
            ->header('Access-Control-Allow-Methods', 'GET, POST');
    };

    // 4. Response Transformation via closure middleware
    $responseWrapper = function (Request $request, Closure $next) {
        $response = $next($request);
        if ($response->isSuccessful() && $response instanceof \Illuminate\Http\JsonResponse) {
            $content = $response->getData();
            $response->setData(['status' => 'success', 'result' => $content]);
        }
        return $response;
    };

    Route::middleware([$corsMiddleware, $responseWrapper])->group(function () {
        Route::get('/posts', function () {
            return response()->json([['id' => 1, 'title' => 'My First Post']]);
        });
        Route::get('/users', function () {
            return response()->json([['id' => 1, 'email' => 'test@example.com']]);
        });
    });
});


// File: app/Http/Kernel.php (Snippet)
namespace App\Http;

use Illuminate\Foundation\Http\Kernel as HttpKernel;

class Kernel extends HttpKernel
{
    // Global middleware are minimal
    protected $middleware = [
        \Illuminate\Foundation\Http\Middleware\TrustProxies::class,
    ];

    // Aliases can be used for the gateway
    protected $routeMiddleware = [
        'api.gateway' => \App\Http\Middleware\ApiGateway::class,
        'throttle' => \Illuminate\Routing\Middleware\ThrottleRequests::class,
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
        // 5. Error Handling
        $this->renderable(function (Throwable $e, $request) {
            if ($request->is('api/*')) {
                $status = 500;
                if ($e instanceof \Illuminate\Auth\AuthenticationException) {
                    $status = 401;
                } elseif ($e instanceof \Illuminate\Validation\ValidationException) {
                    $status = 422;
                }

                return response()->json([
                    'status' => 'error',
                    'message' => $e->getMessage(),
                    'file' => config('app.debug') ? $e->getFile() : null,
                    'line' => config('app.debug') ? $e->getLine() : null,
                ], $status);
            }
        });
    }
}
?>