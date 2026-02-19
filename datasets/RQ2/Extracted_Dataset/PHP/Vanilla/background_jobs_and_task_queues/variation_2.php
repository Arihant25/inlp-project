<?php

// --- Domain Schema & Mocks ---

enum UserRoleV2 {
    case ADMIN;
    case USER;
}

enum PostStatusV2 {
    case DRAFT;
    case PUBLISHED;
}

class UserV2 {
    public function __construct(
        public string $id,
        public string $email,
        public string $password_hash,
        public UserRoleV2 $role,
        public bool $is_active,
        public int $created_at
    ) {}
}

class PostV2 {
    public function __construct(
        public string $id,
        public string $user_id,
        public string $title,
        public string $content,
        public PostStatusV2 $status
    ) {}
}

// --- Utility ---
function generate_uuid_v2(): string {
    return bin2hex(random_bytes(16));
}

// --- Mock Services ---
class MailerService {
    public function send(string $recipient, string $subject, string $message): bool {
        echo "[Mailer] Attempting to send to <{$recipient}> subject '{$subject}'\n";
        sleep(1);
        if (random_int(0, 3) === 0) { // 25% chance of failure
            echo "[Mailer] ERROR: Failed to connect to SMTP server.\n";
            return false;
        }
        echo "[Mailer] SUCCESS: Email delivered to <{$recipient}>\n";
        return true;
    }
}

class ImageProcessorService {
    public function process(string $filePath): bool {
        echo "[ImageProcessor] Starting processing for '{$filePath}'\n";
        sleep(2); // Simulate heavy work
        echo "[ImageProcessor] SUCCESS: '{$filePath}' processed.\n";
        return true;
    }
}

// --- Background Job System (Classic OOP Approach) ---

enum JobStatusV2 {
    case PENDING;
    case RUNNING;
    case COMPLETED;
    case FAILED;
}

class Job {
    private string $id;
    private string $type;
    private array $payload;
    private JobStatusV2 $status;
    private int $attempts = 0;
    private int $maxAttempts;
    private int $executeAt;

    public function __construct(string $type, array $payload, int $maxAttempts = 3) {
        $this->id = generate_uuid_v2();
        $this->type = $type;
        $this->payload = $payload;
        $this->maxAttempts = $maxAttempts;
        $this->status = JobStatusV2::PENDING;
        $this->executeAt = time();
    }

    public function getId(): string { return $this->id; }
    public function getType(): string { return $this->type; }
    public function getPayload(): array { return $this->payload; }
    public function getStatus(): JobStatusV2 { return $this->status; }
    public function getAttempts(): int { return $this->attempts; }
    public function getMaxAttempts(): int { return $this->maxAttempts; }
    public function getExecuteAt(): int { return $this->executeAt; }

    public function setStatus(JobStatusV2 $status): void { $this->status = $status; }
    public function incrementAttempts(): void { $this->attempts++; }
    public function setExecuteAt(int $timestamp): void { $this->executeAt = $timestamp; }
}

class JobQueue {
    private array $queue = [];

    public function push(Job $job): void {
        $this->queue[] = $job;
    }

    public function pop(): ?Job {
        return array_shift($this->queue);
    }

    public function isEmpty(): bool {
        return empty($this->queue);
    }
}

class JobScheduler {
    private array $periodicTasks = [];

    public function addPeriodic(string $type, array $payload, int $intervalSeconds): void {
        $this->periodicTasks[] = [
            'type' => $type,
            'payload' => $payload,
            'interval' => $intervalSeconds,
            'last_run' => 0
        ];
        echo "[Scheduler] Added periodic task '{$type}' every {$intervalSeconds}s.\n";
    }

    public function getDueJobs(JobQueue $queue): void {
        foreach ($this->periodicTasks as &$task) {
            if (time() >= ($task['last_run'] + $task['interval'])) {
                $job = new Job($task['type'], $task['payload'], 1);
                $queue->push($job);
                $task['last_run'] = time();
                echo "[Scheduler] Queued periodic job '{$job->getType()}' ({$job->getId()}).\n";
            }
        }
    }
}

class Worker {
    private JobQueue $queue;
    private JobScheduler $scheduler;
    private array $jobStatusTracker = [];
    private MailerService $mailer;
    private ImageProcessorService $imageProcessor;

    public function __construct(JobQueue $queue, JobScheduler $scheduler) {
        $this->queue = $queue;
        $this->scheduler = $scheduler;
        $this->mailer = new MailerService();
        $this->imageProcessor = new ImageProcessorService();
    }

    public function start(): void {
        echo "[Worker] Starting event loop.\n";
        while (true) {
            $this->scheduler->getDueJobs($this->queue);

            $job = $this->queue->pop();
            if (!$job) {
                sleep(1);
                continue;
            }

            if (time() < $job->getExecuteAt()) {
                $this->queue->push($job); // Re-queue for later
                continue;
            }

            $this->execute($job);
        }
    }

    private function execute(Job $job): void {
        $job->setStatus(JobStatusV2::RUNNING);
        $job->incrementAttempts();
        $this->jobStatusTracker[$job->getId()] = $job->getStatus();
        echo "[Worker] Executing job {$job->getId()} ({$job->getType()}). Attempt {$job->getAttempts()}.\n";

        $isSuccess = false;
        try {
            $payload = $job->getPayload();
            switch ($job->getType()) {
                case 'SendWelcomeEmail':
                    $isSuccess = $this->mailer->send($payload['email'], 'Welcome Aboard!', '...');
                    break;
                case 'ProcessPostImage':
                    $isSuccess = $this->imageProcessor->process($payload['image_path']);
                    break;
                case 'DataRetentionPolicy':
                    echo "[Worker] Applying data retention policy...\n";
                    sleep(1);
                    $isSuccess = true;
                    break;
                default:
                    throw new \InvalidArgumentException("Unknown job type: {$job->getType()}");
            }
        } catch (\Throwable $e) {
            echo "[Worker] CRITICAL: Job {$job->getId()} threw exception: {$e->getMessage()}\n";
            $isSuccess = false;
        }

        if ($isSuccess) {
            $job->setStatus(JobStatusV2::COMPLETED);
            $this->jobStatusTracker[$job->getId()] = $job->getStatus();
            echo "[Worker] COMPLETED job {$job->getId()}.\n";
        } elseif ($job->getAttempts() < $job->getMaxAttempts()) {
            $this->rescheduleForRetry($job);
        } else {
            $job->setStatus(JobStatusV2::FAILED);
            $this->jobStatusTracker[$job->getId()] = $job->getStatus();
            echo "[Worker] FAILED job {$job->getId()} after max attempts.\n";
        }
    }

    private function rescheduleForRetry(Job $job): void {
        $delay = 5 * (2 ** ($job->getAttempts() - 1)); // Exponential backoff: 5s, 10s, 20s...
        $job->setExecuteAt(time() + $delay);
        $job->setStatus(JobStatusV2::PENDING);
        $this->queue->push($job);
        $this->jobStatusTracker[$job->getId()] = $job->getStatus();
        echo "[Worker] RETRYING job {$job->getId()} in {$delay} seconds.\n";
    }
    
    public function getJobStatus(string $jobId): ?JobStatusV2 {
        return $this->jobStatusTracker[$jobId] ?? null;
    }
}

// --- Main Application Logic ---

$jobQueue = new JobQueue();
$scheduler = new JobScheduler();

// Application triggers jobs
$user = new UserV2(generate_uuid_v2(), 'bob@example.com', 'hash456', UserRoleV2::USER, true, time());
$emailJob = new Job('SendWelcomeEmail', ['email' => $user->email], 4);
$jobQueue->push($emailJob);
echo "[App] Queued job {$emailJob->getId()} to welcome new user.\n";

$post = new PostV2(generate_uuid_v2(), $user->id, 'My Trip', 'It was great.', PostStatusV2::PUBLISHED);
$imageJob = new Job('ProcessPostImage', ['image_path' => '/var/data/trip.png']);
$jobQueue->push($imageJob);
echo "[App] Queued job {$imageJob->getId()} to process post image.\n";

// Configure scheduled tasks
$scheduler->addPeriodic('DataRetentionPolicy', [], 30); // Run every 30 seconds

// Start the worker (in a separate process in a real app)
$worker = new Worker($jobQueue, $scheduler);
$worker->start();

?>