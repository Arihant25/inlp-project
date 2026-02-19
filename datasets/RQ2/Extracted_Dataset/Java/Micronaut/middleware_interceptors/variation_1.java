package com.example.variation1;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// --- Domain Models ---

enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

class User {
    public UUID id;
    public String email;
    public String password_hash;
    public Role role;
    public boolean is_active;
    public Timestamp created_at;
}

class Post {
    public UUID id;
    public UUID user_id;
    public String title;
    public String content;
    public Status status;
}

// --- Controller for Demonstration ---

@Controller("/api/v1")
class ApiController {
    private static final User MOCK_USER = new User();
    static {
        MOCK_USER.id = UUID.randomUUID();
        MOCK_USER.email = "test@example.com";
        MOCK_USER.role = Role.ADMIN;
        MOCK_USER.is_active = true;
        MOCK_USER.created_at = Timestamp.from(Instant.now());
    }

    @Get("/user/{id}")
    @Produces("application/json")
    public HttpResponse<User> getUser(UUID id) {
        if ("00000000-0000-0000-0000-000000000000".equals(id.toString())) {
            throw new IllegalArgumentException("Invalid user ID format provided.");
        }
        return HttpResponse.ok(MOCK_USER);
    }
}

// --- Variation 1: Classic OOP - Separate Filter for each concern ---

/**
 * Error Handling Filter: Catches exceptions from downstream and formats a standard error response.
 * Runs first to wrap the entire request chain.
 */
@Singleton
@Order(10)
class ErrorHandlingFilter implements HttpServerFilter {
    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandlingFilter.class);

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        return Mono.from(chain.proceed(request))
            .onErrorResume(throwable -> {
                LOG.error("Exception caught in filter chain: {}", throwable.getMessage());
                HttpStatus status = (throwable instanceof IllegalArgumentException) ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
                Map<String, String> errorBody = Map.of(
                    "status", "error",
                    "message", throwable.getMessage() != null ? throwable.getMessage() : "An internal error occurred"
                );
                return Mono.just(HttpResponse.status(status).body(errorBody));
            });
    }
}

/**
 * CORS Handling Filter: Adds CORS headers to responses, including pre-flight OPTIONS requests.
 */
@Singleton
@Order(20)
class CorsHandlingFilter implements HttpServerFilter {
    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        // Handle pre-flight OPTIONS request
        if (request.getMethod() == HttpMethod.OPTIONS) {
            return Publishers.just(
                HttpResponse.ok()
                    .header("Access-Control-Allow-Origin", "*")
                    .header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                    .header("Access-Control-Allow-Headers", "Content-Type, Authorization")
            );
        }

        // Add headers to actual request responses
        return Mono.from(chain.proceed(request)).doOnNext(response -> {
            response.header("Access-Control-Allow-Origin", "*");
        });
    }
}

/**
 * Request Logging Filter: Logs details of every incoming request.
 */
@Singleton
@Order(30)
class RequestLoggingFilter implements HttpServerFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        long startTime = System.currentTimeMillis();
        LOG.info("Incoming Request: {} {} from {}", request.getMethod(), request.getUri(), request.getRemoteAddress());
        
        return Mono.from(chain.proceed(request)).doOnNext(response -> {
            long duration = System.currentTimeMillis() - startTime;
            LOG.info("Outgoing Response: {} for {} {} ({}ms)", response.getStatus(), request.getMethod(), request.getUri(), duration);
        });
    }
}

/**
 * Rate Limiting Filter: Implements a simple in-memory, per-IP rate limiter.
 */
@Singleton
@Order(40)
class RateLimitingFilter implements HttpServerFilter {
    private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();
    private static final int MAX_REQUESTS_PER_MINUTE = 100;

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String clientIp = request.getRemoteAddress().getAddress().getHostAddress();
        requestCounts.putIfAbsent(clientIp, new AtomicInteger(0));
        
        if (requestCounts.get(clientIp).incrementAndGet() > MAX_REQUESTS_PER_MINUTE) {
            Map<String, String> errorBody = Map.of("message", "Too many requests");
            return Publishers.just(HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS).body(errorBody));
        }
        
        // Note: A real implementation would need to reset counts periodically.
        return chain.proceed(request);
    }
}

/**
 * Response Transformation Filter: Wraps successful responses in a standard JSON structure.
 */
@Singleton
@Order(50)
class ResponseTransformationFilter implements HttpServerFilter {
    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        return Mono.from(chain.proceed(request)).map(response -> {
            if (response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300 && response.getBody().isPresent()) {
                Object originalBody = response.getBody().get();
                Map<String, Object> wrappedBody = Map.of(
                    "status", "success",
                    "data", originalBody
                );
                // Re-cast is safe because we are replacing the body
                return (MutableHttpResponse<?>) response.body(wrappedBody);
            }
            return response;
        });
    }
}