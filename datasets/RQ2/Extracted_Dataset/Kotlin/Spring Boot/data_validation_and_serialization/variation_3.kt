package com.example.functional.validation

import jakarta.validation.ConstraintViolation
import jakarta.validation.Validator
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

// --- MOCK DEPENDENCIES (e.g., build.gradle.kts) ---
// implementation("org.springframework.boot:spring-boot-starter-web")
// implementation("org.springframework.boot:spring-boot-starter-validation")
// implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

// --- DOMAIN MODELS ---

enum class Role { ADMIN, USER }
enum class Status { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val role: Role,
    val isActive: Boolean,
    val createdAt: Timestamp
)

data class Post(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: Status
)

// --- DTOs & COMMANDS ---

data class CreateUserCommand(
    @field:NotBlank(message = "Email must not be empty")
    @field:Email(message = "Must be a valid email format")
    val email: String,

    @field:NotBlank
    @field:Size(min = 10, message = "Password must be at least 10 characters")
    val password: String,

    val role: Role = Role.USER
)

data class UserView(
    val id: UUID,
    val email: String,
    val role: Role,
    val createdAt: Timestamp
)

// --- SERVICE LAYER & PROGRAMMATIC VALIDATION ---

sealed class CreationResult<out T> {
    data class Success<T>(val data: T) : CreationResult<T>()
    data class ValidationFailure(val errors: Map<String, String>) : CreationResult<Nothing>()
    data class Conflict(val message: String) : CreationResult<Nothing>()
}

@Service
class UserService(private val validator: Validator) {
    // In-memory storage for demonstration
    private val users = mutableMapOf<String, User>()

    fun createUser(command: CreateUserCommand): CreationResult<User> {
        // 1. Programmatic Validation
        val violations: Set<ConstraintViolation<CreateUserCommand>> = validator.validate(command)
        if (violations.isNotEmpty()) {
            val errors = violations.associate { it.propertyPath.toString() to it.message }
            return CreationResult.ValidationFailure(errors)
        }

        // 2. Business Logic Validation
        if (users.containsKey(command.email)) {
            return CreationResult.Conflict("User with email ${command.email} already exists.")
        }

        // 3. Create Entity
        val newUser = User(
            id = UUID.randomUUID(),
            email = command.email,
            passwordHash = "hashed:${command.password}", // Use a real hasher
            role = command.role,
            isActive = true,
            createdAt = Timestamp.from(Instant.now())
        )
        users[newUser.email] = newUser
        return CreationResult.Success(newUser)
    }

    fun findUser(id: UUID): User? = users.values.find { it.id == id }
}

// --- CONTROLLER (RESOURCE) LAYER ---

@RestController
@RequestMapping("/api/users")
class UserResource(private val userService: UserService) {

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun handleUserCreation(@RequestBody command: CreateUserCommand): ResponseEntity<*> {
        return when (val result = userService.createUser(command)) {
            is CreationResult.Success -> {
                val userView = UserView(result.data.id, result.data.email, result.data.role, result.data.createdAt)
                ResponseEntity.status(HttpStatus.CREATED).body(userView)
            }
            is CreationResult.ValidationFailure -> {
                ResponseEntity.badRequest().body(mapOf("validationErrors" to result.errors))
            }
            is CreationResult.Conflict -> {
                ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to result.message))
            }
        }
    }

    @GetMapping("/{id}")
    fun handleGetUser(@PathVariable id: UUID): ResponseEntity<UserView> {
        return userService.findUser(id)
            ?.let { user ->
                val userView = UserView(user.id, user.email, user.role, user.createdAt)
                ResponseEntity.ok(userView)
            }
            ?: ResponseEntity.notFound().build()
    }
}

// --- APPLICATION ---

@SpringBootApplication
class FunctionalValidationApplication

fun main(args: Array<String>) {
    runApplication<FunctionalValidationApplication>(*args)
}