<php
/**
 * Variation 4: The "Trait-based" / Code Reusability Developer
 *
 * Style: Focuses on code reuse across controllers by extracting logic into Traits.
 * - Common functionalities like file uploading, image processing, and CSV generation
 *   are encapsulated in reusable traits.
 * - Controllers `use` these traits to gain their functionality.
 * - This pattern is useful when multiple controllers need similar file-handling capabilities.
 * - Can sometimes feel like "magic" if not well-documented, but promotes DRY.
 *
 * To run this:
 * 1. composer require intervention/image
 * 2. Define routes in routes/web.php pointing to UserFileController methods.
 */

namespace App\Http\Controllers\Traits\Variation4;

use Illuminate\Http\Request;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Storage;
use Illuminate\Support\Str;
use Intervention\Image\ImageManagerStatic as Image;
use Symfony\Component\HttpFoundation\StreamedResponse;
use Illuminate\Database\Eloquent\Collection;

trait HandlesFileUploads
{
    protected function storeUploadedFile(UploadedFile $file, string $directory, ?string $disk = 'local'): string
    {
        $filename = Str::uuid() . '.' . $file->getClientOriginalExtension();
        return $file->storeAs($directory, $filename, $disk);
    }
}

trait ProcessesImages
{
    protected function resizeAndStore(UploadedFile $file, string $path, int $width, int $height, string $disk = 'public'): string
    {
        $image = Image::make($file)->fit($width, $height, function ($constraint) {
            $constraint->upsize();
        });

        $fullPath = $path . '/' . Str::random(40) . '.jpg';
        Storage::disk($disk)->put($fullPath, (string) $image->encode('jpg', 85));
        
        return $fullPath;
    }
}

trait GeneratesCsvResponse
{
    protected function streamCollectionAsCsv(Collection $collection, array $headers, string $filename): StreamedResponse
    {
        $responseHeaders = [
            'Content-Type' => 'text/csv',
            'Content-Disposition' => 'attachment; filename="' . $filename . '"',
        ];

        $callback = function () use ($collection, $headers) {
            $file = fopen('php://output', 'w');
            fputcsv($file, array_keys($headers));

            foreach ($collection as $item) {
                $row = [];
                foreach ($headers as $headerKey => $itemKey) {
                    $row[] = data_get($item, $itemKey);
                }
                fputcsv($file, $row);
            }
            fclose($file);
        };

        return new StreamedResponse($callback, 200, $responseHeaders);
    }
}

trait ManagesTemporaryFiles
{
    /**
     * Creates a temporary file from an uploaded file and returns its path.
     */
    protected function createTempFile(UploadedFile $file): string
    {
        $tempDir = storage_path('app/temp');
        if (!is_dir($tempDir)) {
            mkdir($tempDir, 0755, true);
        }
        $path = $file->store('temp');
        return storage_path('app/' . $path);
    }

    /**
     * Deletes a file from the temporary storage.
     */
    protected function cleanupTempFile(string $path): void
    {
        if (file_exists($path)) {
            unlink($path);
        }
    }
}

// --- Controller using the Traits ---
namespace App\Http\Controllers\Variation4;

use Illuminate\Http\Request;
use Illuminate\Routing\Controller;
use App\Http\Controllers\Traits\Variation4\HandlesFileUploads;
use App\Http\Controllers\Traits\Variation4\ProcessesImages;
use App\Http\Controllers\Traits\Variation4\GeneratesCsvResponse;
use App\Http\Controllers\Traits\Variation4\ManagesTemporaryFiles;
use App\Models\Variation4\User;
use App\Models\Variation4\Post;
use App\Enums\Variation4\Role;
use Illuminate\Support\Facades\Hash;

class UserFileController extends Controller
{
    use HandlesFileUploads, ProcessesImages, GeneratesCsvResponse, ManagesTemporaryFiles;

    public function uploadAvatar(Request $request)
    {
        $request->validate(['avatar' => 'required|image|max:2048']);
        
        $user = User::find($request->user()->id); // Assuming authenticated user
        $path = $this->resizeAndStore($request->file('avatar'), 'avatars', 250, 250);

        return response()->json(['message' => 'Avatar updated', 'path' => $path]);
    }

    public function importUsers(Request $request)
    {
        $request->validate(['users_csv' => 'required|file|mimes:csv,txt']);
        
        // Temporary file management in action
        $tempPath = $this->createTempFile($request->file('users_csv'));

        $handle = fopen($tempPath, 'r');
        fgetcsv($handle); // Skip header

        $imported = 0;
        while (($data = fgetcsv($handle)) !== false) {
            User::firstOrCreate(
                ['email' => $data[0]],
                [
                    'password_hash' => Hash::make($data[1]),
                    'role' => Role::USER,
                    'is_active' => true
                ]
            );
            $imported++;
        }
        fclose($handle);
        
        $this->cleanupTempFile($tempPath);

        return response()->json(['message' => "Imported {$imported} users."]);
    }

    public function exportPosts()
    {
        $posts = Post::with('user')->where('status', \App\Enums\Variation4\Status::PUBLISHED)->get();

        $headers = [
            'Post ID' => 'id',
            'Title' => 'title',
            'Author Email' => 'user.email',
            'Status' => 'status',
        ];

        return $this->streamCollectionAsCsv($posts, $headers, 'all-posts.csv');
    }
}

// --- Mocked Domain Model ---
namespace App\Models\Variation4;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use App\Enums\Variation4\Status;

class Post extends Model {
    use HasUuids;
    protected $casts = ['status' => Status::class];
    protected $guarded = [];
    public function user(): BelongsTo { return $this->belongsTo(User::class); }
}

class User extends Model {
    use HasUuids;
    protected $casts = ['role' => \App\Enums\Variation4\Role::class];
    protected $guarded = [];
}

namespace App\Enums\Variation4;

enum Role: string { case ADMIN = 'admin'; case USER = 'user'; }
enum Status: string { case DRAFT = 'draft'; case PUBLISHED = 'published'; }

?>