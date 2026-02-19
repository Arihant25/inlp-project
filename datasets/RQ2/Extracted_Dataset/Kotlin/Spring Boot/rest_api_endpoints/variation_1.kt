package com.example.classic

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import jakarta.persistence.*
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.*

// --- Domain Model ---

enum class UserRole {
    ADMIN, USER
}

@Entity
@Table(name = "users")
data class User(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(unique = true, nullable = false)
    var email: String,

    @Column(nullable = false)
    var passwordHash: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: UserRole,

    @Column(nullable = false)
    var isActive: Boolean = true,

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)

// --- DTOs (Data Transfer Objects) ---

data class UserResponse(
    val id: UUID,
    val email: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: Instant
)

data class CreateUserRequest(
    @field:Email(message = "Must be a valid email address")
    @field:NotEmpty(message = "Email cannot be empty")
    val email: String,

    @field:Size(min = 8, message = "Password must be at least 8 characters long")
    val password: String,

    @field:NotNull(message = "Role cannot be null")
    val role: UserRole
)

data class UpdateUserRequest(
    @field:Email(message = "Must be a valid email address")
    val email: String?,

    val role: UserRole?,
    val isActive: Boolean?
)

// --- Repository Layer ---

@Repository
interface UserRepository : JpaRepository<User, UUID> {
    fun findByIsActive(isActive: Boolean, pageable: Pageable): Page<User>
    fun findByEmailContainingIgnoreCase(email: String, pageable: Pageable): Page<User>
}

// --- Service Layer ---

interface UserService {
    fun createUser(request: CreateUserRequest): User
    fun getUserById(id: UUID): User?
    fun getAllUsers(pageable: Pageable): Page<User>
    fun updateUser(id: UUID, request: UpdateUserRequest): User
    fun deleteUser(id: UUID)
    fun searchUsers(email: String?, isActive: Boolean?, pageable: Pageable): Page<User>
}

@Service
class UserServiceImpl(private val userRepository: UserRepository) : UserService {

    override fun createUser(request: CreateUserRequest): User {
        // In a real app, hash the password here
        val passwordHash = "hashed_${request.password}"
        val user = User(
            email = request.email,
            passwordHash = passwordHash,
            role = request.role
        )
        return userRepository.save(user)
    }

    override fun getUserById(id: UUID): User? {
        return userRepository.findById(id).orElse(null)
    }

    override fun getAllUsers(pageable: Pageable): Page<User> {
        return userRepository.findAll(pageable)
    }

    override fun updateUser(id: UUID, request: UpdateUserRequest): User {
        val existingUser = userRepository.findById(id)
            .orElseThrow { UserNotFoundException(id) }

        existingUser.apply {
            email = request.email ?: email
            role = request.role ?: role
            isActive = request.isActive ?: isActive
        }

        return userRepository.save(existingUser)
    }

    override fun deleteUser(id: UUID) {
        if (!userRepository.existsById(id)) {
            throw UserNotFoundException(id)
        }
        userRepository.deleteById(id)
    }

    override fun searchUsers(email: String?, isActive: Boolean?, pageable: Pageable): Page<User> {
        return when {
            email != null -> userRepository.findByEmailContainingIgnoreCase(email, pageable)
            isActive != null -> userRepository.findByIsActive(isActive, pageable)
            else -> userRepository.findAll(pageable)
        }
    }
}

// --- Controller / API Layer ---

@RestController
@RequestMapping("/api/v1/users")
class UserController(private val userService: UserService) {

    private fun User.toResponse() = UserResponse(id, email, role, isActive, createdAt)

    @PostMapping
    fun createUser(@Valid @RequestBody request: CreateUserRequest): ResponseEntity<UserResponse> {
        val user = userService.createUser(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(user.toResponse())
    }

    @GetMapping("/{id}")
    fun getUserById(@PathVariable id: UUID): ResponseEntity<UserResponse> {
        return userService.getUserById(id)
            ?.let { ResponseEntity.ok(it.toResponse()) }
            ?: throw UserNotFoundException(id)
    }

    @GetMapping
    fun listUsers(pageable: Pageable): ResponseEntity<Page<UserResponse>> {
        val userPage = userService.getAllUsers(pageable).map { it.toResponse() }
        return ResponseEntity.ok(userPage)
    }
    
    @GetMapping("/search")
    fun searchUsers(
        @RequestParam(required = false) email: String?,
        @RequestParam(required = false) isActive: Boolean?,
        pageable: Pageable
    ): ResponseEntity<Page<UserResponse>> {
        val users = userService.searchUsers(email, isActive, pageable).map { it.toResponse() }
        return ResponseEntity.ok(users)
    }

    @PutMapping("/{id}")
    fun updateUser(@PathVariable id: UUID, @Valid @RequestBody request: UpdateUserRequest): ResponseEntity<UserResponse> {
        val updatedUser = userService.updateUser(id, request)
        return ResponseEntity.ok(updatedUser.toResponse())
    }
    
    @PatchMapping("/{id}")
    fun patchUser(@PathVariable id: UUID, @Valid @RequestBody request: UpdateUserRequest): ResponseEntity<UserResponse> {
        val updatedUser = userService.updateUser(id, request)
        return ResponseEntity.ok(updatedUser.toResponse())
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteUser(@PathVariable id: UUID) {
        userService.deleteUser(id)
    }
}

// --- Exception Handling ---

class UserNotFoundException(id: UUID) : RuntimeException("User with ID $id not found")

@ControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(UserNotFoundException::class)
    fun handleUserNotFound(ex: UserNotFoundException): ResponseEntity<Map<String, String>> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(mapOf("error" to (ex.message ?: "Resource not found")))
    }
}