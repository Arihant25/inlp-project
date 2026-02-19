package com.example.cqrs

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
import java.time.Instant
import java.util.*

// --- Domain Model ---
enum class UserRole { ADMIN, USER }

@Entity
@Table(name = "users")
data class User(
    @Id val id: UUID,
    @Column(unique = true) var email: String,
    var passwordHash: String,
    @Enumerated(EnumType.STRING) var role: UserRole,
    var isActive: Boolean,
    val createdAt: Instant
)

// --- DTOs: Commands, Queries, and Views ---
// Commands (Intention to change state)
data class CreateUserCommand(
    @field:Email @field:NotEmpty val email: String,
    @field:NotEmpty val password: String,
    @field:NotNull val role: UserRole
)

data class UpdateUserCommand(
    @field:NotNull val id: UUID,
    @field:Email val email: String?,
    val role: UserRole?,
    val isActive: Boolean?
)

// Search Criteria
data class UserSearchCriteria(
    val email: String? = null,
    val role: UserRole? = null,
    val active: Boolean? = null
)

// Views (Read-optimized representations)
data class UserDetailedView(
    val id: UUID,
    val email: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: Instant
)

// --- Repository ---
@Repository
interface UserRepo : JpaRepository<User, UUID> {
    fun existsByEmail(email: String): Boolean
    fun findAllBy(pageable: Pageable): Page<User> // Placeholder for more complex criteria query
}

// --- Custom Exceptions ---
class EmailAlreadyExistsException(email: String) : ResponseStatusException(HttpStatus.CONFLICT, "Email '$email' is already in use.")
class UserNotFoundQueryException(id: UUID) : ResponseStatusException(HttpStatus.NOT_FOUND, "User with id '$id' not found.")

// --- CQRS Services ---

@Service
class UserCommandService(private val userRepo: UserRepo) {
    fun handle(command: CreateUserCommand): UUID {
        if (userRepo.existsByEmail(command.email)) {
            throw EmailAlreadyExistsException(command.email)
        }
        val user = User(
            id = UUID.randomUUID(),
            email = command.email,
            passwordHash = command.password.reversed(), // Dummy hashing
            role = command.role,
            isActive = true,
            createdAt = Instant.now()
        )
        userRepo.save(user)
        return user.id
    }

    fun handle(command: UpdateUserCommand) {
        val user = userRepo.findById(command.id).orElseThrow { UserNotFoundQueryException(command.id) }
        command.email?.let { user.email = it }
        command.role?.let { user.role = it }
        command.isActive?.let { user.isActive = it }
        userRepo.save(user)
    }

    fun deleteUser(id: UUID) {
        if (!userRepo.existsById(id)) {
            throw UserNotFoundQueryException(id)
        }
        userRepo.deleteById(id)
    }
}

@Service
class UserQueryService(private val userRepo: UserRepo) {
    private fun User.toDetailedView() = UserDetailedView(id, email, role, isActive, createdAt)

    fun findById(id: UUID): UserDetailedView {
        return userRepo.findById(id)
            .map { it.toDetailedView() }
            .orElseThrow { UserNotFoundQueryException(id) }
    }

    fun search(criteria: UserSearchCriteria, pageable: Pageable): Page<UserDetailedView> {
        // In a real app, this would use Specifications or QueryDSL based on criteria
        return userRepo.findAll(pageable).map { it.toDetailedView() }
    }
}

// --- API Endpoint ---
@RestController
@RequestMapping("/api/cqrs/users")
class UserEndpoint(
    private val commandService: UserCommandService,
    private val queryService: UserQueryService
) {

    @PostMapping
    fun createUser(@Valid @RequestBody command: CreateUserCommand): ResponseEntity<Map<String, UUID>> {
        val newUserId = commandService.handle(command)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(mapOf("id" to newUserId))
    }

    @GetMapping("/{id}")
    fun getUserById(@PathVariable id: UUID): ResponseEntity<UserDetailedView> {
        val userView = queryService.findById(id)
        return ResponseEntity.ok(userView)
    }

    @GetMapping
    fun findUsers(criteria: UserSearchCriteria, pageable: Pageable): ResponseEntity<Page<UserDetailedView>> {
        val results = queryService.search(criteria, pageable)
        return ResponseEntity.ok(results)
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateUser(@PathVariable id: UUID, @RequestBody payload: Map<String, Any>) {
        val command = UpdateUserCommand(
            id = id,
            email = payload["email"] as? String,
            role = (payload["role"] as? String)?.let { UserRole.valueOf(it) },
            isActive = payload["isActive"] as? Boolean
        )
        commandService.handle(command)
    }

    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun patchUser(@PathVariable id: UUID, @RequestBody payload: Map<String, Any>) {
        // For CQRS, PUT and PATCH can be handled by the same update command
        updateUser(id, payload)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteUser(@PathVariable id: UUID) {
        commandService.deleteUser(id)
    }
}