package com.example.variation2;

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

record User(UUID id, String email, String password_hash, Role role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, Status status) {}

// --- Controller for Demonstration ---

@Controller("/api/v2")
class UserController {
    private static final User MOCK_USER = new User(
        UUID.randomUUID(),
        "test@example.com",
        "hashed_password",
        Role.ADMIN,
        true,
        Timestamp.from(Instant.now())
    );

    @Get("/user/{id}")
    @Produces("application/json")
    public HttpResponse<User> findUser(UUID id) {
        if ("00000000-0000-0000-0000-000000000000".equals(id.toString())) {
            throw new RuntimeException("Invalid ID specified.");
        }
        return HttpResponse.ok(MOCK_USER);
    }
}

// --- Variation 2: Consolidated Functional/Reactive Approach ---

/**
 * A single, consolidated filter that uses a reactive chain to handle all cross-cutting concerns.
 */
@Singleton
@Order(100)
class ConsolidatedReactiveFilter implements HttpServerFilter {

    private static final Logger log = LoggerFactory.getLogger(ConsolidatedReactiveFilter.class);
    private final Map<String, AtomicInteger> reqCounts = new ConcurrentHashMap<>();
    private static final int RATE_LIMIT = 100;

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        long startTime = System.currentTimeMillis();

        // 1. CORS Pre-flight Handling (Imperative)
        if (request.getMethod() == HttpMethod.OPTIONS) {
            return Publishers.just(createCorsPreflightResponse());
        }

        // 2. Rate Limiting (Imperative)
        String clientIp = request.getRemoteAddress().getAddress().getHostAddress();
        reqCounts.putIfAbsent(clientIp, new AtomicInteger(0));
        if (reqCounts.get(clientIp).incrementAndGet() > RATE_LIMIT) {
            return Publishers.just(HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "Rate limit exceeded")));
        }

        // 3. Reactive Chain for Logging, Transformation, and Error Handling
        return Mono.from(chain.proceed(request))
            .doOnSubscribe(subscription -> log.info(">> REQ: {} {}", request.getMethod(), request.getUri()))
            .map(this::transformSuccessResponse)
            .doOnNext(response -> {
                addCorsHeaders(response);
                long duration = System.currentTimeMillis() - startTime;
                log.info("<< RES: {} for {} {} ({}ms)", response.getStatus(), request.getMethod(), request.getUri(), duration);
            })
            .onErrorResume(this::handleError);
    }

    private MutableHttpResponse<?> transformSuccessResponse(MutableHttpResponse<?> response) {
        if (response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300 && response.getBody().isPresent()) {
            Map<String, Object> wrappedBody = Map.of("data", response.getBody().get());
            return response.body(wrappedBody);
        }
        return response;
    }

    private Mono<MutableHttpResponse<?>> handleError(Throwable throwable) {
        log.error("!! ERR: Exception processing request: {}", throwable.getMessage());
        Map<String, String> errorBody = Map.of("error", throwable.getMessage() != null ? throwable.getMessage() : "Internal Server Error");
        MutableHttpResponse<?> errorResponse = HttpResponse.serverError().body(errorBody);
        addCorsHeaders(errorResponse);
        return Mono.just(errorResponse);
    }

    private void addCorsHeaders(MutableHttpResponse<?> response) {
        response.header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type");
    }

    private MutableHttpResponse<?> createCorsPreflightResponse() {
        MutableHttpResponse<?> response = HttpResponse.ok();
        addCorsHeaders(response);
        return response;
    }
}