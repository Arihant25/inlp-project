<?php

// --- Domain Schema & Mocks ---

// PHP 8.1+ Enums for type safety
enum UserRole {
    case ADMIN;
    case USER;
}

enum PostStatus {
    case DRAFT;
    case PUBLISHED;
}

class User {
    public function __construct(
        public string $id,
        public string $email,
        public string $password_hash,
        public UserRole $role,
        public bool $is_active,
        public int $created_at
    ) {}
}

class Post {
    public function __construct(
        public string $id,
        public string $user_id,
        public string $title,
        public string $content,
        public PostStatus $status
    ) {}
}

// --- Utility Functions ---

function generate_uuid(): string {
    return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x',
        mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff),
        mt_rand(0, 0x0fff) | 0x4000, mt_rand(0, 0x3fff) | 0x8000,
        mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)
    );
}

// --- Mock External Services ---

function mock_send_email(string $to, string $subject, string $body): bool {
    echo "Sending email to {$to} with subject '{$subject}'...\n";
    sleep(1); // Simulate network latency
    // Simulate a transient failure for demonstration
    if (rand(1, 4) === 1) {
        echo "ERROR: Failed to send email to {$to}.\n";
        return false;
    }
    echo "SUCCESS: Email sent to {$to}.\n";
    return true;
}

function mock_process_image(string $imagePath): bool {
    echo "Processing image: {$imagePath}...\n";
    echo "  - Resizing to 1024x768...\n";
    sleep(1);
    echo "  - Applying watermark...\n";
    sleep(1);
    echo "  - Compressing...\n";
    sleep(1);
    echo "SUCCESS: Image processing complete for {$imagePath}.\n";
    return true;
}

// --- Background Job System (Procedural/Functional Approach) ---

const JOB_STATUS_PENDING = 'pending';
const JOB_STATUS_RUNNING = 'running';
const JOB_STATUS_COMPLETED = 'completed';
const JOB_STATUS_FAILED = 'failed';

// In-memory data structures
$job_queue = [];
$job_statuses = [];
$scheduled_tasks = [];

function add_job(string $type, array $payload, int $max_attempts = 3, int $delay_seconds = 0): string {
    global $job_queue, $job_statuses;
    $job_id = generate_uuid();
    $job = [
        'id' => $job_id,
        'type' => $type,
        'payload' => $payload,
        'status' => JOB_STATUS_PENDING,
        'attempts' => 0,
        'max_attempts' => $max_attempts,
        'execute_at' => time() + $delay_seconds,
    ];
    $job_queue[] = $job;
    $job_statuses[$job_id] = JOB_STATUS_PENDING;
    echo "QUEUED: Job {$job_id} ({$type})\n";
    return $job_id;
}

function add_periodic_task(string $type, array $payload, int $interval_seconds): string {
    global $scheduled_tasks;
    $task_id = generate_uuid();
    $scheduled_tasks[$task_id] = [
        'id' => $task_id,
        'type' => $type,
        'payload' => $payload,
        'interval' => $interval_seconds,
        'last_run' => 0,
    ];
    echo "SCHEDULED: Periodic task {$task_id} ({$type}) every {$interval_seconds}s\n";
    return $task_id;
}

function get_job_status(string $job_id): ?string {
    global $job_statuses;
    return $job_statuses[$job_id] ?? null;
}

function run_worker(): void {
    global $job_queue, $job_statuses, $scheduled_tasks;
    echo "Worker started. Waiting for jobs...\n";

    while (true) {
        // Check for scheduled periodic tasks
        foreach ($scheduled_tasks as &$task) {
            if (time() >= ($task['last_run'] + $task['interval'])) {
                add_job($task['type'], $task['payload'], 1); // Periodic tasks usually don't retry
                $task['last_run'] = time();
                echo "TRIGGERED: Periodic task {$task['id']} ({$task['type']})\n";
            }
        }
        unset($task);

        if (empty($job_queue)) {
            sleep(1);
            continue;
        }

        $job = array_shift($job_queue);

        if (time() < $job['execute_at']) {
            // Not time to run yet, put it back at the end
            array_push($job_queue, $job);
            continue;
        }

        $job_id = $job['id'];
        $job_statuses[$job_id] = JOB_STATUS_RUNNING;
        $job['attempts']++;
        echo "PROCESSING: Job {$job_id} ({$job['type']}), Attempt {$job['attempts']}/{$job['max_attempts']}\n";

        $success = false;
        try {
            switch ($job['type']) {
                case 'send_welcome_email':
                    $success = mock_send_email($job['payload']['email'], 'Welcome!', 'Thanks for signing up.');
                    break;
                case 'process_post_image':
                    $success = mock_process_image($job['payload']['image_path']);
                    break;
                case 'cleanup_logs':
                    echo "Running log cleanup...\n";
                    sleep(1);
                    echo "Logs cleaned.\n";
                    $success = true;
                    break;
                default:
                    echo "ERROR: Unknown job type '{$job['type']}'\n";
                    $success = false; // Treat as failed
            }
        } catch (Exception $e) {
            echo "FATAL ERROR executing job {$job_id}: " . $e->getMessage() . "\n";
            $success = false;
        }

        if ($success) {
            $job_statuses[$job_id] = JOB_STATUS_COMPLETED;
            echo "COMPLETED: Job {$job_id}\n";
        } else {
            if ($job['attempts'] < $job['max_attempts']) {
                $delay = 2 ** $job['attempts']; // Exponential backoff
                $job['execute_at'] = time() + $delay;
                array_push($job_queue, $job);
                $job_statuses[$job_id] = JOB_STATUS_PENDING;
                echo "RETRYING: Job {$job_id} will be retried in {$delay} seconds.\n";
            } else {
                $job_statuses[$job_id] = JOB_STATUS_FAILED;
                echo "FAILED: Job {$job_id} reached max attempts.\n";
            }
        }
    }
}

// --- Main Application Logic ---

// Create a new user, which triggers a welcome email job
$newUser = new User(generate_uuid(), 'alice@example.com', 'hash123', UserRole::USER, true, time());
$emailJobId = add_job('send_welcome_email', ['email' => $newUser->email], 5);

// Create a new post, which triggers an image processing job
$newPost = new Post(generate_uuid(), $newUser->id, 'My First Post', 'Hello world!', PostStatus::DRAFT);
add_job('process_post_image', ['image_path' => '/uploads/post_header.jpg']);

// Schedule a periodic task
add_periodic_task('cleanup_logs', [], 10); // Run every 10 seconds

// In a real app, the worker would run in a separate process.
// Here we run it directly for demonstration.
run_worker();

?>