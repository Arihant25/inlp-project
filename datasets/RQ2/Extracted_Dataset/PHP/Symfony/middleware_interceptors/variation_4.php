<?php

namespace App\Variation4;

use Psr\Log\LoggerInterface;
use Symfony\Component\EventDispatcher\EventSubscriberInterface;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Event\ExceptionEvent;
use Symfony\Component\HttpKernel\Event\RequestEvent;
use Symfony\Component\HttpKernel\Event\ResponseEvent;
use Symfony\Component\HttpKernel\Event\ViewEvent;
use Symfony\Component\HttpKernel\Exception\HttpException;
use Symfony\Component\HttpKernel\KernelEvents;
use Symfony\Component\RateLimiter\RateLimiterFactory;
use Symfony\Component\RateLimiter\Exception\RateLimitExceededException;

// --- Domain Schema Mocks ---
namespace App\Variation4\Domain\Enum {
    enum UserRole { case ADMIN; case USER; }
    enum PostStatus { case DRAFT; case PUBLISHED; }
}

// --- Service-Oriented Implementation: Services ---
namespace App\Variation4\Service {
    use Psr\Log\LoggerInterface;
    use Symfony\Component\HttpFoundation\Request;
    use Symfony\Component\HttpFoundation\Response;
    use Symfony\Component\HttpKernel\Exception\HttpException;
    use Symfony\Component\RateLimiter\RateLimiterFactory;
    use Symfony\Component\RateLimiter\Exception\RateLimitExceededException;
    use Throwable;

    class RequestLoggerService {
        public function __construct(private LoggerInterface $logger) {}
        public function log(Request $request): void {
            $this->logger->info('API Request', ['method' => $request->getMethod(), 'uri' => $request->getUri()]);
        }
    }

    class CorsHandlerService {
        public function handlePreflight(Request $request): ?Response {
            if ($request->isMethod('OPTIONS')) {
                return new Response(null, Response::HTTP_NO_CONTENT);
            }
            return null;
        }
        public function addHeaders(Response $response): void {
            $response->headers->set('Access-Control-Allow-Origin', '*');
            $response->headers->set('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
            $response->headers->set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
        }
    }

    class ApiRateLimiterService {
        public function __construct(private RateLimiterFactory $limiterFactory) {}
        public function consume(Request $request): void {
            try {
                $limiter = $this->limiterFactory->create($request->getClientIp());
                $limiter->consume(1)->ensureAccepted();
            } catch (RateLimitExceededException $e) {
                throw new HttpException(Response::HTTP_TOO_MANY_REQUESTS, 'API rate limit exceeded.', $e);
            }
        }
    }

    class JsonTransformerService {
        public function transformRequest(Request $request): void {
            if (str_contains($request->headers->get('Content-Type', ''), 'application/json')) {
                $data = json_decode($request->getContent(), true);
                if (is_array($data)) {
                    $request->request->replace($data);
                }
            }
        }
        public function transformResponse($controllerResult): JsonResponse {
            return new JsonResponse(['data' => $controllerResult]);
        }
    }

    class ApiErrorHandlerService {
        public function __construct(private LoggerInterface $logger) {}
        public function handleException(Throwable $exception): JsonResponse {
            $statusCode = $exception instanceof HttpException ? $exception->getStatusCode() : 500;
            $this->logger->error('API Exception handled', ['exception' => $exception]);
            return new JsonResponse(
                ['error' => ['message' => $exception->getMessage()]],
                $statusCode
            );
        }
    }
}

// --- Service-Oriented Implementation: Subscriber ---
namespace App\Variation4\EventSubscriber {
    use App\Variation4\Service\ApiErrorHandlerService;
    use App\Variation4\Service\ApiRateLimiterService;
    use App\Variation4\Service\CorsHandlerService;
    use App\Variation4\Service\JsonTransformerService;
    use App\Variation4\Service\RequestLoggerService;
    use Symfony\Component\EventDispatcher\EventSubscriberInterface;
    use Symfony\Component\HttpFoundation\Request;
    use Symfony\Component\HttpFoundation\Response;
    use Symfony\Component\HttpKernel\Event\ExceptionEvent;
    use Symfony\Component\HttpKernel\Event\RequestEvent;
    use Symfony\Component\HttpKernel\Event\ResponseEvent;
    use Symfony\Component\HttpKernel\Event\ViewEvent;
    use Symfony\Component\HttpKernel\KernelEvents;

    class ApiGatewaySubscriber implements EventSubscriberInterface
    {
        public function __construct(
            private RequestLoggerService $requestLogger,
            private CorsHandlerService $corsHandler,
            private ApiRateLimiterService $rateLimiter,
            private JsonTransformerService $jsonTransformer,
            private ApiErrorHandlerService $errorHandler
        ) {}

        public static function getSubscribedEvents(): array
        {
            return [
                KernelEvents::REQUEST => ['onRequest', 10],
                KernelEvents::VIEW => ['onView', 10],
                KernelEvents::EXCEPTION => ['onException', 10],
                KernelEvents::RESPONSE => ['onResponse', 10],
            ];
        }

        public function onRequest(RequestEvent $event): void
        {
            if (!$this->isApiRequest($event->getRequest())) return;

            $this->requestLogger->log($event->getRequest());

            if ($response = $this->corsHandler->handlePreflight($event->getRequest())) {
                $event->setResponse($response);
                return;
            }

            $this->rateLimiter->consume($event->getRequest());
            $this->jsonTransformer->transformRequest($event->getRequest());
        }

        public function onView(ViewEvent $event): void
        {
            if (!$this->isApiRequest($event->getRequest()) || $event->getControllerResult() instanceof Response) return;
            
            $response = $this->jsonTransformer->transformResponse($event->getControllerResult());
            $event->setResponse($response);
        }

        public function onException(ExceptionEvent $event): void
        {
            if (!$this->isApiRequest($event->getRequest())) return;

            $response = $this->errorHandler->handleException($event->getThrowable());
            $event->setResponse($response);
        }

        public function onResponse(ResponseEvent $event): void
        {
            if (!$this->isApiRequest($event->getRequest())) return;

            $this->corsHandler->addHeaders($event->getResponse());
        }

        private function isApiRequest(Request $request): bool
        {
            return $request->isMainRequest() && str_starts_with($request->getPathInfo(), '/api/');
        }
    }
}