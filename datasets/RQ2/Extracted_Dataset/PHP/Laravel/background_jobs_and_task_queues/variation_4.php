<pre>
&lt;?php

namespace App\Models {
    use Illuminate\Database\Eloquent\Model;
    use Illuminate\Database\Eloquent\Concerns\HasUuids;
    use Illuminate\Foundation\Auth\User as Authenticatable;
    use Illuminate\Notifications\Notifiable;

    class User extends Authenticatable {
        use HasUuids, Notifiable;
        protected $fillable = ['email', 'password_hash', 'role', 'is_active'];
        protected $casts = ['id' => 'string'];
    }

    class Post extends Model {
        use HasUuids;
        protected $fillable = ['user_id', 'title', 'content', 'status'];
        protected $casts = ['id' => 'string', 'user_id' => 'string'];
        public function user() { return $this->belongsTo(User::class); }
    }
}

namespace App\Notifications {
    use Illuminate\Bus\Queueable;
    use Illuminate\Contracts\Queue\ShouldQueue;
    use Illuminate\Notifications\Notification;
    use Illuminate\Notifications\Messages\MailMessage;
    use App\Models\User;
    use App\Models\Post;

    // 1. Async Email Sending via Queueable Notification
    class UserWelcomeNotification extends Notification implements ShouldQueue {
        use Queueable;

        public function __construct() {
            $this->onConnection('redis')->onQueue('emails');
        }

        public function via(object $notifiable): array { return ['mail']; }

        public function toMail(User $notifiable): MailMessage {
            return (new MailMessage)
                ->subject('Welcome Aboard!')
                ->line('Thank you for joining our platform.')
                ->action('Explore Now', url('/'));
        }
    }

    class PostPublishedNotification extends Notification implements ShouldQueue {
        use Queueable;
        public function __construct(public Post $post) {}
        public function via(object $notifiable): array { return ['mail']; }
        public function toMail(User $notifiable): MailMessage {
            return (new MailMessage)
                ->subject("Your post is live: {$this->post->title}")
                ->line("Your new post has been successfully published and processed.");
        }
    }
}

namespace App\Jobs {
    use Illuminate\Bus\Queueable;
    use Illuminate\Contracts\Queue\ShouldQueue;
    use Illuminate\Foundation\Bus\Dispatchable;
    use Illuminate\Queue\InteractsWithQueue;
    use Illuminate\Queue\SerializesModels;
    use Illuminate\Support\Facades\Log;
    use Illuminate\Support\Facades\Cache;
    use App\Models\Post;
    use App\Notifications\PostPublishedNotification;
    use Throwable;

    // 2. Image Processing using a single, configurable Job
    class ProcessImageJob implements ShouldQueue {
        use Dispatchable, InteractsWithQueue, Queueable, SerializesModels;

        public int $tries = 3;
        public function backoff(): array { return [5, 15, 30]; } // Exponential backoff

        public function __construct(
            private Post $post,
            private string $operation, // 'resize', 'watermark'
            private string $image_path
        ) {}

        public function handle(): void {
            $jobId = $this->job->getJobId();
            $cacheKey = "job_status:{$jobId}";

            Cache::put($cacheKey, ['status' => 'processing', 'operation' => $this->operation], 3600);
            Log::info("Executing '{$this->operation}' for post {$this->post->id}");

            switch ($this->operation) {
                case 'resize':
                    // sleep(2); // Simulate resize
                    if (rand(1, 10) > 8) { throw new \Exception("Resize failed"); }
                    break;
                case 'watermark':
                    // sleep(1); // Simulate watermark
                    break;
            }

            Cache::put($cacheKey, ['status' => 'completed', 'operation' => $this->operation], 3600);
        }

        public function failed(Throwable $exception): void {
            $jobId = $this->job->getJobId();
            $cacheKey = "job_status:{$jobId}";
            Cache::put($cacheKey, [
                'status' => 'failed',
                'operation' => $this->operation,
                'error' => $exception->getMessage()
            ], 3600);
        }
    }
}

namespace App\Http\Controllers {
    use Illuminate\Http\Request;
    use Illuminate\Routing\Controller;
    use Illuminate\Support\Facades\Bus;
    use App\Models\User;
    use App\Models\Post;
    use App\Jobs\ProcessImageJob;
    use App\Notifications\UserWelcomeNotification;
    use App\Notifications\PostPublishedNotification;
    use Closure;

    class UserController extends Controller {
        public function register() {
            $user = User::firstOrCreate(['email' => 'new-user@example.com']);
            // Laravel's Notifiable trait makes this clean and async
            $user->notify(new UserWelcomeNotification());
            return response()->json(['message' => 'User registered, welcome email queued.']);
        }
    }

    class PostController extends Controller {
        public function publish(Request $request) {
            $user = User::firstOrCreate(['email' => 'test@example.com']);
            $post = Post::create([
                'user_id' => $user->id,
                'title' => 'Concise Post Title',
                'content' => '...',
                'status' => 'DRAFT'
            ]);
            $imagePath = '/tmp/upload.jpg';

            // 3. Image Processing Pipeline using Bus::chain and a final closure
            $pipeline = [
                new ProcessImageJob($post, 'resize', $imagePath),
                new ProcessImageJob($post, 'watermark', $imagePath),
                function () use ($post) {
                    $post->update(['status' => 'PUBLISHED']);
                    $post->user->notify(new PostPublishedNotification($post));
                    Log::info("Post {$post->id} successfully published and user notified.");
                }
            ];

            Bus::chain($pipeline)->dispatch();

            return response()->json(['message' => 'Post is being processed and will be published shortly.']);
        }
    }
}

namespace App\Console {
    use Illuminate\Console\Scheduling\Schedule;
    use Illuminate\Foundation\Console\Kernel as ConsoleKernel;
    use Illuminate\Support\Facades\DB;
    use Illuminate\Support\Facades\Log;

    class Kernel extends ConsoleKernel {
        protected function schedule(Schedule $schedule): void {
            // 4. Scheduled Periodic Task using a simple closure
            $schedule->call(function () {
                Log::info("Running scheduled task: Pruning old posts.");
                $deletedCount = DB::table('posts')
                    ->where('status', 'DRAFT')
                    ->where('created_at', '<', now()->subMonths(6))
                    ->delete();
                Log::info("Pruned {$deletedCount} old draft posts.");
            })->daily()->at('05:00');
        }
    }
}
</pre>