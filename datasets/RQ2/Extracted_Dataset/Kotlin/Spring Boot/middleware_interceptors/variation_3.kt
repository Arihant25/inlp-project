package com.example.variationthree

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.*
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.lang.annotation.Inherited
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
class VariationThreeApplication

fun main(args: Array<String>) {
    runApplication<VariationThreeApplication>(*args)
}

// --- MOCK CONTROLLER ---
@RestController
@RequestMapping("/api/posts")
// Fine-grained CORS control at the controller level
@CrossOrigin(origins = ["https://trusted-client.com"], maxAge = 3600)
class PostController {

    @GetMapping("/{id}")
    fun getPost(@PathVariable id: UUID): Post {
        return Post(id, UUID.randomUUID(), "AOP Post", "Content", PostStatus.PUBLISHED)
    }

    @PostMapping
    @RateLimited(permits = 5, timeUnit = TimeUnit.MINUTES)
    fun createPost(@RequestBody post: Map<String, String>): ResponseEntity<Post> {
        if (post["title"].isNullOrBlank()) {
            throw InvalidPostException("Post title cannot be empty.")
        }
        val newPost = Post(UUID.randomUUID(), UUID.randomUUID(), post["title"]!!, post["content"]!!, PostStatus.DRAFT)
        return ResponseEntity.status(HttpStatus.CREATED).body(newPost)
    }
}

// --- MIDDLEWARE (Annotation-Driven & AOP Style) ---

// 1. Custom Annotation for Rate Limiting
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Inherited
annotation class RateLimited(
    val permits: Int = 10,
    val timeUnit: TimeUnit = TimeUnit.SECONDS,
    val key: String = "ip" // 'ip' or 'user'
)

// 2. AOP Aspect to enforce @RateLimited
@Aspect
@Component
class RateLimitingAspect {
    private val buckets = ConcurrentHashMap<String, SimpleRateLimiter>()

    @Around("@annotation(rateLimited)")
    fun rateLimit(pjp: ProceedingJoinPoint, rateLimited: RateLimited): Any? {
        val request = (pjp.args.firstOrNull { it is HttpServletRequest } ?: throw IllegalStateException("HttpServletRequest not found in method args")) as HttpServletRequest
        val key = if (rateLimited.key == "user") request.userPrincipal?.name ?: request.remoteAddr else request.remoteAddr

        val limiter = buckets.computeIfAbsent(key) {
            SimpleRateLimiter(rateLimited.permits, rateLimited.timeUnit.toMillis(1))
        }

        if (limiter.tryAcquire()) {
            return pjp.proceed()
        }

        // Create a response entity directly for AOP context
        val response = HttpServletResponse::class.java.cast(pjp.args.firstOrNull { it is HttpServletResponse })
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("Rate limit exceeded for key: $key")
    }

    // Simple rate limiter implementation for self-containment
    class SimpleRateLimiter(private val capacity: Int, private val windowMillis: Long) {
        private val requests = ConcurrentHashMap<Long, Int>()
        fun tryAcquire(): Boolean {
            val now = System.currentTimeMillis()
            requests.entries.removeIf { it.key < now - windowMillis }
            val currentCount = requests.values.sum()
            if (currentCount < capacity) {
                requests[now] = requests.getOrDefault(now, 0) + 1
                return true
            }
            return false
        }
    }
}

// 3. Request Logging via a standard Servlet Filter
@Component
class RequestLoggingFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val startTime = System.nanoTime()
        log.info("Incoming request: ${request.method} ${request.requestURI}")
        try {
            filterChain.doFilter(request, response)
        } finally {
            val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
            log.info("Request finished: ${request.method} ${request.requestURI} | Status: ${response.status} | Duration: ${duration}ms")
        }
    }
}

// 4. Request/Response Transformation (e.g., adding a header) can still be an interceptor
// For this variation, we assume it's not needed as AOP/Filters handle the other concerns.

// --- CONFIGURATION ---
@Configuration
class WebConfig : WebMvcConfigurer {
    // Global CORS fallback
    // Note: @CrossOrigin on controllers takes precedence
    override fun addCorsMappings(registry: org.springframework.web.servlet.config.annotation.CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins("*")
            .allowedMethods("GET")
    }
}

// --- ERROR HANDLING ---
class InvalidPostException(message: String) : RuntimeException(message)

@ControllerAdvice
class RestResponseEntityExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(InvalidPostException::class)
    fun handleInvalidPost(ex: InvalidPostException, request: HttpServletRequest): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message!!)
        pd.title = "Invalid Post Data"
        pd.setProperty("path", request.requestURI)
        return pd
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception, request: HttpServletRequest): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error has occurred.")
        pd.title = "Server Error"
        pd.setProperty("path", request.requestURI)
        logger.error("Unhandled exception: ", ex)
        return pd
    }
}