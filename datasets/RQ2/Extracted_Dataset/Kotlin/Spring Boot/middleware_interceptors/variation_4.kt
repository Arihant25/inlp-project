package com.example.variationfour

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap

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
class VariationFourApplication

fun main(args: Array<String>) {
    runApplication<VariationFourApplication>(*args)
}

// --- MOCK CONTROLLER ---
@RestController
@RequestMapping("/api/v2")
class ApiController {
    @GetMapping("/users/{id}")
    fun findUser(@PathVariable id: UUID): User {
        if (id == UUID.fromString("00000000-0000-0000-0000-000000000000")) {
            throw UserNotFoundException(id)
        }
        return User(id, "test@example.com", "hash", UserRole.USER, true, Timestamp.from(Instant.now()))
    }
}

// --- MIDDLEWARE (Modern, Modular & Composable Style) ---

// --- Module: Logging ---
@Component
class LoggingInterceptor : HandlerInterceptor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        request.setAttribute("requestReceivedAt", System.currentTimeMillis())
        log.trace("Request received for ${request.method} ${request.requestURI}")
        return true
    }

    override fun afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception?) {
        val startTime = request.getAttribute("requestReceivedAt") as Long
        val duration = System.currentTimeMillis() - startTime
        log.info("AUDIT: method=${request.method} uri=${request.requestURI} status=${response.status} duration_ms=$duration")
    }
}

// --- Module: Security (Rate Limiting) ---
interface RateLimiterService {
    fun isAllowed(key: String): Boolean
}

@Service
class InMemoryRateLimiterService : RateLimiterService {
    private val maxRequestsPerMinute = 20
    private val clientTries = ConcurrentHashMap<String, MutableList<Long>>()

    override fun isAllowed(key: String): Boolean {
        val now = System.currentTimeMillis()
        val requestTimestamps = clientTries.computeIfAbsent(key) { Collections.synchronizedList(mutableListOf()) }

        synchronized(requestTimestamps) {
            requestTimestamps.removeIf { it < now - 60_000 }
            if (requestTimestamps.size < maxRequestsPerMinute) {
                requestTimestamps.add(now)
                return true
            }
        }
        return false
    }
}

@Component
class RateLimitingInterceptor(private val rateLimiterService: RateLimiterService) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val clientKey = request.remoteAddr
        if (!rateLimiterService.isAllowed(clientKey)) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write("{\"error\":\"API rate limit exceeded for IP: $clientKey\"}")
            return false
        }
        return true
    }
}

// --- Module: Transformation (Standard API Response Wrapper) ---
data class ApiResponse<T>(
    val data: T?,
    val success: Boolean,
    val timestamp: OffsetDateTime = OffsetDateTime.now()
)

@ControllerAdvice
class ApiResponseWrapper : ResponseBodyAdvice<Any> {
    override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>): Boolean {
        // Wrap everything that is not an error response or already an ApiResponse
        return !returnType.parameterType.isAssignableFrom(ResponseEntity::class.java) &&
               !returnType.parameterType.isAssignableFrom(ApiError::class.java)
    }

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse
    ): Any {
        return ApiResponse(data = body, success = true)
    }
}

// --- Module: Error Handling ---
data class ApiError(
    val status: Int,
    val error: String,
    val message: String,
    val path: String,
    val timestamp: OffsetDateTime = OffsetDateTime.now()
)

class UserNotFoundException(id: UUID) : RuntimeException("User with ID '$id' could not be found.")

@ControllerAdvice
class GlobalApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(UserNotFoundException::class)
    fun handleNotFound(ex: UserNotFoundException, request: HttpServletRequest): ResponseEntity<ApiError> {
        val error = ApiError(
            status = HttpStatus.NOT_FOUND.value(),
            error = "Not Found",
            message = ex.message ?: "Resource not found",
            path = request.requestURI
        )
        return ResponseEntity(error, HttpStatus.NOT_FOUND)
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericError(ex: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        log.error("An unhandled exception occurred", ex)
        val error = ApiError(
            status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
            error = "Internal Server Error",
            message = "An unexpected error occurred. Please contact support.",
            path = request.requestURI
        )
        return ResponseEntity(error, HttpStatus.INTERNAL_SERVER_ERROR)
    }
}

// --- CONFIGURATION ---
@Configuration
class WebConfiguration(
    private val loggingInterceptor: LoggingInterceptor,
    private val rateLimitingInterceptor: RateLimitingInterceptor
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(loggingInterceptor).order(0)
        registry.addInterceptor(rateLimitingInterceptor).addPathPatterns("/api/**").order(1)
    }

    // CORS is assumed to be configured via application.properties for this modern approach
    // e.g., management.endpoints.web.cors.allowed-origins=...
}