package com.example.middleware.variation3;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// --- DOMAIN SCHEMA ---
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

record User(UUID id, String email, UserRole role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}

// --- CUSTOM ANNOTATIONS FOR AOP ---
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface LoggableRequest {
    String value() default "";
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface RateLimited {
    int requestsPerMinute() default 20;
}

// --- CONTROLLER USING ANNOTATIONS ---
@RestController
@RequestMapping("/api/v1")
class AnnotatedController {
    private static final User MOCK_USER = new User(UUID.randomUUID(), "test@example.com", UserRole.ADMIN, true, Timestamp.from(Instant.now()));
    private static final Post MOCK_POST = new Post(UUID.randomUUID(), MOCK_USER.id(), "AOP Post", "Content", PostStatus.PUBLISHED);

    @GetMapping("/users/{id}")
    @LoggableRequest("GetUserByID")
    @RateLimited(requestsPerMinute = 10)
    public User getUser(@PathVariable UUID id) {
        if (id.toString().length() < 10) {
            throw new RuntimeException("Invalid ID provided");
        }
        return MOCK_USER;
    }

    @GetMapping("/posts")
    @LoggableRequest("GetPost")
    @RateLimited // Uses default of 20
    public Post getPost() {
        return MOCK_POST;
    }
}

// --- MIDDLEWARE IMPLEMENTATIONS (AOP-based) ---
// NOTE: Add `org.springframework.boot:spring-boot-starter-aop` to your dependencies.
@Aspect
@Component
class LoggingAspect {
    private static final Logger logger = LoggerFactory.getLogger(LoggingAspect.class);

    @Around("@annotation(loggableRequest)")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint, LoggableRequest loggableRequest) throws Throwable {
        long start = System.currentTimeMillis();
        Object proceed = joinPoint.proceed();
        long executionTime = System.currentTimeMillis() - start;
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        logger.info("AOP LOG [{}]: {} {} executed in {}ms", loggableRequest.value(), request.getMethod(), request.getRequestURI(), executionTime);
        return proceed;
    }
}

@Aspect
@Component
class RateLimitingAspect {
    private final Map<String, Map<Long, AtomicInteger>> requestCounts = new ConcurrentHashMap<>();

    @Before("@annotation(rateLimited)")
    public void rateLimit(RateLimited rateLimited) {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String key = request.getRemoteAddr() + ":" + request.getRequestURI();
        long currentMinute = Instant.now().getEpochSecond() / 60;

        requestCounts.putIfAbsent(key, new ConcurrentHashMap<>());
        Map<Long, AtomicInteger> minuteCounts = requestCounts.get(key);
        
        // Clean up old entries
        minuteCounts.keySet().removeIf(minute -> minute < currentMinute);

        AtomicInteger count = minuteCounts.computeIfAbsent(currentMinute, k -> new AtomicInteger(0));

        if (count.incrementAndGet() > rateLimited.requestsPerMinute()) {
            throw new RateLimitExceededException("Rate limit exceeded. Max " + rateLimited.requestsPerMinute() + " requests per minute.");
        }
    }
}

class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) { super(message); }
}

// --- INTERCEPTOR FOR RESPONSE TRANSFORMATION ---
@Component
class HeaderTransformInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Example of request transformation: add a correlation ID if not present
        if (request.getHeader("X-Correlation-ID") == null) {
            request.setAttribute("X-Correlation-ID", UUID.randomUUID().toString());
        }
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // Example of response transformation: add the correlation ID to the response
        Object correlationId = request.getAttribute("X-Correlation-ID");
        if (correlationId != null) {
            response.setHeader("X-Correlation-ID", correlationId.toString());
        }
    }
}

// --- CONFIGURATION ---
@Configuration
@EnableAspectJAutoProxy
class AppConfig implements WebMvcConfigurer {

    private final HeaderTransformInterceptor headerTransformInterceptor;

    public AppConfig(HeaderTransformInterceptor headerTransformInterceptor) {
        this.headerTransformInterceptor = headerTransformInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // AOP handles logging and rate limiting, interceptor handles header transformation
        registry.addInterceptor(headerTransformInterceptor);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*") // More flexible for dev
                .allowedMethods("GET", "POST");
    }
}

// --- ERROR HANDLING ---
@ControllerAdvice
class RestApiErrorHandler {
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleRateLimitExceeded(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "Rate Limit Exceeded", "message", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericError(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal Server Error", "message", "An unexpected error occurred."));
    }
}

// --- MAIN APPLICATION ---
@SpringBootApplication
public class Variation3Application {
    public static void main(String[] args) {
        SpringApplication.run(Variation3Application.class, args);
    }
}