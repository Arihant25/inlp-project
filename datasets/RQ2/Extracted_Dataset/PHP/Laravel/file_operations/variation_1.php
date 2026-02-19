<php
/**
 * Variation 1: The "By-the-Book" Service Class Developer
 *
 * Style: Clean, Object-Oriented, follows SOLID.
 * - A dedicated Service class encapsulates all business logic.
 * - The Controller is thin, responsible only for HTTP layer concerns.
 * - Form Requests are used for validation and authorization.
 * - Dependency Injection is used to provide the service to the controller.
 * - Uses popular libraries: `maatwebsite/excel` and `intervention/image`.
 *
 * To run this:
 * 1. composer require maatwebsite/excel intervention/image
 * 2. Define routes in routes/web.php pointing to FileController methods.
 */

namespace App\Http\Controllers\Variation1;

use Illuminate\Http\Request;
use Illuminate\Routing\Controller;
use App\Services\Variation1\FileOperationService;
use App\Http\Requests\Variation1\UserImportRequest;
use App\Http\Requests\Variation1\PostImageUploadRequest;
use App\Models\Variation1\Post;
use Symfony\Component\HttpFoundation\StreamedResponse;

class FileController extends Controller
{
    protected $fileService;

    public function __construct(FileOperationService $fileService)
    {
        $this->fileService = $fileService;
    }

    /**
     * Handle the upload and processing of a user import CSV.
     */
    public function importUsers(UserImportRequest $request): \Illuminate\Http\JsonResponse
    {
        $file = $request->file('users_csv');
        $importCount = $this->fileService->importUsersFromCsv($file);

        return response()->json([
            'message' => "Successfully imported {$importCount} users.",
        ]);
    }

    /**
     * Handle the upload and resizing of a post's primary image.
     */
    public function uploadPostImage(PostImageUploadRequest $request, Post $post): \Illuminate\Http\JsonResponse
    {
        $imageFile = $request->file('post_image');
        $imagePath = $this->fileService->processPostImage($imageFile, $post);

        return response()->json([
            'message' => 'Image uploaded and processed successfully.',
            'path' => $imagePath,
        ]);
    }

    /**
     * Stream a CSV download of all published posts.
     */
    public function downloadPublishedPostsReport(): StreamedResponse
    {
        return $this->fileService->generatePostsCsvStream();
    }
}

// --- Service Layer ---
namespace App\Services\Variation1;

use App\Models\Variation1\Post;
use App\Models\Variation1\User;
use App\Enums\Variation1\Role;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Storage;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Str;
use Maatwebsite\Excel\Facades\Excel;
use App\Imports\Variation1\UsersImport;
use Intervention\Image\ImageManagerStatic as Image;
use Symfony\Component\HttpFoundation\StreamedResponse;

class FileOperationService
{
    /**
     * Imports users from a given CSV file.
     * Uses a dedicated Import class for maatwebsite/excel.
     */
    public function importUsersFromCsv(UploadedFile $file): int
    {
        $importer = new UsersImport();
        Excel::import($importer, $file);
        return $importer->getRowCount();
    }

    /**
     * Resizes, stores, and associates an image with a post.
     */
    public function processPostImage(UploadedFile $imageFile, Post $post): string
    {
        $image = Image::make($imageFile)->fit(1200, 630, function ($constraint) {
            $constraint->upsize();
        });

        $fileName = $post->id . '_' . time() . '.jpg';
        $path = 'posts/' . $fileName;

        // Store the processed image in the public disk
        Storage::disk('public')->put($path, (string) $image->encode('jpg', 80));

        return Storage::disk('public')->url($path);
    }

    /**
     * Generates a streamed response for downloading a CSV of posts.
     */
    public function generatePostsCsvStream(): StreamedResponse
    {
        $fileName = 'published_posts_' . date('Y-m-d') . '.csv';

        $headers = [
            'Content-Type' => 'text/csv',
            'Content-Disposition' => 'attachment; filename="' . $fileName . '"',
        ];

        $callback = function () {
            $fileHandle = fopen('php://output', 'w');
            fputcsv($fileHandle, ['ID', 'Title', 'Author Email', 'Status']);

            Post::where('status', \App\Enums\Variation1\Status::PUBLISHED)
                ->with('user')
                ->chunk(200, function ($posts) use ($fileHandle) {
                    foreach ($posts as $post) {
                        fputcsv($fileHandle, [
                            $post->id,
                            $post->title,
                            $post->user->email,
                            $post->status->value,
                        ]);
                    }
                });

            fclose($fileHandle);
        };

        return new StreamedResponse($callback, 200, $headers);
    }
    
    /**
     * Manages temporary files.
     */
    public function cleanupTempFile(string $path): bool
    {
        // Assuming 'temp' disk is configured in filesystems.php
        if (Storage::disk('temp')->exists($path)) {
            return Storage::disk('temp')->delete($path);
        }
        return false;
    }
}

// --- Form Requests for Validation ---
namespace App\Http\Requests\Variation1;

use Illuminate\Foundation\Http\FormRequest;

class UserImportRequest extends FormRequest
{
    public function authorize(): bool { return true; /* Assume authorization logic here */ }

    public function rules(): array
    {
        return [
            'users_csv' => ['required', 'file', 'mimes:csv,txt', 'max:2048'],
        ];
    }
}

class PostImageUploadRequest extends FormRequest
{
    public function authorize(): bool { return true; /* Assume authorization logic here */ }

    public function rules(): array
    {
        return [
            'post_image' => ['required', 'image', 'mimes:jpeg,png,jpg,gif', 'max:5120'],
        ];
    }
}

// --- Maatwebsite/Excel Import Class ---
namespace App\Imports\Variation1;

use App\Models\Variation1\User;
use App\Enums\Variation1\Role;
use Illuminate\Support\Facades\Hash;
use Maatwebsite\Excel\Concerns\ToModel;
use Maatwebsite\Excel\Concerns\WithHeadingRow;
use Maatwebsite\Excel\Concerns\WithBatchInserts;

class UsersImport implements ToModel, WithHeadingRow, WithBatchInserts
{
    private $rowCount = 0;

    public function model(array $row): User
    {
        ++$this->rowCount;
        return new User([
            'email' => $row['email'],
            'password_hash' => Hash::make($row['password']),
            'role' => $row['role'] === 'ADMIN' ? Role::ADMIN : Role::USER,
            'is_active' => filter_var($row['is_active'], FILTER_VALIDATE_BOOLEAN),
        ]);
    }

    public function batchSize(): int
    {
        return 500;
    }

    public function getRowCount(): int
    {
        return $this->rowCount;
    }
}

// --- Mocked Domain Model ---
namespace App\Models\Variation1;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use App\Enums\Variation1\Status;

class Post extends Model {
    use HasUuids;
    protected $casts = ['status' => Status::class];
    protected $guarded = [];
    public function user(): BelongsTo { return $this->belongsTo(User::class); }
}

class User extends Model {
    use HasUuids;
    protected $casts = ['role' => \App\Enums\Variation1\Role::class];
    protected $guarded = [];
}

namespace App\Enums\Variation1;

enum Role: string { case ADMIN = 'admin'; case USER = 'user'; }
enum Status: string { case DRAFT = 'draft'; case PUBLISHED = 'published'; }

?>