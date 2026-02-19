package com.example.variationone

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// --- DOMAIN SCHEMA ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val password_hash: String,
    val role: UserRole,
    val is_active: Boolean,
    val created_at: Timestamp
)

data class Post(
    val id: UUID,
    val user_id: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

// --- APPLICATION ---
@SpringBootApplication
class VariationOneApplication

fun main(args: Array<String>) {
    runApplication<VariationOneApplication>(*args)
}

// --- MOCK CONTROLLER ---
@RestController
@RequestMapping("/api/v1/users")
class UserController {
    private val mockUser = User(
        id = UUID.randomUUID(),
        email = "admin@example.com",
        password_hash = "hashed_password",
        role = UserRole.ADMIN,
        is_active = true,
        created_at = Timestamp.from(Instant.now())
    )

    @GetMapping("/{id}")
    fun getUserById(@PathVariable id: UUID): User {
        if (id == mockUser.id) {
            return mockUser
        }
        throw NoSuchElementException("User with ID $id not found.")
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createUser(@RequestBody user: Map<String, String>): User {
        // Simulate user creation
        return mockUser.copy(id = UUID.randomUUID(), email = user["email"] ?: "new.user@example.com")
    }
}

// --- MIDDLEWARE/INTERCEPTORS (Classic OOP Style) ---

/**
 * Interceptor for logging details of incoming requests.
 */
@Component
class RequestLoggingInterceptor : HandlerInterceptor {
    private val logger = LoggerFactory.getLogger(RequestLoggingInterceptor::class.java)

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val startTime = System.currentTimeMillis()
        request.setAttribute("startTime", startTime)
        logger.info(
            "Request START: [${request.method}] ${request.requestURI} from ${request.remoteAddr}"
        )
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        val startTime = request.getAttribute("startTime") as Long
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        logger.info(
            "Request END: [${request.method}] ${request.requestURI} -> Status: ${response.status} | Duration: ${duration}ms"
        )
        if (ex != null) {
            logger.error("Request resulted in an exception: ", ex)
        }
    }
}

/**
 * Interceptor for simple IP-based rate limiting.
 */
@Component
class RateLimitingInterceptor : HandlerInterceptor {
    private val requestsPerMinute = 10
    private val ipRequestCounts = ConcurrentHashMap<String, Pair<Long, AtomicInteger>>()

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val clientIp = request.remoteAddr
        val now = System.currentTimeMillis()

        val (firstRequestTime, count) = ipRequestCounts.compute(clientIp) { _, value ->
            if (value == null || now - value.first > 60 * 1000) {
                // If no record or the window has expired, start a new window
                Pair(now, AtomicInteger(1))
            } else {
                // Increment count in the current window
                value.second.incrementAndGet()
                value
            }
        }!!

        return if (count.get() > requestsPerMinute) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.writer.write("Rate limit exceeded. Please try again later.")
            false
        } else {
            true
        }
    }
}

/**
 * Interceptor for transforming the response, e.g., adding a custom header.
 */
@Component
class ResponseTransformationInterceptor : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        response.addHeader("X-API-Version", "1.0.0")
        return true
    }
}

// --- CONFIGURATION ---

/**
 * Configures web-related beans, including CORS and interceptors.
 */
@Configuration
class WebMvcConfiguration(
    private val requestLoggingInterceptor: RequestLoggingInterceptor,
    private val rateLimitingInterceptor: RateLimitingInterceptor,
    private val responseTransformationInterceptor: ResponseTransformationInterceptor
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        // Order of registration matters.
        registry.addInterceptor(requestLoggingInterceptor).order(1)
        registry.addInterceptor(rateLimitingInterceptor).addPathPatterns("/api/**").order(2)
        registry.addInterceptor(responseTransformationInterceptor).order(3)
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins("https://example.com")
            .allowedMethods("GET", "POST", "PUT", "DELETE")
            .allowedHeaders("*")
            .allowCredentials(true)
    }
}

// --- ERROR HANDLING ---

/**
 * Centralized exception handler for the application.
 */
@ControllerAdvice
class GlobalExceptionHandler {

    data class ErrorResponse(val status: Int, val message: String?, val path: String)

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val errorDetails = ErrorResponse(
            status = HttpStatus.NOT_FOUND.value(),
            message = ex.message,
            path = request.requestURI
        )
        return ResponseEntity(errorDetails, HttpStatus.NOT_FOUND)
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val errorDetails = ErrorResponse(
            status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
            message = "An unexpected error occurred.",
            path = request.requestURI
        )
        return ResponseEntity(errorDetails, HttpStatus.INTERNAL_SERVER_ERROR)
    }
}