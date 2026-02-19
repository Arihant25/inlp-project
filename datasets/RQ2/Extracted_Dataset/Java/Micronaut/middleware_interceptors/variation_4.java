package com.example.variation4;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// --- Domain Models ---

enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

record User(UUID id, String email, String password_hash, Role role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, Status status) {}

// --- Controller for Demonstration ---

@Controller("/api/v4")
class ConfigurableController {
    private static final User MOCK_USER = new User(
        UUID.randomUUID(), "config@example.com", "hash123", Role.USER, true, Timestamp.from(Instant.now())
    );

    @Get("/user/{id}")
    public User getUser(UUID id) {
        if (id.toString().startsWith("f")) {
            throw new UnsupportedOperationException("Forbidden ID range.");
        }
        return MOCK_USER;
    }
}

// --- Configuration Properties Classes ---

@ConfigurationProperties("app.filter-config")
class FilterConfig {
    boolean enabled = true;
    LoggingConfig logging = new LoggingConfig();
    CorsConfig cors = new CorsConfig();
    RateLimitConfig rateLimiting = new RateLimitConfig();
    ResponseWrapperConfig responseWrapper = new ResponseWrapperConfig();

    @ConfigurationProperties("logging")
    static class LoggingConfig {
        boolean enabled = true;
        String level = "INFO";
    }

    @ConfigurationProperties("cors")
    static class CorsConfig {
        boolean enabled = true;
        String allowedOrigin = "*";
        List<String> allowedMethods = List.of("GET", "POST");
    }

    @ConfigurationProperties("rate-limiting")
    static class RateLimitConfig {
        boolean enabled = true;
        int limit = 200;
    }

    @ConfigurationProperties("response-wrapper")
    static class ResponseWrapperConfig {
        boolean enabled = true;
        String successKey = "result";
        String errorKey = "fault";
    }
}

// --- Variation 4: Configuration-Driven Filter ---

/**
 * A single, powerful filter whose behavior is entirely dictated by externalized
 * configuration. This promotes flexibility and operational control.
 */
@Singleton
@Order(1)
class ConfigurableUniversalFilter implements HttpServerFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurableUniversalFilter.class);
    private final FilterConfig config;
    private final Map<String, AtomicLong> rateLimitTracker = new ConcurrentHashMap<>();

    @Inject
    public ConfigurableUniversalFilter(FilterConfig config) {
        this.config = config;
        LOG.info("Filter initialized with enabled={}", config.enabled);
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        if (!config.enabled) {
            return chain.proceed(request);
        }

        // CORS Pre-flight
        if (config.cors.enabled && request.getMethod() == io.micronaut.http.HttpMethod.OPTIONS) {
            return Mono.just(createCorsResponse(HttpResponse.ok()));
        }

        // Rate Limiting
        if (config.rateLimiting.enabled) {
            String ip = request.getRemoteAddress().getAddress().getHostAddress();
            rateLimitTracker.putIfAbsent(ip, new AtomicLong(0));
            if (rateLimitTracker.get(ip).incrementAndGet() > config.rateLimiting.limit) {
                return Mono.just(HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS).body("Rate limit exceeded"));
            }
        }

        long startTime = System.currentTimeMillis();
        if (config.logging.enabled) {
            LOG.info("[{}] Request: {} {}", config.logging.level, request.getMethod(), request.getUri());
        }

        return Mono.from(chain.proceed(request))
            .map(response -> {
                // Response Transformation
                if (config.responseWrapper.enabled && response.getStatus().getCode() < 400 && response.getBody().isPresent()) {
                    return response.body(Map.of(config.responseWrapper.successKey, response.body()));
                }
                return response;
            })
            .doOnNext(response -> {
                if (config.cors.enabled) {
                    createCorsResponse(response);
                }
                if (config.logging.enabled) {
                    long duration = System.currentTimeMillis() - startTime;
                    LOG.info("[{}] Response: {} for {} ({}ms)", config.logging.level, response.getStatus(), request.getUri(), duration);
                }
            })
            .onErrorResume(t -> {
                // Error Handling
                LOG.error("Filter chain error for {}: {}", request.getUri(), t.getMessage());
                Map<String, Object> errorBody = Map.of(
                    config.responseWrapper.errorKey,
                    Map.of("type", t.getClass().getSimpleName(), "message", t.getMessage())
                );
                MutableHttpResponse<?> errorResponse = HttpResponse.serverError(errorBody);
                if (config.cors.enabled) {
                    createCorsResponse(errorResponse);
                }
                return Mono.just(errorResponse);
            });
    }

    private MutableHttpResponse<?> createCorsResponse(MutableHttpResponse<?> response) {
        response.header("Access-Control-Allow-Origin", config.cors.allowedOrigin);
        response.header("Access-Control-Allow-Methods", String.join(", ", config.cors.allowedMethods));
        return response;
    }
}