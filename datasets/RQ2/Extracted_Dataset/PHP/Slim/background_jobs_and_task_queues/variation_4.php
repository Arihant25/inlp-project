<?php

// VARIATION 4: The "Pragmatic/Minimalist" Developer
// STYLE: Mix of OOP/functional, invokable (single-action) controllers and jobs, PHP 8 features.
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
            "Pragmatic\\": "src/"
        }
    }
}
*/

// --- FILE: src/Domain.php ---
namespace Pragmatic;

// Using enums for domain constraints
enum UserRole: string { case ADMIN = 'ADMIN'; case USER = 'USER'; }
enum PostStatus: string { case DRAFT = 'DRAFT'; case PUBLISHED = 'PUBLISHED'; }

// Using readonly properties and constructor promotion
class User {
    public readonly string $id;
    public readonly int $createdAt;
    public function __construct(
        public string $email,
        public string $passwordHash,
        public UserRole $role = UserRole::USER,
        public bool $isActive = true,
    ) {
        $this->id = \Ramsey\Uuid\Uuid::uuid4()->toString();
        $this->createdAt = time();
    }
}

class Post {
    public readonly string $id;
    public function __construct(
        public string $userId,
        public string $title,
        public string $content,
        public PostStatus $status = PostStatus::DRAFT,
    ) {
        $this->id = \Ramsey\Uuid\Uuid::uuid4()->toString();
    }
}

// --- FILE: src/JobStatusService.php ---
namespace Pragmatic;

// A simple service to handle job state, could be backed by Redis/DB.
class JobStatusService {
    private string $dir = '/tmp/pragmatic_jobs/';

    public function __construct() {
        if (!is_dir($this->dir)) mkdir($this->dir, 0777, true);
    }

    public function create(string $jobId, string $type, array $meta): void {
        $this->save($jobId, [
            'status' => 'PENDING', 'type' => $type, 'meta' => $meta,
            'history' => [[time(), 'PENDING']], 'attempts' => 0
        ]);
    }

    public function update(string $jobId, string $status, string $message = ''): void {
        $data = $this->get($jobId) ?? [];
        $data['status'] = $status;
        $data['history'][] = [time(), $status, $message];
        if ($status === 'RUNNING') {
            $data['attempts'] = ($data['attempts'] ?? 0) + 1;
        }
        $this->save($jobId, $data);
    }

    public function get(string $jobId): ?array {
        $file = $this->dir . $jobId;
        return file_exists($file) ? unserialize(file_get_contents($file)) : null;
    }

    private function save(string $jobId, array $data): void {
        file_put_contents($this->dir . $jobId, serialize($data));
    }
}

// --- FILE: src/QueueService.php ---
namespace Pragmatic;

use Interop\Queue\Context;
use Ramsey\Uuid\Uuid;

class QueueService {
    public function __construct(
        private Context $context,
        private JobStatusService $statusService
    ) {}

    public function dispatch(object $job, string $jobType, array $meta = []): string {
        $jobId = Uuid::uuid4()->toString();
        // Attach jobId to the job object if it has the property
        if (property_exists($job, 'jobId')) {
            $job->jobId = $jobId;
        }

        $this->statusService->create($jobId, $jobType, $meta);

        $queue = $this->context->createQueue('default');
        $message = $this->context->createMessage(serialize($job));
        $this->context->createProducer()->send($queue, $message);

        return $jobId;
    }
}

// --- FILE: src/Jobs/WelcomeEmailJob.php ---
namespace Pragmatic\Jobs;

use Pragmatic\JobStatusService;

class WelcomeEmailJob {
    public string $jobId;

    public function __construct(
        private readonly string $userEmail,
        private readonly JobStatusService $statusSvc
    ) {}

    public function __invoke(): void {
        $this->statusSvc->update($this->jobId, 'RUNNING', "Sending email to {$this->userEmail}");
        echo "Job {$this->jobId}: Sending welcome email to {$this->userEmail}\n";
        sleep(2);

        // Simulate failure
        if (random_int(1, 10) <= 3) { // 30% failure rate
            throw new \Exception("SMTP server timed out");
        }

        $this->statusSvc->update($this->jobId, 'COMPLETED');
        echo "Job {$this->jobId}: Email sent.\n";
    }
}

// --- FILE: src/Jobs/ImagePipelineJob.php ---
namespace Pragmatic\Jobs;

use Pragmatic\JobStatusService;

class ImagePipelineJob {
    public string $jobId;

    public function __construct(
        private readonly string $postId,
        private readonly string $sourceUrl,
        private readonly JobStatusService $statusSvc
    ) {}

    public function __invoke(): void {
        $this->statusSvc->update($this->jobId, 'RUNNING', "Starting pipeline for post {$this->postId}");
        echo "Job {$this->jobId}: Processing image from {$this->sourceUrl}\n";
        
        sleep(1);
        $this->statusSvc->update($this->jobId, 'RUNNING', "Resizing...");
        echo "Job {$this->jobId}: Resized.\n";
        
        sleep(2);
        $this->statusSvc->update($this->jobId, 'RUNNING', "Watermarking...");
        echo "Job {$this->jobId}: Watermarked.\n";

        $this->statusSvc->update($this->jobId, 'COMPLETED');
        echo "Job {$this->jobId}: Done.\n";
    }
}

// --- FILE: src/Jobs/PeriodicCleanupJob.php ---
namespace Pragmatic\Jobs;

use Pragmatic\JobStatusService;

class PeriodicCleanupJob {
    public string $jobId;
    public function __construct(private readonly JobStatusService $statusSvc) {}

    public function __invoke(): void {
        $this->statusSvc->update($this->jobId, 'RUNNING', "Cleaning up old posts/users");
        echo "Job {$this->jobId}: Running periodic cleanup...\n";
        sleep(5);
        $this->statusSvc->update($this->jobId, 'COMPLETED');
        echo "Job {$this->jobId}: Cleanup complete.\n";
    }
}

// --- FILE: src/Controllers/RegisterUser.php ---
namespace Pragmatic\Controllers;

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Pragmatic\QueueService;
use Pragmatic\Jobs\WelcomeEmailJob;
use Pragmatic\JobStatusService;

class RegisterUser {
    public function __construct(
        private QueueService $queue,
        private JobStatusService $statusSvc
    ) {}

    public function __invoke(Request $request, Response $response): Response {
        $email = $request->getParsedBody()['email'] ?? 'pragmatist@example.dev';
        $job = new WelcomeEmailJob($email, $this->statusSvc);
        $jobId = $this->queue->dispatch($job, 'WelcomeEmail', ['email' => $email]);

        $payload = json_encode(['message' => 'Registration accepted', 'jobId' => $jobId]);
        $response->getBody()->write($payload);
        return $response->withHeader('Content-Type', 'application/json')->withStatus(202);
    }
}

// --- FILE: src/Controllers/CreatePost.php ---
namespace Pragmatic\Controllers;

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Pragmatic\QueueService;
use Pragmatic\Jobs\ImagePipelineJob;
use Pragmatic\JobStatusService;
use Ramsey\Uuid\Uuid;

class CreatePost {
    public function __construct(
        private QueueService $queue,
        private JobStatusService $statusSvc
    ) {}

    public function __invoke(Request $request, Response $response): Response {
        $imageUrl = $request->getParsedBody()['image_url'] ?? 'https://picsum.photos/200';
        $postId = Uuid::uuid4()->toString();
        $job = new ImagePipelineJob($postId, $imageUrl, $this->statusSvc);
        $jobId = $this->queue->dispatch($job, 'ImagePipeline', ['postId' => $postId]);

        $payload = json_encode(['message' => 'Post accepted', 'jobId' => $jobId]);
        $response->getBody()->write($payload);
        return $response->withHeader('Content-Type', 'application/json')->withStatus(202);
    }
}

// --- FILE: src/Controllers/GetJob.php ---
namespace Pragmatic\Controllers;

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Pragmatic\JobStatusService;

class GetJob {
    public function __construct(private JobStatusService $statusSvc) {}

    public function __invoke(Request $request, Response $response, array $args): Response {
        $job = $this->statusSvc->get($args['id']);
        if (!$job) {
            return $response->withStatus(404);
        }
        $response->getBody()->write(json_encode($job));
        return $response->withHeader('Content-Type', 'application/json');
    }
}

// --- FILE: public/index.php ---
/*
<?php
require __DIR__ . '/../vendor/autoload.php';

use Slim\Factory\AppFactory;
use DI\Container;
use Enqueue\SimpleClient\SimpleClient;
use Interop\Queue\Context;

// To run:
// 1. composer install
// 2. php -S localhost:8080 -t public

$container = new Container();
$container->set(Context::class, fn() => (new SimpleClient('file:///tmp/enqueue_pragmatic'))->getQueueContext());
AppFactory::setContainer($container);

$app = AppFactory::create();
$app->addBodyParsingMiddleware();

$app->post('/users', \Pragmatic\Controllers\RegisterUser::class);
$app->post('/posts', \Pragmatic\Controllers\CreatePost::class);
$app->get('/jobs/{id}', \Pragmatic\Controllers\GetJob::class);

$app->run();
*/

// --- FILE: worker.php ---
/*
<?php
require __DIR__ . '/vendor/autoload.php';

use DI\Container;
use Enqueue\SimpleClient\SimpleClient;
use Interop\Queue\Context;
use Pragmatic\JobStatusService;

// To run:
// php worker.php

echo "Pragmatic worker online.\n";

$container = new Container();
$container->set(Context::class, fn() => (new SimpleClient('file:///tmp/enqueue_pragmatic'))->getQueueContext());
$context = $container->get(Context::class);
$statusSvc = $container->get(JobStatusService::class);

$queue = $context->createQueue('default');
$consumer = $context->createConsumer($queue);

while (true) {
    $message = $consumer->receive(5000); // 5s timeout
    if (!$message) continue;

    $job = unserialize($message->getBody());
    $jobId = $job->jobId ?? null;

    if (!is_callable($job) || !$jobId) {
        echo "Received invalid job. Rejecting.\n";
        $consumer->reject($message, false);
        continue;
    }

    try {
        $job(); // Execute the invokable job object
        $consumer->acknowledge($message);
    } catch (Throwable $e) {
        echo "Job {$jobId} failed: {$e->getMessage()}\n";
        $attempts = $statusSvc->get($jobId)['attempts'] ?? 1;

        if ($attempts < 5) {
            $delay = (10 ** $attempts); // 10ms, 100ms, 1s, 10s
            $statusSvc->update($jobId, 'RETRYING', "Error: {$e->getMessage()}. Retrying in {$delay}ms");
            
            $retryMessage = $context->createMessage($message->getBody());
            $context->createProducer()->setDeliveryDelay($delay)->send($queue, $retryMessage);
            echo "Job {$jobId} re-queued for attempt #".($attempts+1)."\n";
        } else {
            $statusSvc->update($jobId, 'FAILED', "Max retries exceeded. Last error: {$e->getMessage()}");
            echo "Job {$jobId} has failed permanently.\n";
        }
        $consumer->reject($message, false);
    }
}
*/

// --- FILE: cron.php ---
/*
<?php
require __DIR__ . '/vendor/autoload.php';

use DI\Container;
use Pragmatic\QueueService;
use Pragmatic\Jobs\PeriodicCleanupJob;
use Pragmatic\JobStatusService;

// To run:
// php cron.php

echo "Pragmatic cron dispatcher running.\n";

$container = new Container();
$container->set(Interop\Queue\Context::class, fn() => (new Enqueue\SimpleClient\SimpleClient('file:///tmp/enqueue_pragmatic'))->getQueueContext());

$queueService = $container->get(QueueService::class);
$statusService = $container->get(JobStatusService::class);

$job = new PeriodicCleanupJob($statusService);
$jobId = $queueService->dispatch($job, 'PeriodicCleanup');

echo "Dispatched PeriodicCleanupJob with ID: {$jobId}\n";
*/
?>