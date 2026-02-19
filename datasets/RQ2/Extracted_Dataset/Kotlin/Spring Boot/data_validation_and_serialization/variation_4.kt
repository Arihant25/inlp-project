package com.example.grouped.validation

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import jakarta.validation.constraints.*
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
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
    var id: UUID,
    var email: String,
    var passwordHash: String,
    var role: Role,
    var isActive: Boolean,
    var createdAt: Timestamp
)

data class Post(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: Status
)

// --- VALIDATION GROUPS ---
interface OnCreate
interface OnUpdate

// --- CUSTOM VALIDATOR ---
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [RoleSubsetValidator::class])
annotation class RoleSubset(
    val anyOf: Array<Role>,
    val message: String = "Role must be one of {anyOf}",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

class RoleSubsetValidator : ConstraintValidator<RoleSubset, Role> {
    private lateinit var subset: Array<Role>
    override fun initialize(constraint: RoleSubset) {
        this.subset = constraint.anyOf
    }
    override fun isValid(value: Role?, context: ConstraintValidatorContext?) =
        value != null && subset.contains(value)
}

// --- DTO WITH GROUPED VALIDATIONS ---
@JacksonXmlRootElement(localName = "UserPayload")
data class UserPayload(
    @field:NotNull(groups = [OnUpdate::class], message = "ID is required for updates")
    @field:Null(groups = [OnCreate::class], message = "ID must be null for creation")
    @JacksonXmlProperty(isAttribute = true)
    val id: UUID?,

    @field:NotBlank(groups = [OnCreate::class], message = "Email is required on creation")
    @field:Email(groups = [OnCreate::class, OnUpdate::class], message = "Invalid email format")
    val email: String?,

    @field:NotBlank(groups = [OnCreate::class], message = "Password is required on creation")
    @field:Size(min = 12, groups = [OnCreate::class, OnUpdate::class], message = "Password must be at least 12 characters")
    val password: String?,

    @field:NotNull(groups = [OnCreate::class, OnUpdate::class])
    @field:RoleSubset(anyOf = [Role.ADMIN, Role.USER])
    val role: Role?,

    @field:AssertTrue(groups = [OnUpdate::class], message = "User must be active to be updated")
    val isActive: Boolean?
)

// --- ERROR HANDLING FOR XML/JSON ---
@ControllerAdvice
class RestResponseEntityExceptionHandler : ResponseEntityExceptionHandler() {
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatus,
        request: WebRequest
    ): ResponseEntity<Any>? {
        val errors = ex.bindingResult.fieldErrors.map {
            mapOf("field" to it.field, "message" to it.defaultMessage)
        }
        val body = mapOf(
            "timestamp" to Instant.now().toString(),
            "status" to status.value(),
            "error" to "Validation Error",
            "validation_details" to errors
        )
        return ResponseEntity(body, headers, status)
    }
}

// --- CONTROLLER WITH @Validated ---
@RestController
@RequestMapping("/management/users")
class UserManagementController {
    private val userStore = mutableMapOf<UUID, User>()

    @PostMapping(
        consumes = [MediaType.APPLICATION_XML_VALUE],
        produces = [MediaType.APPLICATION_XML_VALUE]
    )
    fun createUser(@Validated(OnCreate::class) @RequestBody payload: UserPayload): ResponseEntity<User> {
        val newUser = User(
            id = UUID.randomUUID(),
            email = payload.email!!,
            passwordHash = "hashed::${payload.password!!}",
            role = payload.role!!,
            isActive = true,
            createdAt = Timestamp.from(Instant.now())
        )
        userStore[newUser.id] = newUser
        return ResponseEntity.status(HttpStatus.CREATED).body(newUser)
    }

    @PutMapping(
        "/{id}",
        consumes = [MediaType.APPLICATION_XML_VALUE],
        produces = [MediaType.APPLICATION_XML_VALUE]
    )
    fun updateUser(
        @PathVariable id: UUID,
        @Validated(OnUpdate::class) @RequestBody payload: UserPayload
    ): ResponseEntity<User> {
        val existingUser = userStore[id] ?: return ResponseEntity.notFound().build()

        // Apply updates
        payload.email?.let { existingUser.email = it }
        payload.password?.let { existingUser.passwordHash = "hashed::${it}" }
        payload.role?.let { existingUser.role = it }
        payload.isActive?.let { existingUser.isActive = it }

        userStore[id] = existingUser
        return ResponseEntity.ok(existingUser)
    }
}

// --- APPLICATION ---
@SpringBootApplication
class GroupedValidationApplication

fun main(args: Array<String>) {
    runApplication<GroupedValidationApplication>(*args)
}