package com.example.middleware.variation2;

import com.google.common.util.concurrent.RateLimiter;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// --- DOMAIN SCHEMA (using records for conciseness) ---
enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

record User(UUID id, String email, String password_hash, Role role, Boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, Status status) {}

// --- DTOs for API responses ---
record ApiResponse<T>(T data, String trackingId) {}
record ErrorResponse(int status, String error, String message, String path, Instant timestamp) {}

// --- CONTROLLER FOR DEMONSTRATION ---
@RestController
@RequestMapping("/api")
class DataController {
    private static final User MOCK_USER = new User(UUID.randomUUID(), "user@example.com", "hash", Role.USER, true, Timestamp.from(Instant.now()));
    private static final Post MOCK_POST = new Post(UUID.randomUUID(), MOCK_USER.id(), "A Post Title", "Content here", Status.PUBLISHED);

    @GetMapping("/users/{id}")
    public User getUser(@PathVariable UUID id) {
        if (id.equals(UUID.fromString("00000000-0000-0000-0000-000000000000"))) {
            throw new ResourceNotFoundException("User with specified ID not found.");
        }
        return MOCK_USER;
    }

    @GetMapping("/posts")
    public Post getPost() {
        return MOCK_POST;
    }
}

class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}

// --- MIDDLEWARE IMPLEMENTATIONS (Filter-based) ---

// 1. Request Logging Filter
// NOTE: Add `org.slf4j:slf4j-api` and an implementation like `ch.qos.logback:logback-classic` to your dependencies.
@Component
@Order(2) // Runs after the rate limiting filter
class ReqLogFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(ReqLogFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        log.info("INBOUND REQ: {} {} | Remote: {}", req.getMethod(), req.getRequestURI(), req.getRemoteAddr());
        chain.doFilter(request, response);
    }
}

// 2. Rate Limiting Filter
// NOTE: Add `com.google.guava:guava` to your dependencies.
@Component
@Order(1) // Runs first
class RateLimitFilter implements Filter {
    // Allows 5 requests per second per IP
    private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String clientIp = req.getRemoteAddr();

        RateLimiter limiter = limiters.computeIfAbsent(clientIp, k -> RateLimiter.create(5.0));

        if (limiter.tryAcquire(1, 100, TimeUnit.MILLISECONDS)) {
            chain.doFilter(request, response);
        } else {
            res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write("{\"error\": \"Too many requests\"}");
        }
    }
}

// 3. Response Transformation Advice
@ControllerAdvice
class ApiResponseWrapper implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // Wrap responses from our specific controller package and that are not already an ApiResponse
        return returnType.getContainingClass().getPackage().getName().contains("com.example.middleware.variation2")
               && !returnType.getParameterType().equals(ApiResponse.class)
               && !returnType.getParameterType().equals(ErrorResponse.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        String trackingId = UUID.randomUUID().toString();
        response.getHeaders().add("X-Tracking-ID", trackingId);
        return new ApiResponse<>(body, trackingId);
    }
}

// --- CONFIGURATION ---
@Configuration
class WebConfig {
    // 4. CORS Handling (Functional Bean approach)
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList("http://localhost:3000", "https://prod.ui"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}

// 5. Error Handling
@ControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
            HttpStatus.NOT_FOUND.value(),
            "Not Found",
            ex.getMessage(),
            request.getRequestURI(),
            Instant.now()
        );
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "Internal Server Error",
            "An unexpected error has occurred.",
            request.getRequestURI(),
            Instant.now()
        );
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}

// --- MAIN APPLICATION ---
@SpringBootApplication
public class Variation2Application {
    public static void main(String[] args) {
        SpringApplication.run(Variation2Application.class, args);
    }
}