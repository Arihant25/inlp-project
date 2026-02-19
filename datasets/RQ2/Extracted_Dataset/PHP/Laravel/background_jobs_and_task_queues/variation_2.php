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
        protected $fillable = ['user_id', 'title', 'content', 'status', 'image_processing_status'];
        protected $casts = [
            'id' => 'string',
            'user_id' => 'string',
            'image_processing_status' => 'string' // e.g., PENDING, PROCESSING, COMPLETED, FAILED
        ];
    }
}

namespace App\Mail {
    use Illuminate\Bus\Queueable;
    use Illuminate\Mail\Mailable;
    use Illuminate\Queue\SerializesModels;
    use App\Models\Post;

    class PostPublishedEmail extends Mailable {
        use Queueable, SerializesModels;
        public function __construct(public Post $thePost) {}
        public function build() {
            return $this->subject("Your Post '{$this->thePost->title}' is Live!")->view('emails.post_published');
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
    use Illuminate\Support\Facades\Mail;
    use App\Models\Post;
    use App\Mail\PostPublishedEmail;
    use Throwable;

    class ProcessPostAssets implements ShouldQueue {
        use Dispatchable, InteractsWithQueue, Queueable, SerializesModels;

        public int $tries = 3;
        public array|int $backoff = [10, 30, 60]; // Exponential backoff: 10s, 30s, 60s

        public function __construct(public Post $post, public string $originalImagePath) {}

        public function handle(): void {
            try {
                $this->post->update(['image_processing_status' => 'PROCESSING']);

                $this->resizeImage();
                $this->applyWatermark();
                $this->storeFinalImage();

                $this->post->update(['image_processing_status' => 'COMPLETED', 'status' => 'PUBLISHED']);
                Mail::to($this->post->user->email)->send(new PostPublishedEmail($this->post));

            } catch (\Exception $e) {
                $this->post->update(['image_processing_status' => 'FAILED']);
                // Re-throw to let Laravel's queue handle the failure and retry logic
                throw $e;
            }
        }

        private function resizeImage(): void {
            Log::info("[ProcessPostAssets] Resizing image for Post ID: {$this->post->id}");
            // sleep(2); // Simulate work
            if (rand(1, 10) > 8) { throw new \Exception("GD Library failed during resize."); } // Simulate failure
        }

        private function applyWatermark(): void {
            Log::info("[ProcessPostAssets] Applying watermark for Post ID: {$this->post->id}");
            // sleep(1);
        }

        private function storeFinalImage(): void {
            Log::info("[ProcessPostAssets] Storing final image for Post ID: {$this->post->id}");
        }

        public function failed(Throwable $exception): void {
             $this->post->update(['image_processing_status' => 'FAILED']);
             Log::error("ProcessPostAssets failed for Post {$this->post->id}: {$exception->getMessage()}");
        }
    }
}

namespace App\Actions {
    use Illuminate\Foundation\Bus\Dispatchable;
    use Illuminate\Queue\InteractsWithQueue;
    use Illuminate\Queue\SerializesModels;
    use Illuminate\Contracts\Queue\ShouldQueue;
    use Illuminate\Support\Facades\Log;
    use App\Models\User;
    use App\Models\Post;
    use App\Jobs\ProcessPostAssets;

    // Base Action class to enable `::dispatch()` syntax
    abstract class Action {
        use Dispatchable, InteractsWithQueue, SerializesModels;
    }

    class PublishPostAction extends Action {
        public function __construct(private User $user, private array $postData, private string $imagePath) {}

        public function handle(): Post {
            $post = Post::create([
                'user_id' => $this->user->id,
                'title' => $this->postData['title'],
                'content' => $this->postData['content'],
                'status' => 'DRAFT',
                'image_processing_status' => 'PENDING',
            ]);

            ProcessPostAssets::dispatch($post, $this->imagePath);

            return $post;
        }
    }

    class GenerateAnalyticsReport extends Action implements ShouldQueue {
        public function handle(): void {
            Log::info("ACTION: Generating daily analytics report.");
            $active_users = User::where('is_active', true)->count();
            $published_posts = Post::where('status', 'PUBLISHED')->count();
            Log::info("ACTION: Report Data - Users: {$active_users}, Posts: {$published_posts}");
        }
    }
}

namespace App\Http\Controllers {
    use Illuminate\Http\Request;
    use Illuminate\Routing\Controller;
    use App\Models\User;
    use App\Actions\PublishPostAction;

    class PostController extends Controller {
        public function create(Request $request) {
            $user = User::firstOrCreate(['email' => 'test@example.com']);
            $postData = ['title' => 'A Post via Action', 'content' => '...'];
            $imagePath = '/tmp/new_image.png';

            // The action handles creating the post and dispatching the background job
            $post = (new PublishPostAction($user, $postData, $imagePath))->handle();

            return response()->json(['message' => 'Post creation initiated.', 'post_id' => $post->id]);
        }
    }
}

namespace App\Console {
    use Illuminate\Console\Scheduling\Schedule;
    use Illuminate\Foundation\Console\Kernel as ConsoleKernel;
    use App\Actions\GenerateAnalyticsReport;

    class Kernel extends ConsoleKernel {
        protected function schedule(Schedule $schedule): void {
            // Schedule the action to run as a job
            $schedule->job(new GenerateAnalyticsReport)->daily()->at('03:00');
        }
    }
}
</pre>