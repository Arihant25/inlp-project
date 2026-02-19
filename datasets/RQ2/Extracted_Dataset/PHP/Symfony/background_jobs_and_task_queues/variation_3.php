<?php

namespace App\Variation3;

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

// --- Shared Enums ---
namespace App\Variation3\Shared\Enum;
enum UserRole: string { case ADMIN = 'admin'; case USER = 'user'; }
enum PostStatus: string { case DRAFT = 'draft'; case PUBLISHED = 'published'; }
enum TaskStatus: string { case PENDING = 'pending'; case IN_PROGRESS = 'in_progress'; case COMPLETED = 'completed'; case FAILED = 'failed'; }

// --- Shared Entities ---
namespace App\Variation3\Shared\Entity;
use App\Variation3\Shared\Enum\PostStatus;
use App\Variation3\Shared\Enum\TaskStatus;
use App\Variation3\Shared\Enum\UserRole;
use DateTimeImmutable;
use Symfony\Component\Uid\Uuid;

class User {
    public function __construct(public Uuid $id, public string $email, public string $password_hash, public UserRole $role, public bool $is_active, public DateTimeImmutable $created_at) {}
}
class Post {
    public function __construct(public Uuid $id, public Uuid $user_id, public string $title, public string $content, public PostStatus $status) {}
}
class AsyncTask {
    public function __construct(
        public Uuid $id,
        public string $jobName,
        public TaskStatus $status = TaskStatus::PENDING,
        public ?Uuid $correlationId = null,
        public ?string $failureReason = null
    ) {}
    public function getId(): Uuid { return $this->id; }
    public function setStatus(TaskStatus $status): void { $this->status = $status; }
    public function setFailureReason(string $reason): void { $this->failureReason = $reason; }
}

// --- Mock Interfaces ---
namespace App\Variation3\Shared\Library;
use Doctrine\ORM\EntityManagerInterface;
use Psr\Log\LoggerInterface;
use Symfony\Component\Mailer\MailerInterface;
interface ORM extends EntityManagerInterface {}
interface Mailer extends MailerInterface {}
interface Logger extends LoggerInterface {}

/*
================================================================================
 CONFIGURATION (messenger.yaml)
================================================================================

framework:
    messenger:
        transports:
            notifications:
                dsn: '%env(MESSENGER_TRANSPORT_DSN)%'
                retry_strategy: { max_retries: 5, delay: 2000, multiplier: 2 }
            image_processing:
                dsn: '%env(MESSENGER_TRANSPORT_DSN)%'
                retry_strategy: { max_retries: 2, delay: 10000 }
            reports:
                dsn: '%env(MESSENGER_TRANSPORT_DSN)%'
                retry_strategy: { max_retries: 0 } # Fail fast

        routing:
            'App\Variation3\Notification\Message\*': notifications
            'App\Variation3\PostProcessing\Message\*': image_processing
            'App\Variation3\Reporting\Message\*': reports
*/

// ================================================================================
// SHARED SERVICES
// ================================================================================

namespace App\Variation3\Shared\Service;

use App\Variation3\Shared\Entity\AsyncTask;
use App\Variation3\Shared\Enum\TaskStatus;
use App\Variation3\Shared\Library\ORM;
use Symfony\Component\Uid\Uuid;

class TaskStatusUpdater
{
    public function __construct(private readonly ORM $orm) {}

    public function createTask(string $name, ?Uuid $correlationId = null): AsyncTask
    {
        $task = new AsyncTask(Uuid::v4(), $name, TaskStatus::PENDING, $correlationId);
        $this->orm->persist($task);
        return $task;
    }

    public function updateStatus(Uuid $taskId, TaskStatus $status, ?string $reason = null): void
    {
        $task = $this->orm->find(AsyncTask::class, $taskId);
        if ($task) {
            $task->setStatus($status);
            if ($reason) {
                $task->setFailureReason($reason);
            }
        }
    }
}

// ================================================================================
// NOTIFICATION DOMAIN
// ================================================================================

namespace App\Variation3\Notification\Message;
use Symfony\Component\Uid\Uuid;
final class SendWelcomeEmail { public function __construct(public Uuid $userId, public Uuid $taskId) {} }

namespace App\Variation3\Notification\Handler;

use App\Variation3\Notification\Message\SendWelcomeEmail;
use App\Variation3\Shared\Entity\User;
use App\Variation3\Shared\Enum\TaskStatus;
use App\Variation3\Shared\Library\Logger;
use App\Variation3\Shared\Library\Mailer;
use App\Variation3\Shared\Library\ORM;
use App\Variation3\Shared\Service\TaskStatusUpdater;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
final class SendWelcomeEmailHandler
{
    public function __construct(
        private readonly Mailer $mailer,
        private readonly ORM $orm,
        private readonly TaskStatusUpdater $taskUpdater,
        private readonly Logger $logger
    ) {}

    public function __invoke(SendWelcomeEmail $message): void
    {
        $this->taskUpdater->updateStatus($message->taskId, TaskStatus::IN_PROGRESS);
        $user = $this->orm->find(User::class, $message->userId);
        if (!$user) {
            $this->taskUpdater->updateStatus($message->taskId, TaskStatus::FAILED, 'User not found');
            return;
        }
        try {
            // $this->mailer->send(...);
            $this->logger->info("Welcome email sent to {$user->email}");
            $this->taskUpdater->updateStatus($message->taskId, TaskStatus::COMPLETED);
        } catch (\Exception $e) {
            $this->taskUpdater->updateStatus($message->taskId, TaskStatus::FAILED, $e->getMessage());
            throw $e;
        }
    }
}

// ================================================================================
// POST PROCESSING DOMAIN (Chained Jobs)
// ================================================================================

namespace App\Variation3\PostProcessing\Message;
use Symfony\Component\Uid\Uuid;
final class ResizeImage { public function __construct(public Uuid $postId, public string $path, public Uuid $taskId, public Uuid $correlationId) {} }
final class ApplyWatermark { public function __construct(public Uuid $postId, public string $path, public Uuid $taskId, public Uuid $correlationId) {} }
final class GenerateThumbnails { public function __construct(public Uuid $postId, public string $path, public Uuid $taskId, public Uuid $correlationId) {} }

namespace App\Variation3\PostProcessing\Service;

use App\Variation3\PostProcessing\Message\ResizeImage;
use App\Variation3\Shared\Service\TaskStatusUpdater;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Uuid;

class PostImageOrchestrator
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly TaskStatusUpdater $taskUpdater
    ) {}

    public function startProcessing(Uuid $postId, string $imagePath): void
    {
        $correlationId = Uuid::v4(); // ID for the entire pipeline
        $task = $this->taskUpdater->createTask(ResizeImage::class, $correlationId);
        $this->bus->dispatch(new ResizeImage($postId, $imagePath, $task->getId(), $correlationId));
    }
}

namespace App\Variation3\PostProcessing\Handler;

use App\Variation3\PostProcessing\Message\ApplyWatermark;
use App\Variation3\PostProcessing\Message\GenerateThumbnails;
use App\Variation3\PostProcessing\Message\ResizeImage;
use App\Variation3\Shared\Enum\TaskStatus;
use App\Variation3\Shared\Library\Logger;
use App\Variation3\Shared\Service\TaskStatusUpdater;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
final class ImageProcessingHandler
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly TaskStatusUpdater $taskUpdater,
        private readonly Logger $logger
    ) {}

    public function __invoke(ResizeImage|ApplyWatermark|GenerateThumbnails $message): void
    {
        $this->taskUpdater->updateStatus($message->taskId, TaskStatus::IN_PROGRESS);
        try {
            match ($message::class) {
                ResizeImage::class => $this->handleResize($message),
                ApplyWatermark::class => $this->handleWatermark($message),
                GenerateThumbnails::class => $this->handleThumbnails($message),
            };
        } catch (\Exception $e) {
            $this->taskUpdater->updateStatus($message->taskId, TaskStatus::FAILED, $e->getMessage());
            throw $e;
        }
    }

    private function handleResize(ResizeImage $message): void
    {
        $this->logger->info("Resizing image for post {$message->postId}");
        sleep(2); // Simulate work
        $this->taskUpdater->updateStatus($message->taskId, TaskStatus::COMPLETED);

        // Chain to the next job
        $nextTask = $this->taskUpdater->createTask(ApplyWatermark::class, $message->correlationId);
        $this->bus->dispatch(new ApplyWatermark($message->postId, $message->path, $nextTask->getId(), $message->correlationId));
    }

    private function handleWatermark(ApplyWatermark $message): void
    {
        $this->logger->info("Applying watermark for post {$message->postId}");
        sleep(1); // Simulate work
        $this->taskUpdater->updateStatus($message->taskId, TaskStatus::COMPLETED);

        // Chain to the final job
        $nextTask = $this->taskUpdater->createTask(GenerateThumbnails::class, $message->correlationId);
        $this->bus->dispatch(new GenerateThumbnails($message->postId, $message->path, $nextTask->getId(), $message->correlationId));
    }

    private function handleThumbnails(GenerateThumbnails $message): void
    {
        $this->logger->info("Generating thumbnails for post {$message->postId}");
        sleep(2); // Simulate work
        $this->taskUpdater->updateStatus($message->taskId, TaskStatus::COMPLETED);
        $this->logger->info("Image processing pipeline completed for post {$message->postId}");
    }
}

// ================================================================================
// REPORTING DOMAIN
// ================================================================================

namespace App\Variation3\Reporting\Message;
use Symfony\Component\Uid\Uuid;
final class GenerateWeeklyPostReport { public function __construct(public Uuid $taskId) {} }

namespace App\Variation3\Reporting\Handler;

use App\Variation3\Reporting\Message\GenerateWeeklyPostReport;
use App\Variation3\Shared\Enum\TaskStatus;
use App\Variation3\Shared\Library\Logger;
use App\Variation3\Shared\Service\TaskStatusUpdater;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
final class GenerateWeeklyPostReportHandler
{
    public function __construct(
        private readonly TaskStatusUpdater $taskUpdater,
        private readonly Logger $logger
    ) {}

    public function __invoke(GenerateWeeklyPostReport $message): void
    {
        $this->taskUpdater->updateStatus($message->taskId, TaskStatus::IN_PROGRESS);
        try {
            $this->logger->info("Generating weekly post report...");
            sleep(10); // Simulate work
            $this->taskUpdater->updateStatus($message->taskId, TaskStatus::COMPLETED);
        } catch (\Exception $e) {
            $this->taskUpdater->updateStatus($message->taskId, TaskStatus::FAILED, $e->getMessage());
            throw $e;
        }
    }
}

// ================================================================================
// CONSOLE COMMAND
// ================================================================================

namespace App\Variation3\Reporting\Command;

use App\Variation3\Reporting\Message\GenerateWeeklyPostReport;
use App\Variation3\Shared\Service\TaskStatusUpdater;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsCommand(name: 'app:reporting:schedule', description: 'Schedules a report generation task.')]
class GenerateReportCommand extends Command
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly TaskStatusUpdater $taskUpdater
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $output->writeln('Scheduling weekly post report...');
        $task = $this->taskUpdater->createTask(GenerateWeeklyPostReport::class);
        $this->bus->dispatch(new GenerateWeeklyPostReport($task->getId()));
        $output->writeln("<info>Task {$task->getId()} scheduled.</info>");
        return Command::SUCCESS;
    }
}