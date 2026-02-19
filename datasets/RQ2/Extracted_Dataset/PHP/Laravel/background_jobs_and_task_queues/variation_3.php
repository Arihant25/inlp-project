<pre>
&lt;?php

namespace App\Models {
    use Illuminate\Database\Eloquent\Model;
    use Illuminate\Database\Eloquent\Concerns\HasUuids;
    use Illuminate\Foundation\Auth\User as Authenticatable;

    class User extends Authenticatable {
        use HasUuids;
        protected $fillable = ['email', 'password_hash', 'role', 'is_active'];
        protected $casts = ['id' => 'string'];
    }

    class Post extends Model {
        use HasUuids;
        protected $fillable = ['user_id', 'title', 'content', 'status'];
        protected $casts = ['id' => 'string', 'user_id' => 'string'];
    }

    class JobLog extends Model {
        protected $fillable = ['correlation_id', 'job_name', 'status', 'context'];
        protected $casts = ['context' => 'array'];
    }
}

namespace App\Events {
    use Illuminate\Foundation\Events\Dispatchable;
    use Illuminate\Queue\SerializesModels;
    use App\Models\User;
    use App\Models\Post;

    class UserRegistered {
        use Dispatchable, SerializesModels;
        public function __construct(public User $user) {}
    }

    class PostPublished {
        use Dispatchable, SerializesModels;
        public function __construct(public Post $post, public string $imagePath, public string $correlationId) {}
    }

    class ReportGenerationRequested {
        use Dispatchable, SerializesModels;
        public function __construct(public \DateTime $forDate) {}
    }
}

namespace App\Listeners {
    use Illuminate\Contracts\Queue\ShouldQueue;
    use Illuminate\Queue\InteractsWithQueue;
    use Illuminate\Support\Facades\Mail;
    use Illuminate\Support\Facades\Bus;
    use Illuminate\Support\Facades\Log;
    use App\Events\UserRegistered;
    use App\Events\PostPublished;
    use App\Events\ReportGenerationRequested;
    use App\Services\JobTracker;
    use App\Jobs\ImageProcessing\ResizeImage;
    use App\Jobs\ImageProcessing\ApplyWatermark;
    use App\Jobs\ImageProcessing\FinalizeImage;
    use Throwable;

    class SendWelcomeNotification implements ShouldQueue {
        use InteractsWithQueue;
        public int $tries = 3;
        public function handle(UserRegistered $event): void {
            Log::info("Listener SendWelcomeNotification is sending email to {$event->user->email}");
            // Mail::to($event->user->email)->send(new \App\Mail\WelcomeMail($event->user));
        }
    }

    class InitiatePostImageProcessing implements ShouldQueue {
        use InteractsWithQueue;
        public function __construct(private JobTracker $tracker) {}

        public function handle(PostPublished $event): void {
            $this->tracker->start($event->correlationId, 'ImageProcessingPipeline', ['post_id' => $event->post->id]);

            Bus::chain([
                new ResizeImage($event->post, $event->imagePath, $event->correlationId),
                new ApplyWatermark($event->post, $event->imagePath, $event->correlationId),
                new FinalizeImage($event->post, $event->correlationId),
            ])->catch(function (Throwable $e) use ($event) {
                $this->tracker->fail($event->correlationId, 'ImageProcessingPipeline', $e->getMessage());
            })->dispatch();
        }
    }

    class GenerateReport implements ShouldQueue {
        use InteractsWithQueue;
        public function handle(ReportGenerationRequested $event): void {
            Log::info("Listener GenerateReport is running for date: " . $event->forDate->format('Y-m-d'));
            // ... heavy report generation logic
        }
    }
}

namespace App\Jobs\ImageProcessing {
    use Illuminate\Bus\Queueable;
    use Illuminate\Contracts\Queue\ShouldQueue;
    use Illuminate\Foundation\Bus\Dispatchable;
    use Illuminate\Queue\InteractsWithQueue;
    use Illuminate\Queue\SerializesModels;
    use Illuminate\Support\Facades\Log;
    use App\Models\Post;
    use App\Services\JobTracker;
    use Throwable;

    class ResizeImage implements ShouldQueue {
        use Dispatchable, InteractsWithQueue, Queueable, SerializesModels;
        public int $tries = 2;
        public int $backoff = 10;
        public function __construct(public Post $post, public string $path, public string $correlationId) {}
        public function handle(JobTracker $tracker): void {
            $tracker->update($this->correlationId, 'ResizeImage', 'processing');
            Log::info("Resizing image for post {$this->post->id}");
            if (rand(1, 10) > 8) { throw new \Exception("Imagick Engine Failure"); }
            $tracker->update($this->correlationId, 'ResizeImage', 'completed');
        }
        public function failed(Throwable $e): void {
            app(JobTracker::class)->update($this->correlationId, 'ResizeImage', 'failed', ['error' => $e->getMessage()]);
        }
    }

    class ApplyWatermark implements ShouldQueue { /* ... similar implementation ... */
        use Dispatchable, InteractsWithQueue, Queueable, SerializesModels;
        public function __construct(public Post $post, public string $path, public string $correlationId) {}
        public function handle(JobTracker $tracker): void {
            $tracker->update($this->correlationId, 'ApplyWatermark', 'processing');
            Log::info("Applying watermark for post {$this->post->id}");
            $tracker->update($this->correlationId, 'ApplyWatermark', 'completed');
        }
    }

    class FinalizeImage implements ShouldQueue { /* ... similar implementation ... */
        use Dispatchable, InteractsWithQueue, Queueable, SerializesModels;
        public function __construct(public Post $post, public string $correlationId) {}
        public function handle(JobTracker $tracker): void {
            $this->post->update(['status' => 'PUBLISHED']);
            $tracker->finish($this->correlationId, 'ImageProcessingPipeline');
        }
    }
}

namespace App\Services {
    use App\Models\JobLog;
    class JobTracker {
        public function start(string $id, string $name, array $context = []): void { JobLog::create(['correlation_id' => $id, 'job_name' => $name, 'status' => 'started', 'context' => $context]); }
        public function update(string $id, string $name, string $status, array $context = []): void { JobLog::where('correlation_id', $id)->first()?->increment('context', [$name => ['status' => $status, 'context' => $context, 'time' => now()]]); }
        public function finish(string $id, string $name): void { JobLog::where('correlation_id', $id)->update(['status' => 'finished']); }
        public function fail(string $id, string $name, string $message): void { JobLog::where('correlation_id', $id)->update(['status' => 'failed', 'context->error' => $message]); }
    }
}

namespace App\Providers {
    use Illuminate\Foundation\Support\Providers\EventServiceProvider as ServiceProvider;
    use App\Events;
    use App\Listeners;

    class EventServiceProvider extends ServiceProvider {
        protected $listen = [
            Events\UserRegistered::class => [ Listeners\SendWelcomeNotification::class, ],
            Events\PostPublished::class => [ Listeners\InitiatePostImageProcessing::class, ],
            Events\ReportGenerationRequested::class => [ Listeners\GenerateReport::class, ],
        ];
    }
}

namespace App\Http\Controllers {
    use Illuminate\Http\Request;
    use Illuminate\Routing\Controller;
    use Illuminate\Support\Str;
    use App\Models\User;
    use App\Models\Post;
    use App\Events\PostPublished;

    class PostController extends Controller {
        public function publish(Request $request, string $postId) {
            $post = Post::findOrFail($postId);
            $imagePath = '/tmp/image.jpg';
            $correlationId = (string) Str::uuid();

            // Fire an event. Listeners will handle the async work.
            event(new PostPublished($post, $imagePath, $correlationId));

            return response()->json(['message' => 'Post publication process initiated.', 'tracking_id' => $correlationId]);
        }
    }
}

namespace App\Console {
    use Illuminate\Console\Scheduling\Schedule;
    use Illuminate\Foundation\Console\Kernel as ConsoleKernel;
    use App\Events\ReportGenerationRequested;

    class Kernel extends ConsoleKernel {
        protected function schedule(Schedule $schedule): void {
            $schedule->call(function () {
                event(new ReportGenerationRequested(new \DateTime()));
            })->daily()->at('04:00');
        }
    }
}
</pre>