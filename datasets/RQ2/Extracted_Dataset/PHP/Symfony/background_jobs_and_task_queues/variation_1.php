<?php

namespace App\Variation1;

use DateTimeImmutable;
use Doctrine\ORM\EntityManagerInterface;
use Psr\Log\LoggerInterface;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Mailer\MailerInterface;
use Symfony\Component\Messenger\Handler\MessageHandlerInterface;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Uuid;

/*
================================================================================
 MOCK DEPENDENCIES & DOMAIN MODEL
================================================================================
This section contains mock objects and the domain model for compilability.
*/

// --- Enums ---
enum UserRole: string {
    case ADMIN = 'admin';
    case USER = 'user';
}

enum PostStatus: string {
    case DRAFT = 'draft';
    case PUBLISHED = 'published';
}

enum JobStatus: string {
    case PENDING = 'pending';
    case RUNNING = 'running';
    case COMPLETED = 'completed';
    case FAILED = 'failed';
}

// --- Entities ---
class User {
    public function __construct(
        public Uuid $id,
        public string $email,
        public string $password_hash,
        public UserRole $role,
        public bool $is_active,
        public DateTimeImmutable $created_at
    ) {}
    public function getEmail(): string { return $this->email; }
}

class Post {
    public function __construct(
        public Uuid $id,
        public Uuid $user_id,
        public string $title,
        public string $content,
        public PostStatus $status
    ) {}
    public function getId(): Uuid { return $this->id; }
}

class Job {
    private Uuid $id;
    private string $type;
    private JobStatus $status;
    private array $payload;
    private ?string $result = null;
    private DateTimeImmutable $createdAt;
    private ?DateTimeImmutable $updatedAt = null;

    public function __construct(string $type, array $payload) {
        $this->id = Uuid::v4();
        $this->type = $type;
        $this->payload = $payload;
        $this->status = JobStatus::PENDING;
        $this->createdAt = new DateTimeImmutable();
    }
    public function getId(): Uuid { return $this->id; }
    public function setStatus(JobStatus $status): void { $this->status = $status; $this->updatedAt = new DateTimeImmutable(); }
    public function setResult(string $result): void { $this->result = $result; }
}

// --- Mock Interfaces ---
interface MockEntityManagerInterface extends EntityManagerInterface {}
interface MockMailerInterface extends MailerInterface {}
interface MockLoggerInterface extends LoggerInterface {}

/*
================================================================================
 CONFIGURATION (messenger.yaml)
================================================================================

framework:
    messenger:
        transports:
            # For high-priority, fast jobs like sending emails
            async_priority_high:
                dsn: '%env(MESSENGER_TRANSPORT_DSN)%'
                retry_strategy:
                    max_retries: 3
                    delay: 1000 # 1 second
                    multiplier: 2 # exponential backoff
                    max_delay: 0

            # For long-running, lower-priority jobs like image processing
            async_priority_low:
                dsn: '%env(MESSENGER_TRANSPORT_DSN)%'
                retry_strategy:
                    max_retries: 5
                    delay: 30000 # 30 seconds

        routing:
            'App\Variation1\Message\SendWelcomeEmailMessage': async_priority_high
            'App\Variation1\Message\ProcessPostImageMessage': async_priority_low
            'App\Variation1\Message\GenerateWeeklyReportMessage': async_priority_low
*/

// ================================================================================
// MESSAGES (Data Transfer Objects for the queue)
// ================================================================================

namespace App\Variation1\Message;

use Symfony\Component\Uid\Uuid;

class SendWelcomeEmailMessage
{
    public function __construct(
        public readonly Uuid $userId,
        public readonly Uuid $jobId
    ) {}
}

class ProcessPostImageMessage
{
    public function __construct(
        public readonly Uuid $postId,
        public readonly string $imagePath,
        public readonly Uuid $jobId
    ) {}
}

class GenerateWeeklyReportMessage
{
    public function __construct(
        public readonly Uuid $jobId
    ) {}
}

// ================================================================================
// SERVICES
// ================================================================================

namespace App\Variation1\Service;

use App\Variation1\Job;
use App\Variation1\JobStatus;
use App\Variation1\Message\ProcessPostImageMessage;
use App\Variation1\Message\SendWelcomeEmailMessage;
use App\Variation1\MockEntityManagerInterface;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Uuid;

class JobManager
{
    public function __construct(private MockEntityManagerInterface $entityManager) {}

    public function createJob(string $type, array $payload): Job
    {
        $job = new Job($type, $payload);
        $this->entityManager->persist($job);
        // In a real app, flush would be called later by the kernel or a transaction manager.
        // $this->entityManager->flush();
        return $job;
    }

    public function startJob(Uuid $jobId): ?Job
    {
        $job = $this->entityManager->find(Job::class, $jobId);
        if ($job) {
            $job->setStatus(JobStatus::RUNNING);
        }
        return $job;
    }

    public function completeJob(Uuid $jobId, string $result): void
    {
        $job = $this->entityManager->find(Job::class, $jobId);
        if ($job) {
            $job->setStatus(JobStatus::COMPLETED);
            $job->setResult($result);
        }
    }

    public function failJob(Uuid $jobId, string $error): void
    {
        $job = $this->entityManager->find(Job::class, $jobId);
        if ($job) {
            $job->setStatus(JobStatus::FAILED);
            $job->setResult($error);
        }
    }
}

class UserRegistrationService
{
    public function __construct(
        private MessageBusInterface $bus,
        private JobManager $jobManager
    ) {}

    public function registerUser(Uuid $userId): void
    {
        // ... user creation logic ...

        // Schedule async email sending
        $job = $this->jobManager->createJob(SendWelcomeEmailMessage::class, ['userId' => $userId]);
        $this->bus->dispatch(new SendWelcomeEmailMessage($userId, $job->getId()));
    }
}

class PostCreationService
{
    public function __construct(
        private MessageBusInterface $bus,
        private JobManager $jobManager
    ) {}

    public function createPostWithImage(Uuid $postId, string $imagePath): void
    {
        // ... post creation logic ...

        // Schedule async image processing
        $job = $this->jobManager->createJob(ProcessPostImageMessage::class, ['postId' => $postId, 'imagePath' => $imagePath]);
        $this->bus->dispatch(new ProcessPostImageMessage($postId, $imagePath, $job->getId()));
    }
}


// ================================================================================
// MESSAGE HANDLERS (The workers that process messages)
// ================================================================================

namespace App\Variation1\MessageHandler;

use App\Variation1\Message\GenerateWeeklyReportMessage;
use App\Variation1\Message\ProcessPostImageMessage;
use App\Variation1\Message\SendWelcomeEmailMessage;
use App\Variation1\MockEntityManagerInterface;
use App\Variation1\MockLoggerInterface;
use App\Variation1\MockMailerInterface;
use App\Variation1\Service\JobManager;
use App\Variation1\User;
use Symfony\Component\Messenger\Handler\MessageHandlerInterface;

class SendWelcomeEmailMessageHandler implements MessageHandlerInterface
{
    public function __construct(
        private MockMailerInterface $mailer,
        private MockEntityManagerInterface $entityManager,
        private JobManager $jobManager,
        private MockLoggerInterface $logger
    ) {}

    public function __invoke(SendWelcomeEmailMessage $message): void
    {
        $this->jobManager->startJob($message->jobId);

        $user = $this->entityManager->find(User::class, $message->userId);
        if (!$user) {
            $this->jobManager->failJob($message->jobId, "User not found: {$message->userId}");
            $this->logger->error("User not found for welcome email.", ['userId' => $message->userId]);
            return;
        }

        try {
            // $email = (new Email())->...;
            // $this->mailer->send($email);
            $this->logger->info("Sent welcome email to user.", ['email' => $user->getEmail()]);
            $this->jobManager->completeJob($message->jobId, "Email sent to {$user->getEmail()}");
        } catch (\Exception $e) {
            $this->jobManager->failJob($message->jobId, $e->getMessage());
            // The retry strategy in messenger.yaml will handle this exception
            throw $e;
        }
    }
}

class ProcessPostImageMessageHandler implements MessageHandlerInterface
{
    public function __construct(
        private JobManager $jobManager,
        private MockLoggerInterface $logger
    ) {}

    public function __invoke(ProcessPostImageMessage $message): void
    {
        $this->jobManager->startJob($message->jobId);
        $this->logger->info("Starting image processing pipeline for post.", ['postId' => $message->postId]);

        try {
            // Step 1: Resize image
            sleep(2); // Simulate work
            $this->logger->info("Resized image.", ['path' => $message->imagePath]);

            // Step 2: Apply watermark
            sleep(1); // Simulate work
            $this->logger->info("Applied watermark.", ['path' => $message->imagePath]);

            // Step 3: Generate thumbnails
            sleep(2); // Simulate work
            $this->logger->info("Generated thumbnails.", ['path' => $message->imagePath]);

            $this->jobManager->completeJob($message->jobId, "Image processing complete for {$message->imagePath}");
        } catch (\Exception $e) {
            $this->jobManager->failJob($message->jobId, $e->getMessage());
            throw $e;
        }
    }
}

class GenerateWeeklyReportMessageHandler implements MessageHandlerInterface
{
    public function __construct(
        private JobManager $jobManager,
        private MockLoggerInterface $logger
    ) {}

    public function __invoke(GenerateWeeklyReportMessage $message): void
    {
        $this->jobManager->startJob($message->jobId);
        $this->logger->info("Generating weekly report.");

        try {
            // Simulate complex report generation
            sleep(10);
            $reportData = "Report generated at " . date('Y-m-d H:i:s');
            // file_put_contents('weekly-report.txt', $reportData);

            $this->jobManager->completeJob($message->jobId, "Report successfully generated.");
            $this->logger->info("Weekly report generation complete.");
        } catch (\Exception $e) {
            $this->jobManager->failJob($message->jobId, $e->getMessage());
            throw $e;
        }
    }
}

// ================================================================================
// CONSOLE COMMAND (For scheduling periodic tasks via cron)
// ================================================================================

namespace App\Variation1\Command;

use App\Variation1\Message\GenerateWeeklyReportMessage;
use App\Variation1\Service\JobManager;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsCommand(name: 'app:schedule-weekly-report', description: 'Schedules the weekly report generation job.')]
class ScheduleWeeklyReportCommand extends Command
{
    public function __construct(
        private MessageBusInterface $bus,
        private JobManager $jobManager
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $output->writeln('Scheduling weekly report generation...');

        $job = $this->jobManager->createJob(GenerateWeeklyReportMessage::class, []);
        $this->bus->dispatch(new GenerateWeeklyReportMessage($job->getId()));

        $output->writeln("Successfully scheduled job with ID: {$job->getId()}");

        return Command::SUCCESS;
    }
}