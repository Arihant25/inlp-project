<?php

namespace App\Variation2\EventListener;

use Psr\Log\LoggerInterface;
use Symfony\Component\EventDispatcher\Attribute\AsEventListener;
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
namespace App\Variation2\Domain\Enum {
    enum UserRole { case ADMIN; case USER; }
    enum PostStatus { case DRAFT; case PUBLISHED; }
}

// --- The "Modern" Attribute-based Listener Implementation ---
namespace App\Variation2\EventListener {

    abstract class AbstractApiListener
    {
        protected function isApiRequest(Request $request): bool
        {
            return str_starts_with($request->getPathInfo(), '/api/');
        }
    }

    #[AsEventListener(event: KernelEvents::REQUEST, priority: 100)]
    final class RequestLoggingListener extends AbstractApiListener
    {
        public function __construct(private LoggerInterface $logger) {}

        public function __invoke(RequestEvent $event): void
        {
            if (!$event->isMainRequest() || !$this->isApiRequest($event->getRequest())) {
                return;
            }
            $req = $event->getRequest();
            $this->logger->info(sprintf('Incoming API request: %s %s', $req->getMethod(), $req->getUri()));
        }
    }

    #[AsEventListener(event: KernelEvents::REQUEST, priority: 50)]
    #[AsEventListener(event: KernelEvents::RESPONSE, priority: 0)]
    final class CorsListener extends AbstractApiListener
    {
        public function __invoke(RequestEvent|ResponseEvent $event): void
        {
            if (!$event->isMainRequest() || !$this->isApiRequest($event->getRequest())) {
                return;
            }

            if ($event instanceof RequestEvent && $event->getRequest()->isMethod('OPTIONS')) {
                $event->setResponse(new Response(null, Response::HTTP_NO_CONTENT));
                return;
            }

            if ($event instanceof ResponseEvent) {
                $response = $event->getResponse();
                $response->headers->set('Access-Control-Allow-Origin', '*');
                $response->headers->set('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
                $response->headers->set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
            }
        }
    }

    #[AsEventListener(event: KernelEvents::REQUEST, priority: 40)]
    final class RateLimitingListener extends AbstractApiListener
    {
        public function __construct(private RateLimiterFactory $apiGlobalLimiter) {}

        public function __invoke(RequestEvent $event): void
        {
            if (!$event->isMainRequest() || !$this->isApiRequest($event->getRequest())) {
                return;
            }

            $limiter = $this->apiGlobalLimiter->create($event->getRequest()->getClientIp());
            if (false === $limiter->consume(1)->isAccepted()) {
                throw new HttpException(Response::HTTP_TOO_MANY_REQUESTS, 'Rate limit exceeded.');
            }
        }
    }

    #[AsEventListener(event: KernelEvents::REQUEST, priority: 30)]
    final class JsonRequestTransformerListener extends AbstractApiListener
    {
        public function __invoke(RequestEvent $event): void
        {
            $request = $event->getRequest();
            if (!$this->isApiRequest($request) || !$request->isMethod('POST') && !$request->isMethod('PUT')) {
                return;
            }

            if (str_contains($request->headers->get('Content-Type', ''), 'application/json')) {
                $data = json_decode($request->getContent(), true);
                if (is_array($data)) {
                    $request->request->replace($data);
                }
            }
        }
    }

    #[AsEventListener(event: KernelEvents::VIEW, priority: 10)]
    final class ApiResponseTransformerListener extends AbstractApiListener
    {
        public function __invoke(ViewEvent $event): void
        {
            $result = $event->getControllerResult();
            if (!$this->isApiRequest($event->getRequest()) || $result instanceof Response) {
                return;
            }
            $response = new JsonResponse(['data' => $result]);
            $event->setResponse($response);
        }
    }

    #[AsEventListener(event: KernelEvents::EXCEPTION, priority: 20)]
    final class ApiExceptionListener extends AbstractApiListener
    {
        public function __construct(private LoggerInterface $logger) {}

        public function __invoke(ExceptionEvent $event): void
        {
            if (!$this->isApiRequest($event->getRequest())) {
                return;
            }

            $exception = $event->getThrowable();
            $statusCode = $exception instanceof HttpException ? $exception->getStatusCode() : 500;

            $this->logger->critical('API Exception occurred: ' . $exception->getMessage(), ['exception' => $exception]);

            $response = new JsonResponse(
                ['error' => ['message' => $exception->getMessage(), 'code' => $statusCode]],
                $statusCode
            );
            $event->setResponse($response);
        }
    }
}