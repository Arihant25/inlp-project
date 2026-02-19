<php
/**
 * Variation 2: The "Fat Controller" / Functional Developer
 *
 * Style: More procedural, logic is kept within the controller.
 * - Avoids creating extra classes like Services or Form Requests.
 * - Validation is done directly in the controller method using `$request->validate()`.
 * - File operations are handled directly using Facades or helpers.
 * - Variable names are often shorter and more concise.
 * - For downloads, uses a simple `response()->download()` with temporary file cleanup.
 *
 * To run this:
 * 1. composer require intervention/image
 * 2. Define routes in routes/web.php pointing to FileOperationsController methods.
 */

namespace App\Http\Controllers\Variation2;

use Illuminate\Http\Request;
use Illuminate\Routing\Controller;
use Illuminate\Support\Facades\Storage;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\Validator;
use Illuminate\Support\Str;
use Intervention\Image\ImageManagerStatic as Image;
use App\Models\Variation2\User;
use App\Models\Variation2\Post;
use App\Enums\Variation2\Role;
use App\Enums\Variation2\Status;

class FileOperationsController extends Controller
{
    /**
     * Handles user import from a CSV file.
     */
    public function importUsers(Request $req)
    {
        $req->validate([
            'user_file' => 'required|file|mimes:csv,txt|max:10240',
        ]);

        $file = $req->file('user_file');
        $path = $file->getRealPath();
        
        // Use a temporary file path for processing
        $tempPath = storage_path('app/temp/' . $file->hashName());
        $file->move(storage_path('app/temp'), $file->hashName());

        $handle = fopen($tempPath, "r");
        $header = true;
        $count = 0;

        $usersToInsert = [];
        while (($data = fgetcsv($handle, 1000, ",")) !== FALSE) {
            if ($header) {
                $header = false;
                continue;
            }

            // Basic validation for each row
            $validator = Validator::make([
                'email' => $data[0],
                'password' => $data[1],
            ], [
                'email' => 'required|email|unique:users,email',
                'password' => 'required|min:8',
            ]);

            if ($validator->fails()) {
                // Skip invalid rows, maybe log the error
                continue;
            }

            $usersToInsert[] = [
                'id' => Str::uuid(),
                'email' => $data[0],
                'password_hash' => Hash::make($data[1]),
                'role' => strtoupper($data[2]) === 'ADMIN' ? Role::ADMIN->value : Role::USER->value,
                'is_active' => filter_var($data[3], FILTER_VALIDATE_BOOLEAN),
                'created_at' => now(),
                'updated_at' => now(),
            ];
            $count++;
        }
        fclose($handle);

        // Insert in chunks
        foreach (array_chunk($usersToInsert, 500) as $chunk) {
            User::insert($chunk);
        }
        
        // Temporary file management
        unlink($tempPath);

        return response()->json(['message' => "Processed and imported $count users."]);
    }

    /**
     * Uploads and resizes an image for a specific post.
     */
    public function uploadPostImage(Request $req, string $postId)
    {
        $post = Post::findOrFail($postId);

        $req->validate([
            'image' => 'required|image|max:4096',
        ]);

        $imgFile = $req->file('image');
        $filename = 'post_' . $post->id . '.' . $imgFile->getClientOriginalExtension();
        $path = 'post_images/' . $filename;

        $img = Image::make($imgFile);
        $img->resize(800, null, function ($constraint) {
            $constraint->aspectRatio();
            $constraint->upsize();
        });

        Storage::disk('public')->put($path, (string) $img->encode());

        return response()->json(['url' => Storage::url($path)]);
    }

    /**
     * Generates and downloads a report of all posts.
     */
    public function downloadPostsReport()
    {
        $posts = Post::with('user')->where('status', Status::PUBLISHED)->get();
        
        // Temporary file management
        $tempFilePath = tempnam(sys_get_temp_dir(), 'posts_');
        $handle = fopen($tempFilePath, 'w');

        fputcsv($handle, ['Post ID', 'Title', 'Author']);
        foreach ($posts as $post) {
            fputcsv($handle, [$post->id, $post->title, $post->user->email]);
        }
        fclose($handle);

        $filename = 'posts-report-' . date('Y-m-d') . '.csv';

        return response()->download($tempFilePath, $filename)->deleteFileAfterSend(true);
    }
}


// --- Mocked Domain Model ---
namespace App\Models\Variation2;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use App\Enums\Variation2\Status;

class Post extends Model {
    use HasUuids;
    protected $casts = ['status' => Status::class];
    protected $guarded = [];
    public function user(): BelongsTo { return $this->belongsTo(User::class); }
}

class User extends Model {
    use HasUuids;
    protected $casts = ['role' => \App\Enums\Variation2\Role::class];
    protected $guarded = [];
}

namespace App\Enums\Variation2;

enum Role: string { case ADMIN = 'admin'; case USER = 'user'; }
enum Status: string { case DRAFT = 'draft'; case PUBLISHED = 'published'; }

?>