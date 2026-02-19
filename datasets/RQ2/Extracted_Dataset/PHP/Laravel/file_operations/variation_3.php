<php
/**
 * Variation 3: The "Action Class" / Single-Action Controller Developer
 *
 * Style: Adheres to Single Responsibility Principle for controllers.
 * - Each HTTP endpoint is handled by its own invokable class (an "Action").
 * - This keeps controllers extremely focused and easy to test.
 * - Dependencies are injected into the constructor of each action.
 * - Form Requests are still used for validation, specific to each action.
 * - Logic is contained within the `__invoke` method of the action class.
 *
 * To run this:
 * 1. composer require maatwebsite/excel intervention/image
 * 2. Define routes in routes/web.php pointing to each action class directly.
 *    e.g., Route::post('/users/import', App\Http\Actions\Variation3\ImportUsersAction::class);
 */

namespace App\Http\Actions\Variation3;

use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Http\JsonResponse;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\Storage;
use Maatwebsite\Excel\Facades\Excel;
use Maatwebsite\Excel\Concerns\ToCollection;
use Illuminate\Support\Collection;
use Intervention\Image\ImageManager;
use App\Models\Variation3\Post;
use App\Models\Variation3\User;
use App\Enums\Variation3\Role;
use App\Enums\Variation3\Status;
use Symfony\Component\HttpFoundation\StreamedResponse;

// --- Action 1: Import Users ---

class ImportUsersAction
{
    public function __invoke(UserImportRequest $request): JsonResponse
    {
        $file = $request->file('import_file');
        
        // Using a simple ToCollection import for direct processing
        $userCollection = Excel::toCollection(new class implements ToCollection {
            public function collection(Collection $rows) { return $rows; }
        }, $file)->first();

        // Remove header row
        $userCollection->shift();

        $importedCount = 0;
        foreach ($userCollection as $row) {
            if (empty($row[0]) || User::where('email', $row[0])->exists()) {
                continue;
            }

            User::create([
                'email' => $row[0],
                'password_hash' => Hash::make($row[1]),
                'role' => strtoupper(trim($row[2])) === 'ADMIN' ? Role::ADMIN : Role::USER,
                'is_active' => filter_var(trim($row[3]), FILTER_VALIDATE_BOOLEAN),
            ]);
            $importedCount++;
        }

        return new JsonResponse(['message' => "Import complete. {$importedCount} new users created."]);
    }
}

class UserImportRequest extends FormRequest
{
    public function authorize(): bool { return true; }
    public function rules(): array
    {
        return ['import_file' => 'required|file|mimes:xlsx,csv|max:5000'];
    }
}

// --- Action 2: Upload Post Image ---

class UploadPostImageAction
{
    private $imageManager;

    public function __construct(ImageManager $imageManager)
    {
        $this->imageManager = $imageManager;
    }

    public function __invoke(PostImageUploadRequest $request, Post $post): JsonResponse
    {
        $imageFile = $request->file('image');
        $image = $this->imageManager->make($imageFile);

        $image->cover(1024, 768);

        $filename = 'post-images/' . $post->id . '-' . uniqid() . '.webp';
        
        // Temporary file management: process in memory and store directly
        Storage::disk('public')->put($filename, (string) $image->encode('webp', 90));

        // You might want to update the post model with the image path here
        // $post->update(['image_url' => Storage::url($filename)]);

        return new JsonResponse([
            'message' => 'Image processed and saved.',
            'url' => Storage::url($filename)
        ], 201);
    }
}

class PostImageUploadRequest extends FormRequest
{
    public function authorize(): bool { return true; }
    public function rules(): array
    {
        return ['image' => 'required|image|dimensions:min_width=800,min_height=600'];
    }
}

// --- Action 3: Download Posts Report ---

class DownloadPostsReportAction
{
    public function __invoke(): StreamedResponse
    {
        $callback = function () {
            $handle = fopen('php://output', 'w');
            fputcsv($handle, ['id', 'user_id', 'title', 'status', 'created_at']);

            Post::query()
                ->where('status', Status::PUBLISHED)
                ->select(['id', 'user_id', 'title', 'status', 'created_at'])
                ->orderBy('created_at')
                ->chunk(500, function ($posts) use ($handle) {
                    foreach ($posts as $post) {
                        fputcsv($handle, $post->toArray());
                    }
                });

            fclose($handle);
        };

        return response()->stream($callback, 200, [
            'Content-Type' => 'text/csv',
            'Content-Disposition' => 'attachment; filename="posts_report.csv"',
        ]);
    }
}


// --- Mocked Domain Model ---
namespace App\Models\Variation3;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use App\Enums\Variation3\Status;

class Post extends Model {
    use HasUuids;
    protected $casts = ['status' => Status::class];
    protected $guarded = [];
    public function user(): BelongsTo { return $this->belongsTo(User::class); }
}

class User extends Model {
    use HasUuids;
    protected $casts = ['role' => \App\Enums\Variation3\Role::class];
    protected $guarded = [];
}

namespace App\Enums\Variation3;

enum Role: string { case ADMIN = 'admin'; case USER = 'user'; }
enum Status: string { case DRAFT = 'draft'; case PUBLISHED = 'published'; }

?>