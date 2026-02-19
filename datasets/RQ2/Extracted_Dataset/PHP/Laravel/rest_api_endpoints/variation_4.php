<pre>
// Variation 4: The "Functional & Concise" Minimalist
// This developer prefers to keep things simple and concise, often for smaller
// projects or microservices. They use route closures in `api.php` for all logic,
// avoiding boilerplate like controllers, form requests, and API resources.
// Validation and response shaping are handled inline.

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

    // A simple transformer method to avoid a full Resource class
    public function toApi(): array
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

// routes/api.php
&lt;?php

use App\Enums\UserRole;
use App\Models\User;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\Route;
use Illuminate\Validation\Rules\Enum;
use Illuminate\Validation\Rules\Password;

Route::prefix('v1/users')-&gt;group(function () {

    // List users with pagination and filtering
    Route::get('/', function (Request $request) {
        $users = User::query()
            -&gt;when($request-&gt;input('search'), fn($query, $search) =&gt;
                $query-&gt;where('email', 'like', "%{$search}%")
            )
            -&gt;when($request-&gt;filled('is_active'), fn($query) =&gt;
                $query-&gt;where('is_active', filter_var($request-&gt;is_active, FILTER_VALIDATE_BOOLEAN))
            )
            -&gt;when($request-&gt;input('role'), fn($query, $role) =&gt;
                $query-&gt;where('role', $role)
            )
            -&gt;paginate(15);

        return response()-&gt;json($users);
    });

    // Create user
    Route::post('/', function (Request $request) {
        $validated = $request-&gt;validate([
            'email' =&gt; ['required', 'email', 'unique:users,email'],
            'password' =&gt; ['required', 'confirmed', Password::defaults()],
            'role' =&gt; ['sometimes', 'required', new Enum(UserRole::class)],
            'is_active' =&gt; ['sometimes', 'required', 'boolean'],
        ]);

        $user = User::create([
            'email' =&gt; $validated['email'],
            'password_hash' =&gt; Hash::make($validated['password']),
            'role' =&gt; $validated['role'] ?? 'USER',
            'is_active' =&gt; $validated['is_active'] ?? true,
        ]);

        return response()-&gt;json($user-&gt;toApi(), Response::HTTP_CREATED);
    });

    // Get user by ID
    Route::get('/{user}', function (User $user) {
        return response()-&gt;json($user-&gt;toApi());
    });

    // Update user
    Route::put('/{user}', function (Request $request, User $user) {
        $validated = $request-&gt;validate([
            'email' =&gt; ['sometimes', 'required', 'email', 'unique:users,email,' . $user-&gt;id],
            'password' =&gt; ['sometimes', 'nullable', 'confirmed', Password::defaults()],
            'role' =&gt; ['sometimes', 'required', new Enum(UserRole::class)],
            'is_active' =&gt; ['sometimes', 'required', 'boolean'],
        ]);

        if (!empty($validated['password'])) {
            $validated['password_hash'] = Hash::make($validated['password']);
        }
        unset($validated['password']);

        $user-&gt;update($validated);

        return response()-&gt;json($user-&gt;fresh()-&gt;toApi());
    });

    // Delete user
    Route::delete('/{user}', function (User $user) {
        $user-&gt;delete();
        return response()-&gt;json(null, Response::HTTP_NO_CONTENT);
    });
});

</pre>