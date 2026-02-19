package com.example.enterprise.validation

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.*
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass

// --- MOCK DEPENDENCIES (e.g., build.gradle.kts) ---
// implementation("org.springframework.boot:spring-boot-starter-web")
// implementation("org.springframework.boot:spring-boot-starter-validation")
// implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")
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

// --- CUSTOM VALIDATION ---

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [PhoneNumberValidator::class])
annotation class ValidPhoneNumber(
    val message: String = "Invalid phone number format. Expected format: +1-555-555-5555",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

class PhoneNumberValidator : ConstraintValidator<ValidPhoneNumber, String> {
    private val phoneRegex = Regex("^\\+[1-9]\\d{0,2}-\\d{3}-\\d{3}-\\d{4}$")

    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean {
        return value == null || value.matches(phoneRegex)
    }
}

// --- DATA TRANSFER OBJECTS (DTOs) ---

data class UserCreationRequestDto(
    @field:NotBlank(message = "Email cannot be blank.")
    @field:Email(message = "Email should be a valid email address.")
    @field:Size(max = 100, message = "Email must be less than 100 characters.")
    val email: String,

    @field:NotBlank(message = "Password cannot be blank.")
    @field:Size(min = 8, message = "Password must be at least 8 characters long.")
    val password: String,

    @field:ValidPhoneNumber
    val phoneNumber: String?,

    val role: Role = Role.USER
)

@JacksonXmlRootElement(localName = "User")
data class UserResponseDto(
    @JsonProperty("userId")
    val id: UUID,
    val email: String,
    val role: Role,
    @JsonProperty("active")
    val isActive: Boolean,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    val createdAt: Timestamp
)

// --- ERROR HANDLING ---

data class ApiErrorResponse(
    val status: Int,
    val message: String,
    val errors: Map<String, String?>
)

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleValidationExceptions(ex: MethodArgumentNotValidException): ApiErrorResponse {
        val errors = ex.bindingResult.allErrors.associate { error ->
            val fieldName = (error as FieldError).field
            fieldName to error.defaultMessage
        }
        return ApiErrorResponse(
            status = HttpStatus.BAD_REQUEST.value(),
            message = "Validation failed for one or more fields.",
            errors = errors
        )
    }
}

// --- CONTROLLER ---

@RestController
@RequestMapping("/api/v1/users")
class UserController {

    // In-memory storage for demonstration
    private val users = mutableMapOf<UUID, User>()

    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE]
    )
    fun createUser(@jakarta.validation.Valid @RequestBody request: UserCreationRequestDto): ResponseEntity<UserResponseDto> {
        val newUser = User(
            id = UUID.randomUUID(),
            email = request.email,
            passwordHash = "hashed_${request.password}", // In a real app, hash the password
            role = request.role,
            isActive = true,
            createdAt = Timestamp.from(Instant.now())
        )
        users[newUser.id] = newUser

        val responseDto = UserResponseDto(
            id = newUser.id,
            email = newUser.email,
            role = newUser.role,
            isActive = newUser.isActive,
            createdAt = newUser.createdAt
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(responseDto)
    }

    @GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE])
    fun getUserById(@PathVariable id: UUID): ResponseEntity<UserResponseDto> {
        val user = users[id]
        return if (user != null) {
            val responseDto = UserResponseDto(
                id = user.id,
                email = user.email,
                role = user.role,
                isActive = user.isActive,
                createdAt = user.createdAt
            )
            ResponseEntity.ok(responseDto)
        } else {
            ResponseEntity.notFound().build()
        }
    }
}

// --- SPRING BOOT APPLICATION ---

@SpringBootApplication
class EnterpriseValidationApplication

fun main(args: Array<String>) {
    runApplication<EnterpriseValidationApplication>(*args)
}