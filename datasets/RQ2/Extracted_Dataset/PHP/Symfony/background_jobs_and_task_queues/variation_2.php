<?php

namespace App\Variation2;

use DateTimeImmutable;
use Doctrine\ORM\EntityManagerInterface;
use Psr\Log\LoggerInterface;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Mailer\MailerInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Uuid;

/*
================================================================================
 MOCK DEPENDENCIES & DOMAIN MODEL
================================================================================
This section contains mock objects and the domain model for compilability.
*/

// --- Enums ---
enum UserRole: string { case ADMIN = 'admin'; case USER = 'user'; }
enum PostStatus: string { case DRAFT = 'draft'; case PUBLISHED = 'published'; }
enum TaskState: string { case QUEUED = 'queued'; case PROCESSING = 'processing'; case DONE = 'done'; case FAILED = 'failed'; }

// --- Entities ---
class User {
    public function __construct(public Uuid $id, public string $email, public string $password_hash, public UserRole $role, public bool $is_active, public DateTimeImmutable $created_at) {}
}
class Post {
    public function __construct(public Uuid $id, public Uuid $user_id, public string $title, public string $content, public PostStatus $status) {}
}
class BackgroundTask {
    public Uuid $id;
    public string $task_type;
    public TaskState $state;
    public array $context;
    public ?string $error_message = null;
    public DateTimeImmutable $created_at;
    public ?DateTimeImmutable $updated_at = null;

    public function __construct(string $taskType, array $context) {
        $this->id = Uuid::v4();
        $this->task_type = $taskType;
        $this->context = $context;
        $this->state = TaskState::QUEUED;
        $this->created_at = new DateTimeImmutable();
    }
    public function getId(): Uuid { return $this->id; }
    public function setState(TaskState $state): self { $this->state = $state; $this->updated_at = new DateTimeImmutable(); return $this; }
    public function setErrorMessage(?string $msg): self { $this->error_message = $msg; return $this; }
}

// --- Mock Interfaces ---
interface MockEntityManager extends EntityManagerInterface {}
interface MockMailer extends MailerInterface {}
interface MockLogger extends LoggerInterface {}

/*
================================================================================
 CONFIGURATION (messenger.yaml)
================================================================================

framework:
    messenger:
        transports:
            async:
                dsn: '%env(MESSENGER_TRANSPORT_DSN)%'
                options:
                    # Example for RabbitMQ
                    exchange:
                        name: default
                        type: direct
                retry_strategy:
                    max_retries: 3
                    # 1s, 2s, 4s
                    delay: 1000
                    multiplier: 2
            
            long_running:
                dsn: '%env(MESSENGER_TRANSPORT_DSN)%'
                retry_strategy:
                    max_retries: 2
                    delay: 60000 # 1 minute

        routing:
            'App\Variation2\Task\NewUserEmail': async
            'App\Variation2\Task\PostImageJob': long_running
            'App\Variation2\Task\WeeklyReportTask': long_running
*/

// ================================================================================
// TASKS (Messages)
// ================================================================================

namespace App\Variation2\Task;

use Symfony\Component\Uid\Uuid;

final class NewUserEmail
{
    public function __construct(
        public readonly Uuid $userId,
        public readonly Uuid $taskId
    ) {}
}

final class PostImageJob
{
    public function __construct(
        public readonly Uuid $postId,
        public readonly string $imageLocation,
        public readonly Uuid $taskId
    ) {}
}

final class WeeklyReportTask
{
    public function __construct(
        public readonly Uuid $taskId
    ) {}
}

// ================================================================================
// SERVICES
// ================================================================================

namespace App\Variation2\Service;

use App\Variation2\BackgroundTask;
use App\Variation2\MockEntityManager;
use App\Variation2\TaskState;
use Symfony\Component\Uid\Uuid;

class TaskTracker
{
    public function __construct(private MockEntityManager $em) {}

    public function createTask(string $type, array $context): BackgroundTask
    {
        $task = new BackgroundTask($type, $context);
        $this->em->persist($task);
        // $this->em->flush();
        return $task;
    }

    public function updateTaskState(Uuid $taskId, TaskState $state, ?string $errorMessage = null): void
    {
        $task = $this->em->find(BackgroundTask::class, $taskId);
        if ($task) {
            $task->setState($state)->setErrorMessage($errorMessage);
        }
    }
}

// ================================================================================
// HANDLERS (Workers)
// ================================================================================

namespace App\Variation2\Handler;

use App\Variation2\MockEntityManager;
use App\Variation2\MockLogger;
use App\Variation2\MockMailer;
use App\Variation2\Service\TaskTracker;
use App\Variation2\Task\NewUserEmail;
use App\Variation2\Task\PostImageJob;
use App\Variation2\Task\WeeklyReportTask;
use App\Variation2\TaskState;
use App\Variation2\User;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class NewUserEmailHandler
{
    public function __construct(
        private MockMailer $mailer,
        private MockEntityManager $em,
        private TaskTracker $tracker,
        private MockLogger $log
    ) {}

    public function __invoke(NewUserEmail $task): void
    {
        $this->tracker->updateTaskState($task->taskId, TaskState::PROCESSING);
        $user = $this->em->find(User::class, $task->userId);

        if (!$user) {
            $this->tracker->updateTaskState($task->taskId, TaskState::FAILED, 'User not found');
            return;
        }

        try {
            $this->log->info('Sending welcome email', ['user' => $user->email]);
            // $this->mailer->send(...);
            $this->tracker->updateTaskState($task->taskId, TaskState::DONE);
        } catch (\Exception $e) {
            $this->tracker->updateTaskState($task->taskId, TaskState::FAILED, $e->getMessage());
            throw $e; // Re-throw for retry logic
        }
    }
}

#[AsMessageHandler]
class PostImageJobHandler
{
    public function __construct(
        private TaskTracker $tracker,
        private MockLogger $log
    ) {}

    public function __invoke(PostImageJob $job): void
    {
        $this->tracker->updateTaskState($job->taskId, TaskState::PROCESSING);
        $this->log->info('Processing post image', ['post' => $job->postId, 'image' => $job->imageLocation]);

        try {
            // Simulate a multi-step process within one handler
            $this->resize($job->imageLocation);
            $this->watermark($job->imageLocation);
            $this->generateThumbnails($job->imageLocation);

            $this->tracker->updateTaskState($job->taskId, TaskState::DONE);
            $this->log->info('Finished processing post image', ['post' => $job->postId]);
        } catch (\Exception $e) {
            $this->tracker->updateTaskState($job->taskId, TaskState::FAILED, $e->getMessage());
            throw $e;
        }
    }

    private function resize(string $path): void { sleep(2); $this->log->debug('Resized', ['path' => $path]); }
    private function watermark(string $path): void { sleep(1); $this->log->debug('Watermarked', ['path' => $path]); }
    private function generateThumbnails(string $path): void { sleep(2); $this->log->debug('Thumbnails generated', ['path' => $path]); }
}

#[AsMessageHandler]
class WeeklyReportTaskHandler
{
    public function __construct(
        private TaskTracker $tracker,
        private MockLogger $log
    ) {}

    public function __invoke(WeeklyReportTask $task): void
    {
        $this->tracker->updateTaskState($task->taskId, TaskState::PROCESSING);
        $this->log->info('Starting weekly report generation');

        try {
            // Simulate long-running task
            sleep(15);
            // ... logic to generate and save the report ...
            $this->tracker->updateTaskState($task->taskId, TaskState::DONE);
            $this->log->info('Weekly report generated successfully');
        } catch (\Exception $e) {
            $this->tracker->updateTaskState($task->taskId, TaskState::FAILED, $e->getMessage());
            throw $e;
        }
    }
}

// ================================================================================
// CONSOLE COMMAND (Scheduler)
// ================================================================================

namespace App\Variation2\Command;

use App\Variation2\Service\TaskTracker;
use App\Variation2\Task\WeeklyReportTask;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsCommand(name: 'app:queue-periodic-jobs', description: 'Queues jobs that need to run on a schedule.')]
class SchedulerCommand extends Command
{
    public function __construct(
        private MessageBusInterface $bus,
        private TaskTracker $tracker
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $output->writeln('Queueing weekly report task...');

        $task = $this->tracker->createTask(WeeklyReportTask::class, []);
        $this->bus->dispatch(new WeeklyReportTask($task->getId()));

        $output->writeln("<info>Task queued with ID: {$task->getId()}</info>");

        return Command::SUCCESS;
    }
}