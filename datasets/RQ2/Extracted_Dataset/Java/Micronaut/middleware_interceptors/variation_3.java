package com.example.variation3;

import io.micronaut.http.HttpAttributes;
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
import io.micronaut.web.router.RouteMatch;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// --- Domain Models ---

enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

record User(UUID id, String email, Role role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, Status status) {}

// --- Custom Annotations for AOP-style filtering ---

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface RateLimited {
    int value() default 50; // requests per minute
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface Loggable {}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface StandardApiResponse {}

// --- Controller for Demonstration ---

@Controller("/api/v3")
class PostController {
    private static final User MOCK_USER = new User(
        UUID.randomUUID(), "test@example.com", Role.USER, true, Timestamp.from(Instant.now())
    );

    @Get("/user/{id}")
    @Loggable
    @RateLimited(10) // Stricter rate limit for this endpoint
    @StandardApiResponse
    @Produces("application/json")
    public User getUser(UUID id) {
        if (id.equals(UUID.fromString("00000000-0000-0000-0000-000000000000"))) {
            throw new IllegalStateException("System user cannot be fetched.");
        }
        return MOCK_USER;
    }

    @Get("/health") // No annotations, so filter logic won't apply
    public HttpResponse<String> healthCheck() {
        return HttpResponse.ok("OK");
    }
}

// --- Variation 3: Annotation-Driven AOP-style Filter ---

/**
 * A single filter that inspects annotations on controller methods to apply logic.
 * Global concerns like CORS and Error Handling are still handled universally.
 */
@Singleton
@Order(Integer.MIN_VALUE) // Run early to wrap everything
class AnnotationDrivenFilter implements HttpServerFilter {
    private static final Logger LOG = LoggerFactory.getLogger(AnnotationDrivenFilter.class);
    private final Map<String, Long> rateLimitTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Integer> rateLimitCounters = new ConcurrentHashMap<>();

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        Optional<RouteMatch<?>> routeMatchOpt = request.getAttribute(HttpAttributes.ROUTE_MATCH, RouteMatch.class);

        // 1. Universal CORS Handling
        if (request.getMethod().equals(io.micronaut.http.HttpMethod.OPTIONS)) {
            return Mono.just(HttpResponse.ok()
                .header("Access-Control-Allow-Origin", "https://example.com")
                .header("Access-Control-Allow-Methods", "GET, POST")
                .header("Access-Control-Allow-Headers", "Content-Type"));
        }

        // 2. Annotation-driven Rate Limiting
        if (routeMatchOpt.isPresent() && routeMatchOpt.get().hasAnnotation(RateLimited.class)) {
            if (isRateLimited(request, routeMatchOpt.get())) {
                return Mono.just(HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "Endpoint rate limit exceeded.")));
            }
        }

        // 3. Proceed with chain, wrapping for other concerns
        long startTime = System.currentTimeMillis();
        return Mono.from(chain.proceed(request))
            .map(response -> {
                // 4. Annotation-driven Response Transformation
                if (routeMatchOpt.isPresent() && routeMatchOpt.get().hasAnnotation(StandardApiResponse.class)) {
                    if (response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300 && response.getBody().isPresent()) {
                        return response.body(Map.of("payload", response.body()));
                    }
                }
                return response;
            })
            .doOnSuccess(response -> {
                // 5. Annotation-driven Logging
                if (routeMatchOpt.isPresent() && routeMatchOpt.get().hasAnnotation(Loggable.class)) {
                    long duration = System.currentTimeMillis() - startTime;
                    LOG.info("AUDIT: {} {} -> {} ({}ms)", request.getMethod(), request.getUri(), response.getStatus(), duration);
                }
                response.header("Access-Control-Allow-Origin", "https://example.com");
            })
            .onErrorResume(ex -> {
                // 6. Universal Error Handling
                LOG.error("Unhandled exception for {}: {}", request.getUri(), ex.getMessage());
                MutableHttpResponse<?> errorResponse = HttpResponse.serverError(Map.of("error", "An unexpected error occurred", "details", ex.getClass().getSimpleName()));
                errorResponse.header("Access-Control-Allow-Origin", "https://example.com");
                return Mono.just(errorResponse);
            });
    }

    private boolean isRateLimited(HttpRequest<?> request, RouteMatch<?> routeMatch) {
        String key = request.getRemoteAddress().getAddress().getHostAddress() + ":" + routeMatch.getUriMatchTemplate().toString();
        int limit = routeMatch.getAnnotation(RateLimited.class).getValue("value", Integer.class).orElse(50);
        
        long currentTime = System.currentTimeMillis() / 60000; // Per minute window
        long lastTime = rateLimitTimestamps.getOrDefault(key, 0L);

        if (currentTime > lastTime) {
            rateLimitTimestamps.put(key, currentTime);
            rateLimitCounters.put(key, 1);
            return false;
        }

        return rateLimitCounters.computeIfPresent(key, (k, v) -> v + 1) > limit;
    }
}