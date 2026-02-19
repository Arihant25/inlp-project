package com.example.middleware.variation4;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.cors.CorsConfiguration;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

// --- DOMAIN SCHEMA (Modern Java Records) ---
enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

record User(UUID id, String email, String password_hash, Role role, Boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, Status status) {}

// --- DTOs ---
record ApiError(String path, String message, int statusCode, Instant timestamp) {}
record ValidationError(String path, List<String> errors, int statusCode, Instant timestamp) {}

// --- CONTROLLER ---
@RestController
@RequestMapping("/api")
class MainController {
    private static final User MOCK_USER = new User(UUID.randomUUID(), "user@example.com", "hash", Role.USER, true, Timestamp.from(Instant.now()));

    @GetMapping("/users/{id}")
    public ResponseEntity<User> findUser(@PathVariable UUID id) {
        if (id.getMostSignificantBits() == 0) {
            throw new UserNotFoundException("User with ID " + id + " could not be found.");
        }
        return ResponseEntity.ok(MOCK_USER);
    }
}

class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String message) { super(message); }
}

// --- UNIFIED INTERCEPTOR ---
@Component
class UnifiedProcessingInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(UnifiedProcessingInterceptor.class);
    private static final String REQUEST_ID_ATTR = "request_id";
    private static final String START_TIME_ATTR = "start_time";
    private static final int RATE_LIMIT_PER_MINUTE = 30;

    private final Cache<String, AtomicInteger> rateLimitCache;

    public UnifiedProcessingInterceptor(Cache<String, AtomicInteger> rateLimitCache) {
        this.rateLimitCache = rateLimitCache;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
        // Part of Request/Response Transformation: Add Request ID
        var requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTR, requestId);
        request.setAttribute(START_TIME_ATTR, System.nanoTime());

        // Rate Limiting
        if (!checkRateLimit(request)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("{\"error\":\"Rate limit exceeded\"}");
            return false;
        }

        // Request Logging (start)
        log.info("[START] rid: {} | {} {} | from: {}", requestId, request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler, Exception ex) {
        // Part of Request/Response Transformation: Add Request ID to response header
        var requestId = (String) request.getAttribute(REQUEST_ID_ATTR);
        if (requestId != null) {
            response.setHeader("X-Request-ID", requestId);
        }

        // Request Logging (end)
        var startTime = (Long) request.getAttribute(START_TIME_ATTR);
        var durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        log.info("[END] rid: {} | {} {} | status: {} | duration: {}ms", requestId, request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
        if (ex != null) {
            log.error("rid: {} | Exception occurred: {}", requestId, ex.getMessage());
        }
    }

    private boolean checkRateLimit(HttpServletRequest request) {
        var key = request.getRemoteAddr();
        var counter = rateLimitCache.get(key, k -> new AtomicInteger(0));
        return counter.incrementAndGet() <= RATE_LIMIT_PER_MINUTE;
    }
}

// --- ALL-IN-ONE CONFIGURATION ---
@Configuration
class MasterWebConfiguration implements WebMvcConfigurer {

    private final UnifiedProcessingInterceptor unifiedProcessingInterceptor;

    public MasterWebConfiguration(UnifiedProcessingInterceptor unifiedProcessingInterceptor) {
        this.unifiedProcessingInterceptor = unifiedProcessingInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(unifiedProcessingInterceptor).addPathPatterns("/api/**");
    }

    // Rate Limiting Cache configuration
    // NOTE: Add `com.github.ben-manes.caffeine:caffeine` to your dependencies.
    @Bean
    public Cache<String, AtomicInteger> rateLimitCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .build();
    }

    // CORS Handling via Filter Bean
    @Bean
    public CorsFilter corsFilter() {
        var source = new UrlBasedCorsConfigurationSource();
        var config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOrigins(Collections.singletonList("http://localhost:8080"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}

// --- ERROR HANDLING (Extending ResponseEntityExceptionHandler) ---
@ControllerAdvice
class CustomApiErrorHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    protected ResponseEntity<Object> handleUserNotFound(UserNotFoundException ex, WebRequest request) {
        var apiError = new ApiError(
            request.getDescription(false),
            ex.getMessage(),
            HttpStatus.NOT_FOUND.value(),
            Instant.now()
        );
        return new ResponseEntity<>(apiError, HttpStatus.NOT_FOUND);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.toList());

        var validationError = new ValidationError(
            request.getDescription(false),
            errors,
            HttpStatus.BAD_REQUEST.value(),
            Instant.now()
        );
        return new ResponseEntity<>(validationError, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAllOtherExceptions(Exception ex, WebRequest request) {
        var apiError = new ApiError(
            request.getDescription(false),
            "An unexpected server error occurred.",
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            Instant.now()
        );
        logger.error("Unhandled exception: ", ex);
        return new ResponseEntity<>(apiError, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}

// --- MAIN APPLICATION ---
@SpringBootApplication
public class Variation4Application {
    public static void main(String[] args) {
        SpringApplication.run(Variation4Application.class, args);
    }
}