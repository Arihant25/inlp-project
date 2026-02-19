<?php

namespace App\Variation4;

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
enum Role: string { case ADMIN = 'admin'; case USER = 'user'; }
enum Status: string { case DRAFT = 'draft'; case PUBLISHED = 'published'; }
enum JobState: string { case PENDING = 'pending'; case RUNNING = 'running'; case DONE = 'done'; case FAILED = 'failed'; }

// --- Entities ---
class User {
    public function __construct(public Uuid $id, public string $email, public string $password_hash, public Role $role, public bool $is_active, public DateTimeImmutable $created_at) {}
}
class Post {
    public function __construct(public Uuid $id, public Uuid $user_id, public string $title, public string $content, public Status $status) {}
}
class JobLog {
    private Uuid $id;
    private string $message_class;
    private JobState $state;
    private array $context;
    private ?string $notes = null;
    private DateTimeImmutable $queued_at;
    private ?DateTimeImmutable $finished_at = null;

    public function __construct(string $messageClass, array $context) {
        $this->id = Uuid::v4();
        $this->message_class = $messageClass;
        $this->context = $context;
        $this->state = JobState::PENDING;
        $this->queued_at = new DateTimeImmutable();
    }
    public function getId(): Uuid { return $this->id; }
    public function setState(JobState $state): void { $this->state = $state; }
    public function setNotes(?string $notes): void { $this->notes = $notes; }
    public function setFinishedAt(DateTimeImmutable $time): void { $this->finished_at = $time; }
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
            async_tasks:
                dsn: '%env(MESSENGER_TRANSPORT_DSN)%'
                retry_strategy:
                    max_retries: 3
                    delay: 5000 # 5 seconds
                    multiplier: 3 # 5s, 15s, 45s
                    service: App\Variation4\Retry\CustomRetryStrategy # Optional custom strategy

        routing:
            # Route all messages in this namespace to the same transport
            'App\Variation4\Message\*': async_tasks
*/

// ================================================================================
// MESSAGES (Actions)
// ================================================================================

namespace App\Variation4\Message;

use Symfony\Component\Uid\Uuid;

class ProcessPostImage { public function __construct(public Uuid $postId, public string $imagePath, public Uuid $jobLogId) {} }
class SendUserWelcome { public function __construct(public Uuid $userId, public Uuid $jobLogId) {} }
class CreateWeeklyReport { public function __construct(public Uuid $jobLogId) {} }

// ================================================================================
// SHARED LOGIC (Trait for Job Tracking)
// ================================================================================

namespace App\Variation4\Handler\Trait;

use App\Variation4\JobLog;
use App\Variation4\JobState;
use App\Variation4\MockEntityManager;
use DateTimeImmutable;
use Symfony\Component\Uid\Uuid;

trait JobTrackingTrait
{
    private MockEntityManager $entityManager;

    // This method would be called by the handler's constructor to inject the dependency
    public function setEntityManager(MockEntityManager $entityManager): void
    {
        $this->entityManager = $entityManager;
    }

    private function startJob(Uuid $jobLogId): ?JobLog
    {
        $jobLog = $this->entityManager->find(JobLog::class, $jobLogId);
        if ($jobLog) {
            $jobLog->setState(JobState::RUNNING);
        }
        return $jobLog;
    }

    private function finishJob(Uuid $jobLogId, string $notes = 'Completed successfully'): void
    {
        $jobLog = $this->entityManager->find(JobLog::class, $jobLogId);
        if ($jobLog) {
            $jobLog->setState(JobState::DONE);
            $jobLog->setNotes($notes);
            $jobLog->setFinishedAt(new DateTimeImmutable());
        }
    }

    private function failJob(Uuid $jobLogId, \Throwable $exception): void
    {
        $jobLog = $this->entityManager->find(JobLog::class, $jobLogId);
        if ($jobLog) {
            $jobLog->setState(JobState::FAILED);
            $jobLog->setNotes(substr($exception->getMessage(), 0, 255));
            $jobLog->setFinishedAt(new DateTimeImmutable());
        }
    }
}

// ================================================================================
// HANDLERS (Grouped by functionality)
// ================================================================================

namespace App\Variation4\Handler;

use App\Variation4\Handler\Trait\JobTrackingTrait;
use App\Variation4\Message\ProcessPostImage;
use App\Variation4\Message\SendUserWelcome;
use App\Variation4\MockEntityManager;
use App\Variation4\MockLogger;
use App\Variation4\MockMailer;
use App\Variation4\User;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CoreTaskHandler
{
    // Use trait for DRY job status updates
    use JobTrackingTrait;

    public function __construct(
        private MockMailer $mailer,
        private MockLogger $logger,
        MockEntityManager $entityManager
    ) {
        $this->setEntityManager($entityManager);
    }

    public function __invoke(SendUserWelcome|ProcessPostImage $message): void
    {
        // This is a simple way to handle multiple messages in one invokable handler
        match ($message::class) {
            SendUserWelcome::class => $this->handleWelcomeEmail($message),
            ProcessPostImage::class => $this->handleImageProcessing($message),
        };
    }

    private function handleWelcomeEmail(SendUserWelcome $message): void
    {
        $this->startJob($message->jobLogId);
        try {
            $user = $this->entityManager->find(User::class, $message->userId);
            if (!$user) {
                throw new \RuntimeException("User with ID {$message->userId} not found.");
            }
            $this->logger->info("Sending welcome email to user {$user->email}");
            // $this->mailer->send(...);
            $this->finishJob($message->jobLogId, "Email sent to {$user->email}");
        } catch (\Throwable $e) {
            $this->failJob($message->jobLogId, $e);
            throw $e; // Re-throw for messenger's retry logic
        }
    }

    private function handleImageProcessing(ProcessPostImage $message): void
    {
        $this->startJob($message->jobLogId);
        try {
            $this->logger->info("Starting image pipeline for post {$message->postId}");
            // Steps are synchronous within this async job
            $this->executeStep('resize', 2);
            $this->executeStep('watermark', 1);
            $this->executeStep('thumbnails', 2);
            $this->finishJob($message->jobLogId, "Image pipeline completed.");
        } catch (\Throwable $e) {
            $this->failJob($message->jobLogId, $e);
            throw $e;
        }
    }

    private function executeStep(string $name, int $duration): void
    {
        $this->logger->debug("Executing step: {$name}");
        sleep($duration);
    }
}

namespace App\Variation4\Handler;

use App\Variation4\Handler\Trait\JobTrackingTrait;
use App\Variation4\Message\CreateWeeklyReport;
use App\Variation4\MockEntityManager;
use App\Variation4\MockLogger;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ReportTaskHandler
{
    use JobTrackingTrait;

    public function __construct(
        private MockLogger $logger,
        MockEntityManager $entityManager
    ) {
        $this->setEntityManager($entityManager);
    }

    public function __invoke(CreateWeeklyReport $message): void
    {
        $this->startJob($message->jobLogId);
        try {
            $this->logger->info("Generating weekly report...");
            sleep(10); // Simulate heavy work
            $this->finishJob($message->jobLogId, "Report generated and saved to disk.");
        } catch (\Throwable $e) {
            $this->failJob($message->jobLogId, $e);
            throw $e;
        }
    }
}

// ================================================================================
// DISPATCHER SERVICE & CONSOLE COMMAND
// ================================================================================

namespace App\Variation4\Service;

use App\Variation4\JobLog;
use App\Variation4\Message\CreateWeeklyReport;
use App\Variation4\MockEntityManager;
use Symfony\Component\Messenger\MessageBusInterface;

class JobDispatcher
{
    public function __construct(
        private MessageBusInterface $bus,
        private MockEntityManager $em
    ) {}

    public function dispatch(object $message): JobLog
    {
        // This service centralizes job logging before dispatching
        $jobLog = new JobLog($message::class, (array)$message);
        $this->em->persist($jobLog);
        // $this->em->flush();

        // Inject the JobLog ID into the message if it has the property
        if (property_exists($message, 'jobLogId')) {
            $message->jobLogId = $jobLog->getId();
        }

        $this->bus->dispatch($message);
        return $jobLog;
    }
}

namespace App\Variation4\Command;

use App\Variation4\Message\CreateWeeklyReport;
use App\Variation4\Service\JobDispatcher;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;

#[AsCommand(name: 'app:cron:dispatch', description: 'Dispatches scheduled background jobs.')]
class CronCommand extends Command
{
    public function __construct(private JobDispatcher $dispatcher)
    {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $output->writeln('Dispatching scheduled jobs...');
        $jobLog = $this->dispatcher->dispatch(new CreateWeeklyReport(Uuid::v4())); // ID is temporary
        $output->writeln("<info>Dispatched weekly report. Log ID: {$jobLog->getId()}</info>");
        return Command::SUCCESS;
    }
}