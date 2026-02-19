<?php

namespace App\Variation1\EventListener;

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
use Symfony\Component\Serializer\SerializerInterface;

// --- Domain Schema Mocks ---
namespace App\Variation1\Domain\Enum {
    enum UserRole { case ADMIN; case USER; }
    enum PostStatus { case DRAFT; case PUBLISHED; }
}

// --- The "Classic" Subscriber Implementation ---
namespace App\Variation1\EventListener {

    class ApiLifecycleSubscriber implements EventSubscriberInterface
    {
        private const API_ROUTE_PREFIX = '/api/';

        public function __construct(
            private LoggerInterface $logger,
            private RateLimiterFactory $apiLimiter,
            private SerializerInterface $serializer,
            private bool $isDevelopmentEnv
        ) {}

        public static function getSubscribedEvents(): array
        {
            return [
                KernelEvents::REQUEST => ['onKernelRequest', 20], // High priority
                KernelEvents::EXCEPTION => ['onKernelException', 10],
                KernelEvents::VIEW => ['onKernelView', 5],
                KernelEvents::RESPONSE => ['onKernelResponse', 0],
            ];
        }

        public function onKernelRequest(RequestEvent $event): void
        {
            if (!$event->isMainRequest() || !$this->isApiRequest($event->getRequest())) {
                return;
            }

            $request = $event->getRequest();

            // 1. Request Logging
            $this->logger->info('API Request received', [
                'method' => $request->getMethod(),
                'uri' => $request->getUri(),
                'ip' => $request->getClientIp(),
            ]);

            // 2. CORS Pre-flight Handling
            if ($request->isMethod('OPTIONS')) {
                $response = new Response();
                $event->setResponse($response);
                // Headers added in onKernelResponse
                return;
            }

            // 3. Rate Limiting
            try {
                $limiter = $this->apiLimiter->create($request->getClientIp());
                $limiter->consume(1)->ensureAccepted();
            } catch (RateLimitExceededException $e) {
                throw new HttpException(Response::HTTP_TOO_MANY_REQUESTS, 'Too many requests.', $e);
            }

            // 4. Request Transformation (JSON body to request parameters)
            if (0 === strpos($request->headers->get('Content-Type', ''), 'application/json')) {
                $data = json_decode($request->getContent(), true);
                if (json_last_error() === JSON_ERROR_NONE) {
                    $request->request->replace($data);
                }
            }
        }

        public function onKernelException(ExceptionEvent $event): void
        {
            if (!$this->isApiRequest($event->getRequest())) {
                return;
            }

            // 5. Error Handling
            $exception = $event->getThrowable();
            $statusCode = $exception instanceof HttpException ? $exception->getStatusCode() : Response::HTTP_INTERNAL_SERVER_ERROR;

            $errorData = [
                'error' => [
                    'code' => $statusCode,
                    'message' => $exception->getMessage(),
                ],
            ];

            if ($this->isDevelopmentEnv) {
                $errorData['error']['trace'] = $exception->getTraceAsString();
            }

            $this->logger->error('API Exception', [
                'message' => $exception->getMessage(),
                'file' => $exception->getFile(),
                'line' => $exception->getLine(),
            ]);

            $event->setResponse(new JsonResponse($errorData, $statusCode));
        }

        public function onKernelView(ViewEvent $event): void
        {
            $controllerResult = $event->getControllerResult();
            if (!$this->isApiRequest($event->getRequest()) || $controllerResult instanceof Response) {
                return;
            }

            // 5. Response Transformation
            // Assuming controller returns data array or object to be serialized
            $json = $this->serializer->serialize(['data' => $controllerResult], 'json');
            $event->setResponse(new JsonResponse($json, Response::HTTP_OK, [], true));
        }

        public function onKernelResponse(ResponseEvent $event): void
        {
            if (!$event->isMainRequest() || !$this->isApiRequest($event->getRequest())) {
                return;
            }

            // 2. CORS Header Handling (for all responses)
            $response = $event->getResponse();
            $response->headers->set('Access-Control-Allow-Origin', '*');
            $response->headers->set('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
            $response->headers->set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
        }

        private function isApiRequest(Request $request): bool
        {
            return str_starts_with($request->getPathInfo(), self::API_ROUTE_PREFIX);
        }
    }
}