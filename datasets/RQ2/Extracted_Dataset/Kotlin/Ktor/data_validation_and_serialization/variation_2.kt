package com.example.variation2

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.*

// --- Gradle Dependencies (build.gradle.kts) ---
// Same as Variation 1

// --- Domain Model ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: Instant
)

data class Post(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

// --- DTOs (Data Transfer Objects) ---
@Serializable
@JacksonXmlRootElement(localName = "UserCreationDto")
data class UserCreationDto(
    val email: String,
    val password: String,
    val phone: String,
    val role: UserRole = UserRole.USER
)

@Serializable
data class PostCreationDto(
    val userId: String,
    val title: String,
    val content: String
)

// --- Validation Service (OOP Approach) ---
sealed class ValidationResult {
    object Success : ValidationResult()
    data class Failure(val errors: Map<String, String>) : ValidationResult()
}

class ValidatorService {
    private val EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@(.+)\$".toRegex()
    private val PHONE_REGEX = "^\\+?[1-9]\\d{1,14}\$".toRegex()

    fun validateNewUser(dto: UserCreationDto): ValidationResult {
        val errors = mutableMapOf<String, String>()
        if (!EMAIL_REGEX.matches(dto.email)) {
            errors["email"] = "Invalid email format."
        }
        if (dto.password.length < 8) {
            errors["password"] = "Password must be at least 8 characters long."
        }
        if (!PHONE_REGEX.matches(dto.phone)) {
            errors["phone"] = "Invalid phone number format. E.g., +12223334444"
        }
        return if (errors.isEmpty()) ValidationResult.Success else ValidationResult.Failure(errors)
    }
}

// --- Business Logic Service (Mock) ---
class UserService(private val userStorage: MutableMap<UUID, User>) {
    fun createUser(dto: UserCreationDto): User {
        val newUser = User(
            id = UUID.randomUUID(),
            email = dto.email,
            passwordHash = "hashed_${dto.password}",
            role = dto.role,
            isActive = true,
            createdAt = Instant.now()
        )
        userStorage[newUser.id] = newUser
        return newUser
    }
}

// --- Controller/Routes Class ---
class UserRoutes(private val userService: UserService, private val validator: ValidatorService) {
    fun Route.register() {
        route("/v2/users") {
            post {
                val dto = call.receive<UserCreationDto>()
                when (val validationResult = validator.validateNewUser(dto)) {
                    is ValidationResult.Success -> {
                        val createdUser = userService.createUser(dto)
                        call.respond(HttpStatusCode.Created, createdUser)
                    }
                    is ValidationResult.Failure -> {
                        call.respond(HttpStatusCode.BadRequest, validationResult.errors)
                    }
                }
            }
        }
    }
}

// --- Main Application Setup ---
fun main() {
    embeddedServer(Netty, port = 8081, host = "0.0.0.0", module = Application::oopModule).start(wait = true)
}

fun Application.oopModule() {
    // Dependency Injection (manual)
    val userStorage = mutableMapOf<UUID, User>()
    val validatorService = ValidatorService()
    val userService = UserService(userStorage)
    val userRoutes = UserRoutes(userService, validatorService)

    install(ContentNegotiation) {
        // Custom JSON converter
        register(ContentType.Application.Json, KotlinxSerializationJsonConverter(Json { prettyPrint = true }))
        // Custom XML converter
        register(ContentType.Application.Xml, JacksonXmlConverter())
    }

    routing {
        userRoutes.register()
    }
}

// --- Custom Jackson XML Converter for Ktor ---
class JacksonXmlConverter : ContentConverter {
    private val xmlMapper = jacksonObjectMapper().findAndRegisterModules()

    override suspend fun convertForReceive(context: ApplicationCall): Any? {
        val request = context.request
        val channel = request.receiveChannel()
        val type = context.request.call.receiveType
        val text = channel.readRemaining().readText(context.request.contentCharset() ?: Charsets.UTF_8)
        return xmlMapper.readValue(text, type.jvmType as Class<*>)
    }

    override suspend fun convertForSend(context: ApplicationCall, contentType: ContentType, value: Any): OutgoingContent {
        val text = xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)
        return TextContent(text, contentType.withCharset(context.suitableCharset()))
    }
}

// --- Custom Kotlinx.Json Converter for Ktor ---
class KotlinxSerializationJsonConverter(private val json: Json = Json) : ContentConverter {
    override suspend fun convertForReceive(context: ApplicationCall): Any? {
        val request = context.request
        val channel = request.receiveChannel()
        val serializer = json.serializersModule.serializer(request.receiveType.typeInfo)
        val text = channel.readRemaining().readText(context.request.contentCharset() ?: Charsets.UTF_8)
        return json.decodeFromString(serializer, text)
    }

    override suspend fun convertForSend(context: ApplicationCall, contentType: ContentType, value: Any): OutgoingContent {
        val text = json.encodeToString(json.serializersModule.serializer(value::class.java), value)
        return TextContent(text, contentType.withCharset(context.suitableCharset()))
    }
}