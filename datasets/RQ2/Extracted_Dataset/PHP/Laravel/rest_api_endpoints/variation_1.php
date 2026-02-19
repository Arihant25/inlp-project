<pre>
// Variation 1: The "By-the-Book" Standardist
// This developer follows official Laravel conventions closely, using API resources,
// form requests, and a standard resource controller. Filtering logic is handled
// explicitly within the controller's index method.

// database/migrations/2023_01_01_000000_create_users_table.php
&lt;?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('users', function (Blueprint $table) {
            $table-&gt;uuid('id')-&gt;primary();
            $table-&gt;string('email')-&gt;unique();
            $table-&gt;string('password_hash');
            $table-&gt;string('role')-&gt;default('USER'); // ADMIN, USER
            $table-&gt;boolean('is_active')-&gt;default(true);
            $table-&gt;timestamp('email_verified_at')-&gt;nullable();
            $table-&gt;rememberToken();
            $table-&gt;timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('users');
    }
};

// app/Enums/UserRole.php
&lt;?php

namespace App\Enums;

enum UserRole: string
{
    case ADMIN = 'ADMIN';
    case USER = 'USER';
}

// app/Models/User.php
&lt;?php

namespace App\Models;

use App\Enums\UserRole;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Foundation\Auth\User as Authenticatable;
use Illuminate\Notifications\Notifiable;

class User extends Authenticatable
{
    use HasFactory, Notifiable, HasUuids;

    protected $fillable = [
        'email',
        'password_hash',
        'role',
        'is_active',
    ];

    protected $hidden = [
        'password_hash',
        'remember_token',
    ];

    protected $casts = [
        'is_active' =&gt; 'boolean',
        'email_verified_at' =&gt; 'datetime',
        'password_hash' =&gt; 'hashed',
        'role' =&gt; UserRole::class,
    ];
}

// app/Http/Requests/StoreUserRequest.php
&lt;?php

namespace App\Http\Requests;

use App\Enums\UserRole;
use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rules\Enum;
use Illuminate\Validation\Rules\Password;

class StoreUserRequest extends FormRequest
{
    public function authorize(): bool
    {
        // In a real app, you'd check if the authenticated user can create users.
        return true;
    }

    public function rules(): array
    {
        return [
            'email' =&gt; ['required', 'email', 'unique:users,email'],
            'password' =&gt; ['required', 'confirmed', Password::defaults()],
            'role' =&gt; ['sometimes', 'required', new Enum(UserRole::class)],
            'is_active' =&gt; ['sometimes', 'required', 'boolean'],
        ];
    }
}

// app/Http/Requests/UpdateUserRequest.php
&lt;?php

namespace App\Http\Requests;

use App\Enums\UserRole;
use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rules\Enum;
use Illuminate\Validation\Rules\Password;

class UpdateUserRequest extends FormRequest
{
    public function authorize(): bool
    {
        // In a real app, you'd check if the authenticated user can update this specific user.
        return true;
    }

    public function rules(): array
    {
        return [
            'email' =&gt; ['sometimes', 'required', 'email', 'unique:users,email,' . $this-&gt;user-&gt;id],
            'password' =&gt; ['sometimes', 'required', 'confirmed', Password::defaults()],
            'role' =&gt; ['sometimes', 'required', new Enum(UserRole::class)],
            'is_active' =&gt; ['sometimes', 'required', 'boolean'],
        ];
    }
}

// app/Http/Resources/UserResource.php
&lt;?php

namespace App\Http\Resources;

use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

class UserResource extends JsonResource
{
    public function toArray(Request $request): array
    {
        return [
            'id' =&gt; $this-&gt;id,
            'email' =&gt; $this-&gt;email,
            'role' =&gt; $this-&gt;role,
            'is_active' =&gt; $this-&gt;is_active,
            'created_at' =&gt; $this-&gt;created_at-&gt;toIso8601String(),
        ];
    }
}

// app/Http/Controllers/Api/V1/UserController.php
&lt;?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Http\Requests\StoreUserRequest;
use App\Http\Requests\UpdateUserRequest;
use App\Http\Resources\UserResource;
use App\Models\User;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\Hash;

class UserController extends Controller
{
    public function index(Request $request)
    {
        $query = User::query();

        if ($request-&gt;has('is_active')) {
            $query-&gt;where('is_active', filter_var($request-&gt;is_active, FILTER_VALIDATE_BOOLEAN));
        }

        if ($request-&gt;has('role')) {
            $query-&gt;where('role', $request-&gt;role);
        }

        if ($request-&gt;has('search')) {
            $query-&gt;where('email', 'like', '%' . $request-&gt;search . '%');
        }

        $users = $query-&gt;paginate(15);

        return UserResource::collection($users);
    }

    public function store(StoreUserRequest $request)
    {
        $validatedData = $request-&gt;validated();

        $user = User::create([
            'email' =&gt; $validatedData['email'],
            'password_hash' =&gt; Hash::make($validatedData['password']),
            'role' =&gt; $validatedData['role'] ?? 'USER',
            'is_active' =&gt; $validatedData['is_active'] ?? true,
        ]);

        return new UserResource($user);
    }

    public function show(User $user)
    {
        return new UserResource($user);
    }

    public function update(UpdateUserRequest $request, User $user)
    {
        $validatedData = $request-&gt;validated();

        if (isset($validatedData['password'])) {
            $validatedData['password_hash'] = Hash::make($validatedData['password']);
            unset($validatedData['password']);
        }

        $user-&gt;update($validatedData);

        return new UserResource($user-&gt;fresh());
    }

    public function destroy(User $user)
    {
        $user-&gt;delete();
        return response()-&gt;json(null, Response::HTTP_NO_CONTENT);
    }
}

// routes/api.php
&lt;?php

use App\Http\Controllers\Api\V1\UserController;
use Illuminate\Support\Facades\Route;

Route::prefix('v1')-&gt;group(function () {
    Route::apiResource('users', UserController::class);
});

</pre>