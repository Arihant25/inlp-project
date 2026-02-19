<pre>
// Variation 2: The "Action-Oriented" Developer
// This developer prefers Single Action Controllers (invokable), dedicating a class
// to each endpoint. This promotes the Single Responsibility Principle. Filtering
// logic is encapsulated into a local scope on the model for a cleaner controller.

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
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Foundation\Auth\User as Authenticatable;
use Illuminate\Http\Request;
use Illuminate\Notifications\Notifiable;

class User extends Authenticatable
{
    use HasFactory, Notifiable, HasUuids;

    protected $fillable = ['email', 'password_hash', 'role', 'is_active'];
    protected $hidden = ['password_hash', 'remember_token'];
    protected $casts = [
        'is_active' =&gt; 'boolean',
        'email_verified_at' =&gt; 'datetime',
        'password_hash' =&gt; 'hashed',
        'role' =&gt; UserRole::class,
    ];

    public function scopeFilter(Builder $query, Request $request): Builder
    {
        return $query
            -&gt;when($request-&gt;query('search'), function ($q, $search) {
                $q-&gt;where('email', 'like', "%{$search}%");
            })
            -&gt;when($request-&gt;filled('is_active'), function ($q) use ($request) {
                $q-&gt;where('is_active', filter_var($request-&gt;is_active, FILTER_VALIDATE_BOOLEAN));
            })
            -&gt;when($request-&gt;query('role'), function ($q, $role) {
                $q-&gt;where('role', $role);
            });
    }
}

// app/Http/Requests/StoreUserRequest.php (Identical to Variation 1)
&lt;?php

namespace App\Http\Requests;

use App\Enums\UserRole;
use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rules\Enum;
use Illuminate\Validation\Rules\Password;

class StoreUserRequest extends FormRequest
{
    public function authorize(): bool { return true; }
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

// app/Http/Requests/UpdateUserRequest.php (Identical to Variation 1)
&lt;?php

namespace App\Http\Requests;

use App\Enums\UserRole;
use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rules\Enum;
use Illuminate\Validation\Rules\Password;

class UpdateUserRequest extends FormRequest
{
    public function authorize(): bool { return true; }
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

// app/Http/Resources/UserResource.php (Identical to Variation 1)
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

// app/Http/Controllers/Api/V1/User/ListUsersAction.php
&lt;?php

namespace App\Http\Controllers\Api\V1\User;

use App\Http\Controllers\Controller;
use App\Http\Resources\UserResource;
use App\Models\User;
use Illuminate\Http\Request;

class ListUsersAction extends Controller
{
    public function __invoke(Request $request)
    {
        $users = User::query()
            -&gt;filter($request)
            -&gt;paginate(15)
            -&gt;withQueryString();

        return UserResource::collection($users);
    }
}

// app/Http/Controllers/Api/V1/User/StoreUserAction.php
&lt;?php

namespace App\Http\Controllers\Api\V1\User;

use App\Http\Controllers\Controller;
use App\Http\Requests\StoreUserRequest;
use App\Http\Resources\UserResource;
use App\Models\User;
use Illuminate\Support\Facades\Hash;

class StoreUserAction extends Controller
{
    public function __invoke(StoreUserRequest $request)
    {
        $validated = $request-&gt;validated();
        $user = User::create([
            'email' =&gt; $validated['email'],
            'password_hash' =&gt; Hash::make($validated['password']),
            'role' =&gt; $validated['role'] ?? 'USER',
            'is_active' =&gt; $validated['is_active'] ?? true,
        ]);
        return new UserResource($user);
    }
}

// app/Http/Controllers/Api/V1/User/ShowUserAction.php
&lt;?php

namespace App\Http\Controllers\Api\V1\User;

use App\Http\Controllers\Controller;
use App\Http\Resources\UserResource;
use App\Models\User;

class ShowUserAction extends Controller
{
    public function __invoke(User $user)
    {
        return new UserResource($user);
    }
}

// app/Http/Controllers/Api/V1/User/UpdateUserAction.php
&lt;?php

namespace App\Http\Controllers\Api\V1\User;

use App\Http\Controllers\Controller;
use App\Http\Requests\UpdateUserRequest;
use App\Http\Resources\UserResource;
use App\Models\User;
use Illuminate\Support\Facades\Hash;

class UpdateUserAction extends Controller
{
    public function __invoke(UpdateUserRequest $request, User $user)
    {
        $validated = $request-&gt;validated();
        if (isset($validated['password'])) {
            $validated['password_hash'] = Hash::make($validated['password']);
        }
        $user-&gt;update($validated);
        return new UserResource($user);
    }
}

// app/Http/Controllers/Api/V1/User/DestroyUserAction.php
&lt;?php

namespace App\Http\Controllers\Api\V1\User;

use App\Http\Controllers\Controller;
use App\Models\User;
use Illuminate\Http\Response;

class DestroyUserAction extends Controller
{
    public function __invoke(User $user)
    {
        $user-&gt;delete();
        return response()-&gt;json(null, Response::HTTP_NO_CONTENT);
    }
}

// routes/api.php
&lt;?php

use App\Http\Controllers\Api\V1\User\DestroyUserAction;
use App\Http\Controllers\Api\V1\User\ListUsersAction;
use App\Http\Controllers\Api\V1\User\ShowUserAction;
use App\Http\Controllers\Api\V1\User\StoreUserAction;
use App\Http\Controllers\Api\V1\User\UpdateUserAction;
use Illuminate\Support\Facades\Route;

Route::prefix('v1/users')-&gt;name('users.')-&gt;group(function () {
    Route::get('/', ListUsersAction::class)-&gt;name('index');
    Route::post('/', StoreUserAction::class)-&gt;name('store');
    Route::get('/{user:id}', ShowUserAction::class)-&gt;name('show');
    Route::match(['put', 'patch'], '/{user:id}', UpdateUserAction::class)-&gt;name('update');
    Route::delete('/{user:id}', DestroyUserAction::class)-&gt;name('destroy');
});

</pre>