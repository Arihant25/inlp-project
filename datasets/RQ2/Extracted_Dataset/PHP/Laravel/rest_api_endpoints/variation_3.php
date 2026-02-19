<pre>
// Variation 3: The "Query Builder & Repository" Architect
// This developer abstracts data logic into a Repository layer for better separation
// of concerns and testability. They leverage the `spatie/laravel-query-builder`
// package to handle complex filtering and searching declaratively, keeping the
// controller and repository extremely lean.

// composer.json (dependency)
// {
//     "require": {
//         "spatie/laravel-query-builder": "^5.2"
//     }
// }

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
            $table-&gt;string('role')-&gt;default('USER');
            $table-&gt;boolean('is_active')-&gt;default(true);
            $table-&gt;timestamps();
        });
    }
    public function down(): void { Schema::dropIfExists('users'); }
};

// app/Enums/UserRole.php
&lt;?php namespace App\Enums; enum UserRole: string { case ADMIN = 'ADMIN'; case USER = 'USER'; }

// app/Models/User.php
&lt;?php

namespace App\Models;

use App\Enums\UserRole;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Foundation\Auth\User as Authenticatable;

class User extends Authenticatable
{
    use HasFactory, HasUuids;
    protected $fillable = ['email', 'password_hash', 'role', 'is_active'];
    protected $hidden = ['password_hash'];
    protected $casts = [
        'is_active' =&gt; 'boolean',
        'password_hash' =&gt; 'hashed',
        'role' =&gt; UserRole::class,
    ];
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

// app/Repositories/UserRepositoryInterface.php
&lt;?php

namespace App\Repositories;

use App\Models\User;
use Illuminate\Contracts\Pagination\LengthAwarePaginator;

interface UserRepositoryInterface
{
    public function getPaginatedAndFiltered(): LengthAwarePaginator;
    public function create(array $data): User;
    public function update(User $user, array $data): bool;
    public function delete(User $user): bool;
}

// app/Repositories/Eloquent/UserRepository.php
&lt;?php

namespace App\Repositories\Eloquent;

use App\Models\User;
use App\Repositories\UserRepositoryInterface;
use Illuminate\Contracts\Pagination\LengthAwarePaginator;
use Illuminate\Support\Facades\Hash;
use Spatie\QueryBuilder\AllowedFilter;
use Spatie\QueryBuilder\QueryBuilder;

class UserRepository implements UserRepositoryInterface
{
    public function getPaginatedAndFiltered(): LengthAwarePaginator
    {
        return QueryBuilder::for(User::class)
            -&gt;allowedFilters([
                AllowedFilter::partial('search', 'email'),
                AllowedFilter::exact('role'),
                AllowedFilter::exact('is_active'),
            ])
            -&gt;defaultSort('-created_at')
            -&gt;paginate(15)
            -&gt;withQueryString();
    }

    public function create(array $data): User
    {
        return User::create([
            'email' =&gt; $data['email'],
            'password_hash' =&gt; Hash::make($data['password']),
            'role' =&gt; $data['role'] ?? 'USER',
            'is_active' =&gt; $data['is_active'] ?? true,
        ]);
    }

    public function update(User $user, array $data): bool
    {
        if (isset($data['password'])) {
            $data['password_hash'] = Hash::make($data['password']);
        }
        return $user-&gt;update($data);
    }

    public function delete(User $user): bool
    {
        return $user-&gt;delete();
    }
}

// app/Providers/RepositoryServiceProvider.php
&lt;?php

namespace App\Providers;

use App\Repositories\Eloquent\UserRepository;
use App\Repositories\UserRepositoryInterface;
use Illuminate\Support\ServiceProvider;

class RepositoryServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this-&gt;app-&gt;bind(UserRepositoryInterface::class, UserRepository::class);
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
use App\Repositories\UserRepositoryInterface;
use Illuminate\Http\Response;

class UserController extends Controller
{
    public function __construct(private readonly UserRepositoryInterface $userRepository) {}

    public function index()
    {
        $users = $this-&gt;userRepository-&gt;getPaginatedAndFiltered();
        return UserResource::collection($users);
    }

    public function store(StoreUserRequest $request)
    {
        $user = $this-&gt;userRepository-&gt;create($request-&gt;validated());
        return new UserResource($user);
    }

    public function show(User $user)
    {
        return new UserResource($user);
    }

    public function update(UpdateUserRequest $request, User $user)
    {
        $this-&gt;userRepository-&gt;update($user, $request-&gt;validated());
        return new UserResource($user-&gt;fresh());
    }

    public function destroy(User $user)
    {
        $this-&gt;userRepository-&gt;delete($user);
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