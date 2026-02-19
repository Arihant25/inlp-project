package com.example.middleware.variation1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// --- DOMAIN SCHEMA ---
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

class User {
    UUID id;
    String email;
    String password_hash;
    UserRole role;
    Boolean is_active;
    Timestamp created_at;
    public User(UUID id, String email, UserRole role) {
        this.id = id;
        this.email = email;
        this.role = role;
        this.is_active = true;
        this.created_at = Timestamp.from(Instant.now());
    }
    // Getters
    public UUID getId() { return id; }
    public String getEmail() { return email; }
}

class Post {
    UUID id;
    UUID user_id;
    String title;
    String content;
    PostStatus status;
    public Post(UUID id, UUID user_id, String title, String content) {
        this.id = id;
        this.user_id = user_id;
        this.title = title;
        this.content = content;
        this.status = PostStatus.DRAFT;
    }
    // Getters
    public UUID getId() { return id; }
    public String getTitle() { return title; }
}

// --- CONTROLLER FOR DEMONSTRATION ---
@RestController
@RequestMapping("/api/v1")
class ApiController {
    private static final User MOCK_USER = new User(UUID.randomUUID(), "test@example.com", UserRole.USER);
    private static final Post MOCK_POST = new Post(UUID.randomUUID(), MOCK_USER.getId(), "My First Post", "This is the content.");

    @GetMapping("/users/{id}")
    public ResponseEntity<User> getUserById(@PathVariable UUID id) {
        if ("00000000-0000-0000-0000-000000000000".equals(id.toString())) {
            throw new IllegalArgumentException("Invalid user ID format.");
        }
        return ResponseEntity.ok(MOCK_USER);
    }

    @GetMapping("/posts")
    public ResponseEntity<Post> getPost() {
        return ResponseEntity.ok(MOCK_POST);
    }
}

// --- MIDDLEWARE/INTERCEPTOR IMPLEMENTATIONS ---

// 1. Request Logging
@Component
class RequestLoggingInterceptor implements HandlerInterceptor {
    private static final Logger logger = LoggerFactory.getLogger(RequestLoggingInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        long startTime = System.currentTimeMillis();
        request.setAttribute("startTime", startTime);
        logger.info("Request Start: {} {} from {}", request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        long startTime = (Long) request.getAttribute("startTime");
        long endTime = System.currentTimeMillis();
        long executeTime = endTime - startTime;
        logger.info("Request End: {} {} -> Status: {} [{}ms]", request.getMethod(), request.getRequestURI(), response.getStatus(), executeTime);
        if (ex != null) {
            logger.error("Request resulted in exception: ", ex);
        }
    }
}

// 2. Rate Limiting
@Component
class RateLimitingInterceptor implements HandlerInterceptor {
    private static final int MAX_REQUESTS_PER_MINUTE = 10;
    private final Map<String, RequestCounter> clientRequests = new ConcurrentHashMap<>();

    private static class RequestCounter {
        long lastRequestTime;
        int count;
        RequestCounter(long lastRequestTime, int count) {
            this.lastRequestTime = lastRequestTime;
            this.count = count;
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientIp = request.getRemoteAddr();
        long currentTime = System.currentTimeMillis();

        RequestCounter counter = clientRequests.compute(clientIp, (key, val) -> {
            if (val == null || (currentTime - val.lastRequestTime) > 60000) {
                return new RequestCounter(currentTime, 1);
            }
            val.count++;
            val.lastRequestTime = currentTime;
            return val;
        });

        if (counter.count > MAX_REQUESTS_PER_MINUTE) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("Rate limit exceeded. Please try again later.");
            return false;
        }
        return true;
    }
}

// 3. Request/Response Transformation (adding a header)
@Component
class ResponseTransformationInterceptor implements HandlerInterceptor {
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        response.setHeader("X-API-Version", "1.0.0");
        response.setHeader("X-Request-ID", UUID.randomUUID().toString());
    }
}

// --- CONFIGURATION TO REGISTER MIDDLEWARE ---
@Configuration
class WebMvcConfiguration implements WebMvcConfigurer {
    private final RequestLoggingInterceptor requestLoggingInterceptor;
    private final RateLimitingInterceptor rateLimitingInterceptor;
    private final ResponseTransformationInterceptor responseTransformationInterceptor;

    public WebMvcConfiguration(RequestLoggingInterceptor requestLoggingInterceptor, RateLimitingInterceptor rateLimitingInterceptor, ResponseTransformationInterceptor responseTransformationInterceptor) {
        this.requestLoggingInterceptor = requestLoggingInterceptor;
        this.rateLimitingInterceptor = rateLimitingInterceptor;
        this.responseTransformationInterceptor = responseTransformationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Order is important. Rate limiting should come early.
        registry.addInterceptor(rateLimitingInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(requestLoggingInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(responseTransformationInterceptor).addPathPatterns("/api/**");
    }

    // 4. CORS Handling
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("https://example.com", "https://another-domain.com")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}

// 5. Error Handling
@ControllerAdvice
class GlobalExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAllExceptions(Exception ex) {
        logger.error("An unexpected error occurred: {}", ex.getMessage(), ex);
        Map<String, Object> body = Map.of(
            "timestamp", Instant.now(),
            "status", HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "error", "Internal Server Error",
            "message", "An unexpected error occurred. Please contact support."
        );
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleIllegalArgumentException(IllegalArgumentException ex) {
        Map<String, Object> body = Map.of(
            "timestamp", Instant.now(),
            "status", HttpStatus.BAD_REQUEST.value(),
            "error", "Bad Request",
            "message", ex.getMessage()
        );
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }
}

// --- MAIN APPLICATION ---
@SpringBootApplication
public class Variation1Application {
    public static void main(String[] args) {
        SpringApplication.run(Variation1Application.class, args);
    }
}