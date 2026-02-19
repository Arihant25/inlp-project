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

    class JobStatus extends Model {
        use HasUuids;
        protected $fillable = ['job_name', 'job_id', 'status', 'message', 'trackable_id', 'trackable_type'];
        protected $casts = ['id' => 'string'];
        public function trackable() { return $this->morphTo(); }
    }
}

namespace App\Mail {
    use Illuminate\Bus\Queueable;
    use Illuminate\Mail\Mailable;
    use Illuminate\Queue\SerializesModels;
    use App\Models\User;

    class WelcomeUserEmail extends Mailable {
        use Queueable, SerializesModels;
        public function __construct(public User $user) {}
        public function build() {
            return $this->subject('Welcome to Our Application!')->view('emails.welcome');
        }
    }
}

namespace App\Jobs {
    use Illuminate\Bus\Queueable;
    use Illuminate\Contracts\Queue\ShouldQueue;
    use Illuminate\Contracts\Queue\ShouldBeUnique;
    use Illuminate\Foundation\Bus\Dispatchable;
    use Illuminate\Queue\InteractsWithQueue;
    use Illuminate\Queue\SerializesModels;
    use Illuminate\Support\Facades\Mail;
    use Illuminate\Support\Facades\Log;
    use Illuminate\Support\Facades\Bus;
    use App\Models\User;
    use App\Models\Post;
    use App\Models\JobStatus;
    use App\Mail\WelcomeUserEmail;
    use Throwable;

    // 1. Async Email Sending Job
    class SendWelcomeEmail implements ShouldQueue {
        use Dispatchable, InteractsWithQueue, Queueable, SerializesModels;

        public int $tries = 3;
        public int $backoff = 5; // 5, 10, 15 seconds

        public function __construct(protected User $user) {}

        public function handle(): void {
            Mail::to($this->user->email)->send(new WelcomeUserEmail($this->user));
        }
    }

    // 2. Image Processing Pipeline Jobs
    class ResizePostImage implements ShouldQueue {
        use Dispatchable, InteractsWithQueue, Queueable, SerializesModels;

        public function __construct(public Post $post, public string $imagePath, public JobStatus $jobStatus) {}

        public function handle(): void {
            $this->jobStatus->update(['status' => 'processing_resize', 'message' => 'Resizing image...']);
            Log::info("Resizing image for post {$this->post->id} at path: {$this->imagePath}");
            // sleep(2); // Simulate work
            if (rand(1, 10) > 8) { throw new \Exception("ImageMagick failed to resize."); } // Simulate random failure
        }
        public function failed(Throwable $exception): void {
            $this->jobStatus->update(['status' => 'failed', 'message' => $exception->getMessage()]);
        }
    }

    class WatermarkPostImage implements ShouldQueue {
        use Dispatchable, InteractsWithQueue, Queueable, SerializesModels;

        public function __construct(public Post $post, public string $imagePath, public JobStatus $jobStatus) {}

        public function handle(): void {
            $this->jobStatus->update(['status' => 'processing_watermark', 'message' => 'Applying watermark...']);
            Log::info("Watermarking image for post {$this->post->id}");
            // sleep(1); // Simulate work
        }
    }

    class StoreProcessedImage implements ShouldQueue {
        use Dispatchable, InteractsWithQueue, Queueable, SerializesModels;

        public function __construct(public Post $post, public string $imagePath, public JobStatus $jobStatus) {}

        public function handle(): void {
            Log::info("Storing final image for post {$this->post->id} in production storage.");
            $this->post->update(['status' => 'PUBLISHED']);
            $this->jobStatus->update(['status' => 'completed', 'message' => 'Image pipeline finished successfully.']);
        }
    }

    // 3. Scheduled Periodic Task
    class GenerateDailyReport implements ShouldQueue, ShouldBeUnique {
        use Dispatchable, InteractsWithQueue, Queueable, SerializesModels;

        public function handle(): void {
            Log::info("Generating daily user activity report.");
            $userCount = User::where('is_active', true)->count();
            $postCount = Post::where('status', 'PUBLISHED')->count();
            Log::info("Report: {$userCount} active users, {$postCount} published posts.");
            // sleep(10); // Simulate long-running task
        }
    }
}

namespace App\Http\Controllers {
    use Illuminate\Http\Request;
    use Illuminate\Routing\Controller;
    use Illuminate\Support\Facades\Bus;
    use App\Models\User;
    use App\Models\Post;
    use App\Models\JobStatus;
    use App\Jobs\SendWelcomeEmail;
    use App\Jobs\ResizePostImage;
    use App\Jobs\WatermarkPostImage;
    use App\Jobs\StoreProcessedImage;

    class PostController extends Controller {
        public function store(Request $request) {
            // Assume validation and user creation passed
            $user = User::firstOrCreate(['email' => 'test@example.com']);
            $post = Post::create([
                'user_id' => $user->id,
                'title' => 'My New Post',
                'content' => '...',
                'status' => 'DRAFT'
            ]);
            $imagePath = '/tmp/uploaded_image.jpg'; // Mock path

            // Create a trackable job status record
            $jobStatus = JobStatus::create([
                'job_name' => 'ProcessPostImagePipeline',
                'status' => 'pending',
                'trackable_id' => $post->id,
                'trackable_type' => Post::class,
            ]);

            // Dispatch a chain of jobs for the image pipeline
            Bus::chain([
                new ResizePostImage($post, $imagePath, $jobStatus),
                new WatermarkPostImage($post, $imagePath, $jobStatus),
                new StoreProcessedImage($post, $imagePath, $jobStatus),
            ])->catch(function (Throwable $e) use ($jobStatus) {
                $jobStatus->update(['status' => 'failed', 'message' => 'Chain failed: ' . $e->getMessage()]);
            })->dispatch();

            return response()->json(['message' => 'Post created and image processing started.', 'job_status_id' => $jobStatus->id]);
        }
    }
}

namespace App\Console {
    use Illuminate\Console\Scheduling\Schedule;
    use Illuminate\Foundation\Console\Kernel as ConsoleKernel;
    use App\Jobs\GenerateDailyReport;

    class Kernel extends ConsoleKernel {
        protected function schedule(Schedule $schedule): void {
            // Schedule the daily report job
            $schedule->job(new GenerateDailyReport)->daily()->at('02:00');
        }
    }
}
</pre>