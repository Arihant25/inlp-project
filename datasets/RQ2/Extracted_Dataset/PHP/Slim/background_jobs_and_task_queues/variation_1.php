<?php

// VARIATION 1: The "Service-Oriented" Developer
// STYLE: Heavily OOP, dedicated Service and Job classes, extensive DI.
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
            "App\\": "src/"
        }
    }
}
*/

// --- FILE: src/Domain/User.php ---
namespace App\Domain;

class User {
    public string $id;
    public string $email;
    public string $password_hash;
    public string $role; // ADMIN, USER
    public bool $is_active;
    public int $created_at;
}

// --- FILE: src/Domain/Post.php ---
namespace App\Domain;

class Post {
    public string $id;
    public string $user_id;
    public string $title;
    public string $content;
    public string $status; // DRAFT, PUBLISHED
}

// --- FILE: src/Services/JobStatusTracker.php ---
namespace App\Services;

class JobStatusTracker {
    private string $storagePath = '/tmp/job_statuses.json';

    private function readData(): array {
        if (!file_exists($this->storagePath)) {
            return [];
        }
        return json_decode(file_get_contents($this->storagePath), true) ?: [];
    }

    private function writeData(array $data): void {
        file_put_contents($this->storagePath, json_encode($data, JSON_PRETTY_PRINT));
    }

    public function create(string $jobId, string $jobType, array $payload): void {
        $jobs = $this->readData();
        $jobs[$jobId] = [
            'type' => $jobType,
            'status' => 'PENDING',
            'payload' => $payload,
            'created_at' => time(),
            'updated_at' => time(),
            'attempts' => 0,
            'logs' => [],
        ];
        $this->writeData($jobs);
    }

    public function update(string $jobId, string $status, ?string $log = null): void {
        $jobs = $this->readData();
        if (isset($jobs[$jobId])) {
            $jobs[$jobId]['status'] = $status;
            $jobs[$jobId]['updated_at'] = time();
            if ($status === 'RUNNING') {
                $jobs[$jobId]['attempts']++;
            }
            if ($log) {
                $jobs[$jobId]['logs'][] = date('Y-m-d H:i:s') . ' - ' . $log;
            }
            $this->writeData($jobs);
        }
    }

    public function find(string $jobId): ?array {
        $jobs = $this->readData();
        return $jobs[$jobId] ?? null;
    }
}

// --- FILE: src/Jobs/JobInterface.php ---
namespace App\Jobs;

interface JobInterface {
    public function execute(): void;
}

// --- FILE: src/Jobs/SendWelcomeEmailJob.php ---
namespace App\Jobs;

use App\Services\JobStatusTracker;

class SendWelcomeEmailJob implements JobInterface {
    private string $jobId;
    private string $userEmail;
    private JobStatusTracker $statusTracker;

    public function __construct(string $jobId, string $userEmail, JobStatusTracker $statusTracker) {
        $this->jobId = $jobId;
        $this->userEmail = $userEmail;
        $this->statusTracker = $statusTracker;
    }

    public function execute(): void {
        $this->statusTracker->update($this->jobId, 'RUNNING', 'Starting to send welcome email.');
        echo "Executing SendWelcomeEmailJob for {$this->userEmail}\n";
        // Simulate a potentially failing email service
        if (rand(1, 5) === 1) {
            $this->statusTracker->update($this->jobId, 'FAILED', 'Email service unavailable.');
            throw new \Exception("Failed to send email to {$this->userEmail}. Service unavailable.");
        }
        sleep(2); // Simulate network latency
        $this->statusTracker->update($this->jobId, 'COMPLETED', 'Email sent successfully.');
        echo "Welcome email sent to {$this->userEmail}\n";
    }
}

// --- FILE: src/Jobs/ProcessPostImageJob.php ---
namespace App\Jobs;

use App\Services\JobStatusTracker;

class ProcessPostImageJob implements JobInterface {
    private string $jobId;
    private string $postId;
    private string $imageUrl;
    private JobStatusTracker $statusTracker;

    public function __construct(string $jobId, string $postId, string $imageUrl, JobStatusTracker $statusTracker) {
        $this->jobId = $jobId;
        $this->postId = $postId;
        $this->imageUrl = $imageUrl;
        $this->statusTracker = $statusTracker;
    }

    public function execute(): void {
        $this->statusTracker->update($this->jobId, 'RUNNING', 'Starting image processing pipeline.');
        echo "Executing ProcessPostImageJob for post {$this->postId}\n";
        
        // Step 1: Download
        $this->statusTracker->update($this->jobId, 'RUNNING', 'Downloading image from ' . $this->imageUrl);
        sleep(2);
        echo "Image downloaded for post {$this->postId}\n";

        // Step 2: Resize
        $this->statusTracker->update($this->jobId, 'RUNNING', 'Resizing image.');
        sleep(3);
        echo "Image resized for post {$this->postId}\n";

        // Step 3: Watermark
        $this->statusTracker->update($this->jobId, 'RUNNING', 'Applying watermark.');
        sleep(1);
        echo "Watermark applied for post {$this->postId}\n";

        $this->statusTracker->update($this->jobId, 'COMPLETED', 'Image processing finished.');
        echo "Image processing complete for post {$this->postId}\n";
    }
}

// --- FILE: src/Jobs/CleanupInactiveUsersJob.php ---
namespace App\Jobs;

use App\Services\JobStatusTracker;

class CleanupInactiveUsersJob implements JobInterface {
    private string $jobId;
    private JobStatusTracker $statusTracker;

    public function __construct(string $jobId, JobStatusTracker $statusTracker) {
        $this->jobId = $jobId;
        $this->statusTracker = $statusTracker;
    }

    public function execute(): void {
        $this->statusTracker->update($this->jobId, 'RUNNING', 'Starting cleanup of inactive users.');
        echo "Executing CleanupInactiveUsersJob\n";
        sleep(5); // Simulate DB query
        $this->statusTracker->update($this->jobId, 'COMPLETED', 'Cleanup finished. 3 users pruned.');
        echo "Cleanup of inactive users complete.\n";
    }
}

// --- FILE: src/Controllers/UserController.php ---
namespace App\Controllers;

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Interop\Queue\Context;
use Ramsey\Uuid\Uuid;
use App\Services\JobStatusTracker;

class UserController {
    private Context $queueContext;
    private JobStatusTracker $statusTracker;

    public function __construct(Context $queueContext, JobStatusTracker $statusTracker) {
        $this->queueContext = $queueContext;
        $this->statusTracker = $statusTracker;
    }

    public function register(Request $request, Response $response): Response {
        $params = (array)$request->getParsedBody();
        $email = $params['email'] ?? 'test@example.com';
        $userId = Uuid::uuid4()->toString();
        $jobId = Uuid::uuid4()->toString();

        $payload = [
            'jobClass' => \App\Jobs\SendWelcomeEmailJob::class,
            'jobId' => $jobId,
            'userEmail' => $email,
        ];
        
        $this->statusTracker->create($jobId, 'SendWelcomeEmail', ['userEmail' => $email]);
        
        $topic = $this->queueContext->createTopic('app_tasks');
        $message = $this->queueContext->createMessage(json_encode($payload));
        $this->queueContext->createProducer()->send($topic, $message);

        $responseBody = [
            'message' => 'User registered. Welcome email is being sent.',
            'userId' => $userId,
            'jobId' => $jobId
        ];
        $response->getBody()->write(json_encode($responseBody));
        return $response->withHeader('Content-Type', 'application/json');
    }
}

// --- FILE: src/Controllers/PostController.php ---
namespace App\Controllers;

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Interop\Queue\Context;
use Ramsey\Uuid\Uuid;
use App\Services\JobStatusTracker;

class PostController {
    private Context $queueContext;
    private JobStatusTracker $statusTracker;

    public function __construct(Context $queueContext, JobStatusTracker $statusTracker) {
        $this->queueContext = $queueContext;
        $this->statusTracker = $statusTracker;
    }

    public function create(Request $request, Response $response): Response {
        $params = (array)$request->getParsedBody();
        $imageUrl = $params['image_url'] ?? 'https://example.com/image.jpg';
        $postId = Uuid::uuid4()->toString();
        $jobId = Uuid::uuid4()->toString();

        $payload = [
            'jobClass' => \App\Jobs\ProcessPostImageJob::class,
            'jobId' => $jobId,
            'postId' => $postId,
            'imageUrl' => $imageUrl,
        ];

        $this->statusTracker->create($jobId, 'ProcessPostImage', ['postId' => $postId, 'imageUrl' => $imageUrl]);
        
        $topic = $this->queueContext->createTopic('app_tasks');
        $message = $this->queueContext->createMessage(json_encode($payload));
        $this->queueContext->createProducer()->send($topic, $message);

        $responseBody = [
            'message' => 'Post created. Image processing has been scheduled.',
            'postId' => $postId,
            'jobId' => $jobId
        ];
        $response->getBody()->write(json_encode($responseBody));
        return $response->withHeader('Content-Type', 'application/json');
    }
}

// --- FILE: src/Controllers/JobStatusController.php ---
namespace App\Controllers;

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use App\Services\JobStatusTracker;

class JobStatusController {
    private JobStatusTracker $statusTracker;

    public function __construct(JobStatusTracker $statusTracker) {
        $this->statusTracker = $statusTracker;
    }

    public function getStatus(Request $request, Response $response, array $args): Response {
        $jobId = $args['id'];
        $job = $this->statusTracker->find($jobId);

        if (!$job) {
            $response->getBody()->write(json_encode(['error' => 'Job not found']));
            return $response->withStatus(404)->withHeader('Content-Type', 'application/json');
        }

        $response->getBody()->write(json_encode($job));
        return $response->withHeader('Content-Type', 'application/json');
    }
}


// --- FILE: config/container.php ---
<?php
use DI\ContainerBuilder;
use Interop\Queue\Context;
use Enqueue\SimpleClient\SimpleClient;
use App\Services\JobStatusTracker;

$containerBuilder = new ContainerBuilder();
$containerBuilder->addDefinitions([
    Context::class => function() {
        // In-memory/filesystem queue for self-contained example
        $client = new SimpleClient('file:///tmp/enqueue?file_extension=.json');
        return $client->getQueueContext();
    },
    JobStatusTracker::class => \DI\autowire(JobStatusTracker::class),
]);
return $containerBuilder->build();


// --- FILE: public/index.php ---
/*
<?php
require __DIR__ . '/../vendor/autoload.php';

use Slim\Factory\AppFactory;
use App\Controllers\UserController;
use App\Controllers\PostController;
use App\Controllers\JobStatusController;

// To run:
// 1. composer install
// 2. php -S localhost:8080 -t public

$container = require __DIR__ . '/../config/container.php';
AppFactory::setContainer($container);

$app = AppFactory::create();

$app->post('/users', [UserController::class, 'register']);
$app->post('/posts', [PostController::class, 'create']);
$app->get('/jobs/{id}', [JobStatusController::class, 'getStatus']);

$app->run();
*/


// --- FILE: worker.php ---
/*
<?php
require __DIR__ . '/vendor/autoload.php';

use Interop\Queue\Message;
use Interop\Queue\Processor;
use App\Services\JobStatusTracker;

// To run:
// php worker.php

echo "Worker started. Waiting for tasks...\n";

$container = require __DIR__ . '/config/container.php';
$context = $container->get(Interop\Queue\Context::class);
$statusTracker = $container->get(JobStatusTracker::class);

$queue = $context->createQueue('app_tasks');
$consumer = $context->createConsumer($queue);

while (true) {
    $message = $consumer->receive();
    if (!$message) continue;

    try {
        $payload = json_decode($message->getBody(), true);
        if (!isset($payload['jobClass']) || !class_exists($payload['jobClass'])) {
            throw new \Exception("Invalid job class specified.");
        }

        // Use DI container to resolve the job class and its dependencies
        $jobInstance = $container->make($payload['jobClass'], $payload);
        $jobInstance->execute();

        $consumer->acknowledge($message);
    } catch (\Exception $e) {
        echo "Error processing job: " . $e->getMessage() . "\n";
        
        // Retry logic with exponential backoff
        $attempts = $message->getProperty('attempts', 1);
        if ($attempts < 5) {
            $statusTracker->update($payload['jobId'], 'RETRYING', 'Job failed, will retry. Attempt ' . $attempts);
            $newMessage = $context->createMessage($message->getBody(), ['attempts' => $attempts + 1]);
            $delay = pow(2, $attempts) * 1000; // 2s, 4s, 8s, 16s
            $context->createProducer()->setDeliveryDelay($delay)->send($queue, $newMessage);
            echo "Re-queued job {$payload['jobId']} with {$delay}ms delay.\n";
        } else {
            $statusTracker->update($payload['jobId'], 'FAILED', 'Job failed after max retries. Error: ' . $e->getMessage());
            echo "Job {$payload['jobId']} failed after max retries.\n";
        }
        $consumer->reject($message);
    }
}
*/

// --- FILE: cron.php ---
/*
<?php
require __DIR__ . '/vendor/autoload.php';

use Ramsey\Uuid\Uuid;
use App\Services\JobStatusTracker;

// To run (e.g., via a real cron job `* * * * * php /path/to/cron.php`):
// php cron.php

echo "Cron script started.\n";

$container = require __DIR__ . '/config/container.php';
$context = $container->get(Interop\Queue\Context::class);
$statusTracker = $container->get(JobStatusTracker::class);

$jobId = Uuid::uuid4()->toString();
$payload = [
    'jobClass' => \App\Jobs\CleanupInactiveUsersJob::class,
    'jobId' => $jobId,
];

$statusTracker->create($jobId, 'CleanupInactiveUsers', []);

$topic = $context->createTopic('app_tasks');
$message = $context->createMessage(json_encode($payload));
$context->createProducer()->send($topic, $message);

echo "Scheduled periodic job: CleanupInactiveUsersJob with ID {$jobId}\n";
*/
?>