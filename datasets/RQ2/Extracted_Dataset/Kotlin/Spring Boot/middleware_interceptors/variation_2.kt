package com.example.variationtwo

import com.google.common.util.concurrent.RateLimiter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.*
import org.springframework.web.filter.CorsFilter
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper
import java.sql.Timestamp
import java.time.Instant
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
class VariationTwoApplication

fun main(args: Array<String>) {
    runApplication<VariationTwoApplication>(*args)
}

// --- MOCK CONTROLLER ---
@RestController
@RequestMapping("/posts")
class PostController {
    private val mockPost = Post(
        id = UUID.randomUUID(),
        user_id = UUID.randomUUID(),
        title = "My First Post",
        content = "This is the content.",
        status = PostStatus.PUBLISHED
    )

    @GetMapping("/{id}")
    fun getPost(@PathVariable id: UUID): Post {
        if (id.toString().startsWith("0")) {
            throw IllegalArgumentException("Invalid Post ID format.")
        }
        return mockPost
    }
}

// --- CONFIGURATION (Functional, Bean-based Style) ---

@Configuration
class MiddlewareConfig : WebMvcConfigurer {

    private val log = LoggerFactory.getLogger(MiddlewareConfig::class.java)

    // 1. Request/Response Transformation and Logging Interceptor
    @Bean
    fun loggingAndTransformingInterceptor(): HandlerInterceptor {
        return object : HandlerInterceptor {
            override fun preHandle(req: HttpServletRequest, res: HttpServletResponse, handler: Any): Boolean {
                // Transformation: Add a tracking header to the response
                res.addHeader("X-Request-ID", UUID.randomUUID().toString())
                return true
            }

            override fun afterCompletion(req: HttpServletRequest, res: HttpServletResponse, handler: Any, ex: Exception?) {
                log.info("Handled ${req.method} ${req.requestURI} with status ${res.status}")
            }
        }
    }

    // 2. Rate Limiting Interceptor
    @Bean
    fun rateLimitingInterceptor(): HandlerInterceptor {
        // Using a Guava-like RateLimiter concept (mocked for self-containment)
        val clientRateLimiters = ConcurrentHashMap<String, RateLimiter>()

        return object : HandlerInterceptor {
            override fun preHandle(req: HttpServletRequest, res: HttpServletResponse, handler: Any): Boolean {
                val ip = req.remoteAddr
                // Get or create a rate limiter for this IP, allowing 5 permits per second.
                val limiter = clientRateLimiters.computeIfAbsent(ip) { RateLimiter.create(5.0) }

                if (limiter.tryAcquire()) {
                    return true
                }

                res.status = HttpStatus.TOO_MANY_REQUESTS.value()
                res.addHeader("Content-Type", "application/json")
                res.writer.write("{\"error\": \"Rate limit exceeded\"}")
                return false
            }
        }
    }

    // 3. CORS Handling via a Filter Bean
    @Bean
    fun corsFilter(): CorsFilter {
        val source = org.springframework.web.cors.UrlBasedCorsConfigurationSource()
        val config = org.springframework.web.cors.CorsConfiguration()
        config.allowCredentials = true
        config.addAllowedOrigin("http://localhost:3000")
        config.addAllowedHeader("*")
        config.addAllowedMethod("*")
        source.registerCorsConfiguration("/**", config)
        return CorsFilter(source)
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(rateLimitingInterceptor())
        registry.addInterceptor(loggingAndTransformingInterceptor())
    }
}

// --- ERROR HANDLING ---

@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiErrorHandler {

    data class ApiError(val message: String, val type: String)

    @ExceptionHandler(Exception::class)
    fun handleAllExceptions(ex: Exception): ResponseEntity<ApiError> {
        val (status, error) = when (ex) {
            is IllegalArgumentException -> HttpStatus.BAD_REQUEST to ApiError(ex.message ?: "Invalid argument", "BAD_REQUEST")
            is IllegalStateException -> HttpStatus.CONFLICT to ApiError(ex.message ?: "Invalid state", "CONFLICT")
            else -> HttpStatus.INTERNAL_SERVER_ERROR to ApiError("An internal error occurred", "INTERNAL_SERVER_ERROR")
        }
        return ResponseEntity(error, status)
    }
}