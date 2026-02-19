package com.example.variation3

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.json.JsonMapper
import io.javalin.validation.Validator
import io.javalin.validation.ValidationException
import java.lang.reflect.Type
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

// --- DEPENDENCIES (for build.gradle.kts) ---
// implementation("io.javalin:javalin:6.1.3")
// implementation("org.slf4j:slf4j-simple:2.0.13")
// implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.17.1")
// implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.1")

// --- DOMAIN MODELS ---
enum class Role { ADMIN, USER }
enum class Status { DRAFT, PUBLISHED }

data class User(val id: UUID, val email: String, val passwordHash: String, val role: Role, val isActive: Boolean, val createdAt: Timestamp)
data class Post(val id: UUID, val userId: UUID, val title: String, val content: String, val status: Status)

// --- DTOs ---
data class UserCreationDto(val email: String, val password: String, val phone: String)
data class PostCreationDto(val userId: String, val title: String, val content: String)

// --- REPOSITORY LAYER (Mocked) ---
interface UserRepository {
    fun findByEmail(email: String): User?
    fun save(user: User): User
}

class InMemoryUserRepository : UserRepository {
    private val users = mutableMapOf<UUID, User>()
    override fun findByEmail(email: String): User? = users.values.find { it.email == email }
    override fun save(user: User): User {
        users[user.id] = user
        return user
    }
}

// --- SERVICE LAYER ---
class UserService(private val userRepository: UserRepository) {
    fun create(dto: UserCreationDto): User {
        // Validation logic is now in the service layer
        validateUserDto(dto)

        val newUser = User(
            id = UUID.randomUUID(),
            email = dto.email,
            passwordHash = hashPassword(dto.password),
            role = Role.USER,
            isActive = true,
            createdAt = Timestamp.from(Instant.now())
        )
        return userRepository.save(newUser)
    }

    private fun validateUserDto(dto: UserCreationDto) {
        Validator.create(UserCreationDto::class.java, dto, "user")
            .check({ it.email.isNotBlank() }, "Email must not be blank")
            .check({ userRepository.findByEmail(it.email) == null }, "Email is already taken")
            .check({ it.password.length > 7 }, "Password must be at least 8 characters")
            .check({ isValidPhoneNumber(it.phone) }, "Phone number must be a valid international number")
            .get() // Throws ValidationException on failure
    }

    // Custom validator function used by the service
    private fun isValidPhoneNumber(phone: String): Boolean {
        return phone.startsWith("+") && phone.length > 8 && phone.all { it.isDigit() || it == '+' }
    }

    private fun hashPassword(password: String): String = "bcrypt_hash_of_${password}"
}

// --- CONTROLLER LAYER ---
class UserController(private val userService: UserService) {
    fun create(ctx: Context) {
        val dto = ctx.bodyAs<UserCreationDto>()
        val newUser = userService.create(dto)
        ctx.status(HttpStatus.CREATED).xml(newUser)
    }
}

// --- XML MAPPER CONFIGURATION ---
class JavalinXmlMapper : JsonMapper {
    private val xmlMapper = XmlMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
    override fun <T : Any> fromJsonString(json: String, targetType: Type): T = xmlMapper.readValue(json, xmlMapper.constructType(targetType))
    override fun toJsonString(obj: Any, type: Type): String = xmlMapper.writeValueAsString(obj)
}

// --- APPLICATION SETUP ---
fun main() {
    // Dependency Injection
    val userRepository = InMemoryUserRepository()
    val userService = UserService(userRepository)
    val userController = UserController(userService)

    val app = Javalin.create { config ->
        // Replace the default JSON mapper with our XML mapper
        config.jsonMapper(JavalinXmlMapper())
        config.http.defaultContentType = "application/xml"
    }.start(7070)

    // Global error handler for validation
    app.exception(ValidationException::class.java) { e, ctx ->
        val errorResponse = mapOf("error" to mapOf("messages" to e.errors.mapValues { it.value.first() }))
        ctx.status(HttpStatus.UNPROCESSABLE_ENTITY).xml(errorResponse)
    }

    // Routes
    app.post("/users", userController::create)

    println("XML-based server started at http://localhost:7070")
    println("Try: curl -X POST -H \"Content-Type: application/xml\" -d '<UserCreationDto><email>test@example.com</email><password>longpassword</password><phone>+15551234567</phone></UserCreationDto>' http://localhost:7070/users")
}