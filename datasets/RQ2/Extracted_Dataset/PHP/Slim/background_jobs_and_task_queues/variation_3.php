<?php

// VARIATION 3: The "Functional/Procedural" Developer
// STYLE: Uses closures for routes, simple arrays for jobs, a central worker function.
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
            "FunctionalApp\\": "src/"
        }
    }
}
*/

// --- FILE: src/bootstrap.php ---
namespace FunctionalApp;

use DI\Container;
use Enqueue\SimpleClient\SimpleClient;
use Interop\Queue\Context;
use Ramsey\Uuid\Uuid;

// Mock Domain Models
class User { public $id, $email, $password_hash, $role, $is_active, $created_at; }
class Post { public $id, $user_id, $title, $content, $status; }

// Simple file-based KV store for job status
function get_job_status(string $job_id): ?array {
    $path = "/tmp/fn_jobs/{$job_id}.json";
    return file_exists($path) ? json_decode(file_get_contents($path), true) : null;
}

function update_job_status(string $job_id, array $data): void {
    if (!is_dir('/tmp/fn_jobs')) mkdir('/tmp/fn_jobs');
    $path = "/tmp/fn_jobs/{$job_id}.json";
    $existing_data = get_job_status($job_id) ?? [];
    file_put_contents($path, json_encode(array_merge($existing_data, $data), JSON_PRETTY_PRINT));
}

function create_job_status(string $job_id, string $type, array $payload): void {
    update_job_status($job_id, [
        'id' => $job_id,
        'type' => $type,
        'status' => 'pending',
        'payload' => $payload,
        'created_at' => date('c'),
        'logs' => [],
        'attempts' => 0
    ]);
}

function log_to_job(string $job_id, string $message): void {
    $job = get_job_status($job_id);
    if ($job) {
        $job['logs'][] = date('c') . ": " . $message;
        update_job_status($job_id, ['logs' => $job['logs']]);
    }
}

// DI Container Setup
function create_container(): Container {
    $container = new Container();
    $container->set(Context::class, function() {
        return (new SimpleClient('file:///tmp/enqueue_fn?file_extension=.json'))->getQueueContext();
    });
    return $container;
}

// --- FILE: public/index.php ---
/*
<?php
require __DIR__ . '/../vendor/autoload.php';

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Slim\Factory\AppFactory;
use Interop\Queue\Context;
use Ramsey\Uuid\Uuid;

// To run:
// 1. composer install
// 2. php -S localhost:8080 -t public

$container = FunctionalApp\create_container();
AppFactory::setContainer($container);
$app = AppFactory::create();
$app->addBodyParsingMiddleware();

// Route to send a welcome email
$app->post('/users', function (Request $request, Response $response, Context $queue) {
    $params = (array)$request->getParsedBody();
    $email = $params['email'] ?? 'functional.dev@example.com';
    
    $job_id = Uuid::uuid4()->toString();
    $job_payload = ['type' => 'send_welcome_email', 'data' => ['email' => $email, 'job_id' => $job_id]];

    FunctionalApp\create_job_status($job_id, 'send_welcome_email', ['email' => $email]);

    $queue->createProducer()->send(
        $queue->createQueue('task_queue'),
        $queue->createMessage(json_encode($job_payload))
    );

    $response->getBody()->write(json_encode(['message' => 'User created, welcome email queued.', 'job_id' => $job_id]));
    return $response->withHeader('Content-Type', 'application/json')->withStatus(202);
});

// Route to process an image
$app->post('/posts', function (Request $request, Response $response, Context $queue) {
    $params = (array)$request->getParsedBody();
    $image_url = $params['image_url'] ?? 'http://example.com/img.jpg';
    $post_id = Uuid::uuid4()->toString();

    $job_id = Uuid::uuid4()->toString();
    $job_payload = ['type' => 'process_image', 'data' => ['post_id' => $post_id, 'image_url' => $image_url, 'job_id' => $job_id]];
    
    FunctionalApp\create_job_status($job_id, 'process_image', ['post_id' => $post_id]);

    $queue->createProducer()->send(
        $queue->createQueue('task_queue'),
        $queue->createMessage(json_encode($job_payload))
    );

    $response->getBody()->write(json_encode(['message' => 'Post created, image processing queued.', 'job_id' => $job_id]));
    return $response->withHeader('Content-Type', 'application/json')->withStatus(202);
});

// Route to check job status
$app->get('/jobs/{id}', function (Request $request, Response $response, $args) {
    $job_id = $args['id'];
    $status = FunctionalApp\get_job_status($job_id);

    if (!$status) {
        $response->getBody()->write(json_encode(['error' => 'Not Found']));
        return $response->withHeader('Content-Type', 'application/json')->withStatus(404);
    }

    $response->getBody()->write(json_encode($status));
    return $response->withHeader('Content-Type', 'application/json');
});

$app->run();
*/

// --- FILE: worker.php ---
/*
<?php
require __DIR__ . '/vendor/autoload.php';

use Interop\Queue\Context;
use Interop\Queue\Message;

// To run:
// php worker.php

echo "Functional worker is running...\n";

$container = FunctionalApp\create_container();
$queue = $container->get(Context::class);
$task_queue = $queue->createQueue('task_queue');
$consumer = $queue->createConsumer($task_queue);

// --- Job Logic Definitions ---

$job_processors = [
    'send_welcome_email' => function (array $data) {
        $job_id = $data['job_id'];
        FunctionalApp\log_to_job($job_id, "Processing welcome email for {$data['email']}");
        echo "Sending email to {$data['email']}...\n";
        sleep(2);
        
        // Simulate random failure for retry demo
        if (rand(0, 3) === 0) {
            throw new Exception("Email service is down");
        }
        
        FunctionalApp\log_to_job($job_id, "Email sent successfully.");
        echo "Email sent.\n";
    },
    'process_image' => function (array $data) {
        $job_id = $data['job_id'];
        FunctionalApp\log_to_job($job_id, "Starting image pipeline for post {$data['post_id']}");
        echo "Processing image from {$data['image_url']}...\n";
        sleep(2);
        FunctionalApp\log_to_job($job_id, "Resizing image...");
        echo "Resized.\n";
        sleep(2);
        FunctionalApp\log_to_job($job_id, "Adding watermark...");
        echo "Watermarked.\n";
        sleep(1);
        FunctionalApp\log_to_job($job_id, "Pipeline complete.");
        echo "Image processing complete.\n";
    },
    'cleanup_routine' => function (array $data) {
        $job_id = $data['job_id'];
        FunctionalApp\log_to_job($job_id, "Running daily cleanup.");
        echo "Running cleanup routine...\n";
        sleep(5);
        FunctionalApp\log_to_job($job_id, "Cleanup finished.");
        echo "Cleanup finished.\n";
    }
];

// --- Main Worker Loop ---

while (true) {
    $message = $consumer->receive();
    if (!$message) continue;

    $payload = json_decode($message->getBody(), true);
    $job_type = $payload['type'] ?? 'unknown';
    $job_data = $payload['data'] ?? [];
    $job_id = $job_data['job_id'] ?? null;

    if (!$job_id || !isset($job_processors[$job_type])) {
        echo "Invalid job received. Rejecting.\n";
        $consumer->reject($message, false);
        continue;
    }

    try {
        $attempts = ($message->getProperty('attempts') ?? 0) + 1;
        FunctionalApp\update_job_status($job_id, ['status' => 'running', 'attempts' => $attempts]);
        
        $job_processors[$job_type]($job_data);
        
        FunctionalApp\update_job_status($job_id, ['status' => 'completed']);
        $consumer->acknowledge($message);
    } catch (Exception $e) {
        FunctionalApp\log_to_job($job_id, "ERROR: " . $e->getMessage());
        echo "Job failed: " . $e->getMessage() . "\n";

        // Exponential backoff retry logic
        if ($attempts < 5) {
            $delay = pow(2, $attempts) * 1000; // 2, 4, 8, 16 seconds
            FunctionalApp\update_job_status($job_id, ['status' => 'retrying']);
            
            $retry_message = $queue->createMessage($message->getBody(), ['attempts' => $attempts]);
            $queue->createProducer()->setDeliveryDelay($delay)->send($task_queue, $retry_message);
            
            echo "Job will be retried in {$delay}ms.\n";
        } else {
            FunctionalApp\update_job_status($job_id, ['status' => 'failed']);
            echo "Job failed permanently after {$attempts} attempts.\n";
        }
        $consumer->reject($message, false);
    }
}
*/

// --- FILE: cron.php ---
/*
<?php
require __DIR__ . '/vendor/autoload.php';

use Interop\Queue\Context;
use Ramsey\Uuid\Uuid;

// To run:
// php cron.php

echo "Functional cron job dispatcher running...\n";

$container = FunctionalApp\create_container();
$queue = $container->get(Context::class);

$job_id = Uuid::uuid4()->toString();
$job_payload = ['type' => 'cleanup_routine', 'data' => ['job_id' => $job_id, 'scheduled_at' => time()]];

FunctionalApp\create_job_status($job_id, 'cleanup_routine', []);

$queue->createProducer()->send(
    $queue->createQueue('task_queue'),
    $queue->createMessage(json_encode($job_payload))
);

echo "Scheduled 'cleanup_routine' job with ID: {$job_id}\n";
*/
?>