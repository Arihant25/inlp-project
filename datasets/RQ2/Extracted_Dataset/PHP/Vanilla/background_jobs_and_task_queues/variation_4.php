<?php

// --- Domain Schema ---

enum UserRoleType { case ADMIN; case USER; }
enum PostStatusType { case DRAFT; case PUBLISHED; }

final class UserDTO {
    public function __construct(
        public readonly string $id,
        public readonly string $email,
        public readonly UserRoleType $role,
    ) {}
}

final class PostDTO {
    public function __construct(
        public readonly string $id,
        public readonly string $userId,
        public readonly PostStatusType $status,
    ) {}
}

// --- Core Job System Interfaces (Interface-driven Design) ---

interface Job {
    public function handle(): bool;
}

interface Schedulable {
    public static function getInterval(): int; // in seconds
}

enum JobState: string {
    case PENDING = 'pending';
    case PROCESSING = 'processing';
    case SUCCEEDED = 'succeeded';
    case FAILED = 'failed';
}

final class JobEnvelope {
    public readonly string $id;
    public int $attempts = 0;
    public int $executeAfterTimestamp;

    public function __construct(
        public readonly Job $job,
        public readonly int $maxAttempts = 3
    ) {
        $this->id = spl_object_hash($job) . '-' . microtime(true);
        $this->executeAfterTimestamp = time();
    }

    public function calculateNextAttemptDelay(): int {
        // Exponential backoff: 2, 4, 8, 16...
        return 2 ** $this->attempts;
    }
}

// --- Concrete Job Implementations ---

class SendWelcomeEmailJob implements Job {
    public function __construct(private readonly UserDTO $user) {}

    public function handle(): bool {
        echo "Handler: Sending welcome email to {$this->user->email}...\n";
        sleep(1);
        // Simulate transient failure
        if (mt_rand(1, 100) > 60) {
            echo "Handler: SMTP connection failed for {$this->user->email}.\n";
            return false;
        }
        echo "Handler: Email successfully sent to {$this->user->email}.\n";
        return true;
    }
}

class ProcessPostImageJob implements Job {
    public function __construct(private readonly string $imagePath) {}

    public function handle(): bool {
        echo "Handler: Processing image at '{$this->imagePath}'...\n";
        sleep(2);
        echo "Handler: Resized, watermarked, and compressed '{$this->imagePath}'.\n";
        return true;
    }
}

class PruneOldPostsJob implements Job, Schedulable {
    public function handle(): bool {
        echo "Handler: [PERIODIC] Pruning posts older than 90 days...\n";
        sleep(1);
        echo "Handler: [PERIODIC] Pruning complete.\n";
        return true;
    }

    public static function getInterval(): int {
        return 20; // Run every 20 seconds for demonstration
    }
}

// --- Worker and Queue Implementation ---

final class InMemoryTaskRunner {
    /** @var JobEnvelope[] */
    private array $task_queue = [];
    /** @var array<string, JobState> */
    private array $task_status_map = [];
    /** @var array<string, array{job: Schedulable, last_run: int}> */
    private array $scheduled_tasks = [];

    public function submit(Job $job, int $max_attempts = 3): string {
        $envelope = new JobEnvelope($job, $max_attempts);
        $this->task_queue[] = $envelope;
        $this->task_status_map[$envelope->id] = JobState::PENDING;
        echo "Runner: Submitted job {$envelope->id} of type " . get_class($job) . "\n";
        return $envelope->id;
    }

    public function schedule(string $schedulableClass): void {
        if (!is_subclass_of($schedulableClass, Schedulable::class) || !is_subclass_of($schedulableClass, Job::class)) {
            throw new InvalidArgumentException("Class must implement Schedulable and Job.");
        }
        $interval = $schedulableClass::getInterval();
        $this->scheduled_tasks[$schedulableClass] = [
            'class' => $schedulableClass,
            'last_run' => 0,
            'interval' => $interval
        ];
        echo "Runner: Scheduled task {$schedulableClass} to run every {$interval}s.\n";
    }

    public function getJobState(string $job_id): ?JobState {
        return $this->task_status_map[$job_id] ?? null;
    }

    public function listen(): void {
        echo "Runner: Starting main loop. Press Ctrl+C to exit.\n";
        while (true) {
            $this->enqueueScheduledTasks();

            $envelope = $this->findRunnableTask();
            if ($envelope) {
                $this->processTask($envelope);
            } else {
                // Prevent busy-waiting
                sleep(1);
            }
        }
    }

    private function enqueueScheduledTasks(): void {
        $now = time();
        foreach ($this->scheduled_tasks as $key => &$task) {
            if ($now >= ($task['last_run'] + $task['interval'])) {
                $jobClass = $task['class'];
                $this->submit(new $jobClass(), 1); // Scheduled tasks typically don't retry
                $task['last_run'] = $now;
            }
        }
    }

    private function findRunnableTask(): ?JobEnvelope {
        $now = time();
        foreach ($this->task_queue as $key => $envelope) {
            if ($now >= $envelope->executeAfterTimestamp) {
                // Remove from queue and return
                unset($this->task_queue[$key]);
                $this->task_queue = array_values($this->task_queue);
                return $envelope;
            }
        }
        return null;
    }

    private function processTask(JobEnvelope $envelope): void {
        $this->task_status_map[$envelope->id] = JobState::PROCESSING;
        $envelope->attempts++;
        echo "Runner: Processing job {$envelope->id} (Attempt {$envelope->attempts}/{$envelope->maxAttempts}).\n";

        try {
            $success = $envelope->job->handle();
        } catch (Throwable $e) {
            echo "Runner: CRITICAL - Job {$envelope->id} threw an exception: {$e->getMessage()}\n";
            $success = false;
        }

        if ($success) {
            $this->task_status_map[$envelope->id] = JobState::SUCCEEDED;
            echo "Runner: Job {$envelope->id} SUCCEEDED.\n";
        } elseif ($envelope->attempts < $envelope->maxAttempts) {
            $delay = $envelope->calculateNextAttemptDelay();
            $envelope->executeAfterTimestamp = time() + $delay;
            $this->task_queue[] = $envelope; // Re-queue
            $this->task_status_map[$envelope->id] = JobState::PENDING;
            echo "Runner: Job {$envelope->id} failed. Re-queued for retry in {$delay}s.\n";
        } else {
            $this->task_status_map[$envelope->id] = JobState::FAILED;
            echo "Runner: Job {$envelope->id} FAILED permanently.\n";
        }
    }
}

// --- Main Application Logic ---

$runner = new InMemoryTaskRunner();

// Application events trigger jobs
$newUser = new UserDTO(uniqid(), 'diana@example.com', UserRoleType::ADMIN);
$emailJobId = $runner->submit(new SendWelcomeEmailJob($newUser), 5);

$newPost = new PostDTO(uniqid(), $newUser->id, PostStatusType::DRAFT);
$runner->submit(new ProcessPostImageJob('/data/images/post_banner.tiff'));

// Register periodic tasks
$runner->schedule(PruneOldPostsJob::class);

// In a real application, this would be a separate daemon process.
$runner->listen();

?>