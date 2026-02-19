<?php

// VARIATION 2: The "Action-Domain-Responder" Developer
// STYLE: Follows ADR pattern, uses Command/Handler pattern for jobs.
//
// --- FILE: composer.json ---
/*
{
    "require": {
        "slim/slim": "4.*",
        "slim/psr7": "^1.6",
        "php-di/php-di": "^7.0",
        "enqueue/simple-client": "^0.10.16",
        "ramsey/uuid": "^4.7"
    },
    "autoload": {
        "psr-4": {
            "ADR\\": "src/"
        }
    }
}
*/

// --- FILE: src/Domain/Entity/User.php ---
namespace ADR\Domain\Entity;

class User {
    public function __construct(
        public string $id,
        public string $email,
        public string $password_hash,
        public string $role = 'USER',
        public bool $is_active = true,
        public ?int $created_at = null
    ) {
        $this->created_at = $created_at ?? time();
    }
}

// --- FILE: src/Domain/Entity/Post.php ---
namespace ADR\Domain\Entity;

class Post {
    public function __construct(
        public string $id,
        public string $user_id,
        public string $title,
        public string $content,
        public string $status = 'DRAFT'
    ) {}
}

// --- FILE: src/Domain/Services/JobTrackingService.php ---
namespace ADR\Domain\Services;

class JobTrackingService {
    private const STORAGE_FILE = '/tmp/adr_jobs.json';

    public function create(string $jobId, string $jobType, array $context): void {
        $jobs = $this->load();
        $jobs[$jobId] = [
            'type' => $jobType,
            'status' => 'queued',
            'context' => $context,
            'history' => [ ['status' => 'queued', 'timestamp' => time()] ],
            'attempts' => 0,
        ];
        $this->save($jobs);
    }

    public function updateStatus(string $jobId, string $status, string $details = ''): void {
        $jobs = $this->load();
        if (!isset($jobs[$jobId])) return;
        $jobs[$jobId]['status'] = $status;
        $jobs[$jobId]['history'][] = ['status' => $status, 'details' => $details, 'timestamp' => time()];
        if ($status === 'processing') {
            $jobs[$jobId]['attempts']++;
        }
        $this->save($jobs);
    }

    public function get(string $jobId): ?array {
        return $this->load()[$jobId] ?? null;
    }

    private function load(): array {
        if (!file_exists(self::STORAGE_FILE)) return [];
        return json_decode(file_get_contents(self::STORAGE_FILE), true) ?: [];
    }

    private function save(array $data): void {
        file_put_contents(self::STORAGE_FILE, json_encode($data, JSON_PRETTY_PRINT));
    }
}

// --- FILE: src/Domain/Services/RegistrationService.php ---
namespace ADR\Domain\Services;

use Interop\Queue\Context;
use Ramsey\Uuid\Uuid;

class RegistrationService {
    public function __construct(
        private Context $queueContext,
        private JobTrackingService $jobTracker
    ) {}

    public function registerUser(string $email): array {
        $userId = Uuid::uuid4()->toString();
        // In a real app, save user to DB here.
        
        $jobId = Uuid::uuid4()->toString();
        $command = [
            'command' => 'SendWelcomeEmail',
            'payload' => ['jobId' => $jobId, 'email' => $email]
        ];

        $this->jobTracker->create($jobId, 'SendWelcomeEmail', ['email' => $email]);
        
        $topic = $this->queueContext->createTopic('domain_commands');
        $message = $this->queueContext->createMessage(json_encode($command));
        $this->queueContext->createProducer()->send($topic, $message);

        return ['userId' => $userId, 'jobId' => $jobId];
    }
}

// --- FILE: src/Domain/Services/PostCreationService.php ---
namespace ADR\Domain\Services;

use Interop\Queue\Context;
use Ramsey\Uuid\Uuid;

class PostCreationService {
    public function __construct(
        private Context $queueContext,
        private JobTrackingService $jobTracker
    ) {}

    public function createPost(string $userId, string $imageUrl): array {
        $postId = Uuid::uuid4()->toString();
        // In a real app, save post to DB here.

        $jobId = Uuid::uuid4()->toString();
        $command = [
            'command' => 'ProcessPostImage',
            'payload' => ['jobId' => $jobId, 'postId' => $postId, 'imageUrl' => $imageUrl]
        ];

        $this->jobTracker->create($jobId, 'ProcessPostImage', ['postId' => $postId]);

        $topic = $this->queueContext->createTopic('domain_commands');
        $message = $this->queueContext->createMessage(json_encode($command));
        $this->queueContext->createProducer()->send($topic, $message);

        return ['postId' => $postId, 'jobId' => $jobId];
    }
}

// --- FILE: src/Application/Actions/RegisterUserAction.php ---
namespace ADR\Application\Actions;

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use ADR\Domain\Services\RegistrationService;
use ADR\Application\Responders\JsonResponder;

class RegisterUserAction {
    public function __construct(
        private RegistrationService $registrationService,
        private JsonResponder $responder
    ) {}

    public function __invoke(Request $request, Response $response): Response {
        $body = (array)$request->getParsedBody();
        $email = $body['email'] ?? 'new.user@example.com';
        
        $result = $this->registrationService->registerUser($email);
        
        $payload = [
            'message' => 'User registration initiated. A welcome email will be sent.',
            'data' => $result
        ];
        return $this->responder->respond($response, $payload, 202);
    }
}

// --- FILE: src/Application/Actions/CreatePostAction.php ---
namespace ADR\Application\Actions;

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use ADR\Domain\Services\PostCreationService;
use ADR\Application\Responders\JsonResponder;

class CreatePostAction {
    public function __construct(
        private PostCreationService $postCreationService,
        private JsonResponder $responder
    ) {}

    public function __invoke(Request $request, Response $response): Response {
        $body = (array)$request->getParsedBody();
        $imageUrl = $body['image_url'] ?? 'https://example.com/photo.png';
        $userId = '...'; // from auth
        
        $result = $this->postCreationService->createPost($userId, $imageUrl);
        
        $payload = [
            'message' => 'Post submitted. Image processing is scheduled.',
            'data' => $result
        ];
        return $this->responder->respond($response, $payload, 202);
    }
}

// --- FILE: src/Application/Actions/GetJobStatusAction.php ---
namespace ADR\Application\Actions;

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use ADR\Domain\Services\JobTrackingService;
use ADR\Application\Responders\JsonResponder;

class GetJobStatusAction {
    public function __construct(
        private JobTrackingService $jobTracker,
        private JsonResponder $responder
    ) {}

    public function __invoke(Request $request, Response $response, array $args): Response {
        $jobId = $args['id'];
        $job = $this->jobTracker->get($jobId);

        if (!$job) {
            return $this->responder->respond($response, ['error' => 'Job not found'], 404);
        }
        return $this->responder->respond($response, $job);
    }
}

// --- FILE: src/Application/Responders/JsonResponder.php ---
namespace ADR\Application\Responders;

use Psr\Http\Message\ResponseInterface as Response;

class JsonResponder {
    public function respond(Response $response, array $payload, int $statusCode = 200): Response {
        $response->getBody()->write(json_encode($payload));
        return $response->withHeader('Content-Type', 'application/json')->withStatus($statusCode);
    }
}

// --- FILE: src/Application/Handlers/SendWelcomeEmailHandler.php ---
namespace ADR\Application\Handlers;

use ADR\Domain\Services\JobTrackingService;

class SendWelcomeEmailHandler {
    public function __construct(private JobTrackingService $jobTracker) {}

    public function handle(array $payload): void {
        $jobId = $payload['jobId'];
        $email = $payload['email'];

        $this->jobTracker->updateStatus($jobId, 'processing', 'Attempting to send email.');
        echo "Handler: Sending welcome email to {$email} (Job: {$jobId})\n";
        
        // Simulate failure
        if (rand(1, 4) == 1) {
            $this->jobTracker->updateStatus($jobId, 'failed', 'SMTP server connection failed.');
            throw new \RuntimeException("SMTP server down");
        }

        sleep(2);
        $this->jobTracker->updateStatus($jobId, 'completed', 'Email sent successfully.');
        echo "Handler: Email sent to {$email}\n";
    }
}

// --- FILE: src/Application/Handlers/ProcessPostImageHandler.php ---
namespace ADR\Application\Handlers;

use ADR\Domain\Services\JobTrackingService;

class ProcessPostImageHandler {
    public function __construct(private JobTrackingService $jobTracker) {}

    public function handle(array $payload): void {
        $jobId = $payload['jobId'];
        $this->jobTracker->updateStatus($jobId, 'processing', 'Starting image pipeline.');
        echo "Handler: Processing image for post {$payload['postId']} (Job: {$jobId})\n";
        
        sleep(2);
        $this->jobTracker->updateStatus($jobId, 'processing', 'Resizing image.');
        echo "Handler: Image resized\n";

        sleep(1);
        $this->jobTracker->updateStatus($jobId, 'processing', 'Applying watermark.');
        echo "Handler: Watermark applied\n";

        $this->jobTracker->updateStatus($jobId, 'completed', 'Image processing complete.');
        echo "Handler: Image processing finished\n";
    }
}

// --- FILE: src/Application/Handlers/CleanupTaskHandler.php ---
namespace ADR\Application\Handlers;

use ADR\Domain\Services\JobTrackingService;

class CleanupTaskHandler {
    public function __construct(private JobTrackingService $jobTracker) {}

    public function handle(array $payload): void {
        $jobId = $payload['jobId'];
        $this->jobTracker->updateStatus($jobId, 'processing', 'Running periodic cleanup.');
        echo "Handler: Running cleanup task (Job: {$jobId})\n";
        sleep(5);
        $this->jobTracker->updateStatus($jobId, 'completed', 'Cleanup finished.');
        echo "Handler: Cleanup task finished\n";
    }
}

// --- FILE: config/container.php ---
<?php
use DI\ContainerBuilder;
use Interop\Queue\Context;
use Enqueue\SimpleClient\SimpleClient;

$containerBuilder = new ContainerBuilder();
$containerBuilder->useAutowiring(true);
$containerBuilder->addDefinitions([
    Context::class => function() {
        return (new SimpleClient('file:///tmp/enqueue_adr?file_extension=.json'))->getQueueContext();
    },
]);
return $containerBuilder->build();

// --- FILE: public/index.php ---
/*
<?php
require __DIR__ . '/../vendor/autoload.php';

use Slim\Factory\AppFactory;
use ADR\Application\Actions\RegisterUserAction;
use ADR\Application\Actions\CreatePostAction;
use ADR\Application\Actions\GetJobStatusAction;

// To run:
// 1. composer install
// 2. php -S localhost:8080 -t public

$container = require __DIR__ . '/../config/container.php';
AppFactory::setContainer($container);

$app = AppFactory::create();
$app->addBodyParsingMiddleware();

$app->post('/user/register', RegisterUserAction::class);
$app->post('/post/create', CreatePostAction::class);
$app->get('/job/{id}/status', GetJobStatusAction::class);

$app->run();
*/

// --- FILE: worker.php ---
/*
<?php
require __DIR__ . '/vendor/autoload.php';

use Interop\Queue\Message;
use Interop\Queue\Processor;

// To run:
// php worker.php

echo "ADR Worker started. Listening for commands...\n";

$container = require __DIR__ . '/config/container.php';
$context = $container->get(Interop\Queue\Context::class);

$commandHandlers = [
    'SendWelcomeEmail' => \ADR\Application\Handlers\SendWelcomeEmailHandler::class,
    'ProcessPostImage' => \ADR\Application\Handlers\ProcessPostImageHandler::class,
    'CleanupTask' => \ADR\Application\Handlers\CleanupTaskHandler::class,
];

$queue = $context->createQueue('domain_commands');
$consumer = $context->createConsumer($queue);

while (true) {
    $message = $consumer->receive(10000); // 10 sec timeout
    if (!$message) continue;

    $body = json_decode($message->getBody(), true);
    $commandName = $body['command'] ?? null;
    $payload = $body['payload'] ?? [];

    if ($commandName && isset($commandHandlers[$commandName])) {
        try {
            $handlerClass = $commandHandlers[$commandName];
            $handlerInstance = $container->get($handlerClass);
            $handlerInstance->handle($payload);
            $consumer->acknowledge($message);
        } catch (\Exception $e) {
            echo "Error handling command '{$commandName}': " . $e->getMessage() . "\n";
            // Exponential backoff retry
            $attempt = $message->getProperty('attempt', 1);
            if ($attempt <= 5) {
                $delay = pow(3, $attempt) * 1000; // 3s, 9s, 27s...
                $retryMessage = $context->createMessage($message->getBody(), ['attempt' => $attempt + 1]);
                $context->createProducer()->setDeliveryDelay($delay)->send($queue, $retryMessage);
                echo "Command re-queued for retry #{$attempt} with delay {$delay}ms.\n";
            } else {
                 echo "Command '{$commandName}' failed after max retries.\n";
            }
            $consumer->reject($message, false); // Do not requeue automatically
        }
    } else {
        echo "Unknown or malformed command received.\n";
        $consumer->reject($message, false);
    }
}
*/

// --- FILE: cron.php ---
/*
<?php
require __DIR__ . '/vendor/autoload.php';

use Ramsey\Uuid\Uuid;
use ADR\Domain\Services\JobTrackingService;

// To run:
// php cron.php

echo "ADR Cron runner started.\n";

$container = require __DIR__ . '/config/container.php';
$context = $container->get(Interop\Queue\Context::class);
$jobTracker = $container->get(JobTrackingService::class);

$jobId = Uuid::uuid4()->toString();
$command = [
    'command' => 'CleanupTask',
    'payload' => ['jobId' => $jobId, 'timestamp' => time()]
];

$jobTracker->create($jobId, 'CleanupTask', []);

$topic = $context->createTopic('domain_commands');
$message = $context->createMessage(json_encode($command));
$context->createProducer()->send($topic, $message);

echo "Scheduled periodic command: CleanupTask with Job ID {$jobId}\n";
*/
?>