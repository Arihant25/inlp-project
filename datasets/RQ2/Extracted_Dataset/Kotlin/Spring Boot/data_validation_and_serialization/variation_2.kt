package com.example.concise.validation

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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

// --- DOMAIN & DTOs ---

enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val passHash: String,
    val role: UserRole,
    val active: Boolean,
    val created: Timestamp
)

data class Post(
    val id: UUID,
    val authorId: UUID,
    val title: String,
    val body: String,
    val status: PostStatus
)

// Combined DTO for request/response
@JacksonXmlRootElement(localName = "user")
data class UserDto(
    @JsonProperty("id")
    val id: UUID? = null,

    @field:NotEmpty(message = "email is required")
    @field:Email(message = "invalid email format")
    val email: String,

    @field:NotEmpty(message = "password is required")
    @field:Size(min = 8, message = "password must be at least 8 chars")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) // Hide on serialization
    val password: String? = null,

    @field:PhoneNumber
    val phone: String? = null,

    val role: UserRole? = UserRole.USER,

    @JsonProperty("is_active")
    val active: Boolean? = null,

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    val createdAt: Timestamp? = null
)

// --- DTO to Domain Mapping (Extension Functions) ---

fun UserDto.toNewUser(): User = User(
    id = UUID.randomUUID(),
    email = this.email,
    passHash = "hashed_${this.password}", // Hashing should be done in a service
    role = this.role ?: UserRole.USER,
    active = true,
    created = Timestamp.from(Instant.now())
)

fun User.toDto(): UserDto = UserDto(
    id = this.id,
    email = this.email,
    role = this.role,
    active = this.active,
    createdAt = this.created
)

// --- CUSTOM VALIDATION ---

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [PhoneNumberValidator::class])
annotation class PhoneNumber(
    val message: String = "Invalid phone number",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

class PhoneNumberValidator : ConstraintValidator<PhoneNumber, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext?) =
        value == null || value.matches(Regex("""^\(\d{3}\) \d{3}-\d{4}$""")) // e.g., (555) 123-4567
}

// --- ERROR HANDLING ---

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailures(ex: MethodArgumentNotValidException): ResponseEntity<Map<String, Any>> {
        val errors = ex.bindingResult.fieldErrors.map { it.field to (it.defaultMessage ?: "Invalid") }.toMap()
        val responseBody = mapOf(
            "message" to "Input validation failed",
            "details" to errors
        )
        return ResponseEntity(responseBody, HttpStatus.BAD_REQUEST)
    }
}

// --- API CONTROLLER ---

@RestController
@RequestMapping("/users")
class UserApi {
    private val userStore = mutableMapOf<UUID, User>()

    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun register(@jakarta.validation.Valid @RequestBody userDto: UserDto): ResponseEntity<UserDto> {
        val user = userDto.toNewUser()
        userStore[user.id] = user
        return ResponseEntity.status(HttpStatus.CREATED).body(user.toDto())
    }

    @GetMapping("/{id}", produces = [MediaType.APPLICATION_XML_VALUE])
    fun findById(@PathVariable id: UUID): ResponseEntity<UserDto> =
        userStore[id]?.let { ResponseEntity.ok(it.toDto()) } ?: ResponseEntity.notFound().build()
}

// --- APPLICATION ENTRY POINT ---

@SpringBootApplication
class ConciseValidationApp

fun main(args: Array<String>) {
    runApplication<ConciseValidationApp>(*args)
}