<?php

namespace App\Variation3\EventListener;

use Psr\Log\LoggerInterface;
use Symfony\Component\EventDispatcher\Attribute\AsEventListener;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Event\ExceptionEvent;
use Symfony\Component\HttpKernel\Event\KernelEvent;
use Symfony\Component\HttpKernel\Event\RequestEvent;
use Symfony\Component\HttpKernel\Event\ResponseEvent;
use Symfony\Component\HttpKernel\Event\ViewEvent;
use Symfony\Component\HttpKernel\Exception\HttpException;
use Symfony\Component\RateLimiter\RateLimiterFactory;
use Symfony\Component\RateLimiter\Exception\RateLimitExceededException;

// --- Domain Schema Mocks ---
namespace App\Variation3\Domain\Enum {
    enum UserRole { case ADMIN; case USER; }
    enum PostStatus { case DRAFT; case PUBLISHED; }
}

// --- The "Pragmatic" Combined Listener Implementation ---
namespace App\Variation3\EventListener {

    #[AsEventListener]
    class KernelApiHandler
    {
        public function __construct(
            private LoggerInterface $log,
            private RateLimiterFactory $limiterFactory
        ) {}

        public function __invoke(KernelEvent $evt): void
        {
            $req = $evt->getRequest();
            if (!$evt->isMainRequest() || !str_starts_with($req->getPathInfo(), '/api/')) {
                return;
            }

            match (get_class($evt)) {
                RequestEvent::class => $this->handleRequest($evt),
                ExceptionEvent::class => $this->handleException($evt),
                ViewEvent::class => $this->handleView($evt),
                ResponseEvent::class => $this->handleResponse($evt),
                default => null,
            };
        }

        private function handleRequest(RequestEvent $evt): void
        {
            $req = $evt->getRequest();

            // Logging
            $this->log->info("API Call: {$req->getMethod()} {$req->getRequestUri()} from {$req->getClientIp()}");

            // CORS Pre-flight
            if ($req->isMethod('OPTIONS')) {
                $evt->setResponse(new Response('', Response::HTTP_NO_CONTENT));
                return;
            }

            // Rate Limiting
            try {
                $this->limiterFactory->create($req->getClientIp())->consume()->ensureAccepted();
            } catch (RateLimitExceededException) {
                $evt->setResponse(new JsonResponse(['error' => 'Too many requests'], Response::HTTP_TOO_MANY_REQUESTS));
                return;
            }

            // Request Transformation
            if (str_contains($req->headers->get('Content-Type', ''), 'json')) {
                $content = $req->getContent();
                if (!empty($content)) {
                    $data = json_decode($content, true);
                    if (is_array($data)) {
                        $req->request->replace($data);
                    }
                }
            }
        }

        private function handleException(ExceptionEvent $evt): void
        {
            // Error Handling
            $ex = $evt->getThrowable();
            $code = $ex instanceof HttpException ? $ex->getStatusCode() : 500;
            $this->log->error("API Exception: " . $ex->getMessage(), ['trace' => $ex->getTraceAsString()]);
            $errorPayload = ['error' => ['message' => $ex->getMessage()]];
            $evt->setResponse(new JsonResponse($errorPayload, $code));
        }

        private function handleView(ViewEvent $evt): void
        {
            // Response Transformation
            $controllerResult = $evt->getControllerResult();
            if ($controllerResult instanceof Response) {
                return;
            }
            $evt->setResponse(new JsonResponse(['data' => $controllerResult]));
        }

        private function handleResponse(ResponseEvent $evt): void
        {
            // CORS Headers
            $res = $evt->getResponse();
            $res->headers->add([
                'Access-Control-Allow-Origin' => '*',
                'Access-Control-Allow-Methods' => 'GET, POST, PUT, DELETE, OPTIONS',
                'Access-Control-Allow-Headers' => 'Content-Type, Authorization',
            ]);
        }
    }
}