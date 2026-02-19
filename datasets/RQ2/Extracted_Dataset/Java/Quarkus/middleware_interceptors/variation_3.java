// Variation 3: The "Vert.x Power User" Approach
// This variation leverages Quarkus's underlying Vert.x engine by using @Filter
// methods. This provides low-level, high-performance access to the HTTP request
// pipeline before JAX-RS processing begins. It's great for cross-cutting concerns
// like security, logging, and routing.

package com.example.variation3.model;

import java.time.Instant;
import java.util.UUID;

enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

class User {
    public UUID id;
    public String email;
    public String password_hash;
    public Role role;
    public boolean is_active;
    public Instant created_at;
}

class Post {
    public UUID id;
    public UUID user_id;
    public String title;
    public String content;
    public Status status;
}

package com.example.variation3.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

class ErrorResponse {
    public String message;
    public int code;
    public List<String> details;

    public ErrorResponse(String message, int code, List<String> details) {
        this.message = message;
        this.code = code;
        this.details = details;
    }
    
    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "{\"message\":\"Failed to serialize error\",\"code\":500}";
        }
    }
}

package com.example.variation3.services;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
class RateLimiterService {
    private static final int MAX_REQUESTS = 100;

    private final LoadingCache<String, AtomicInteger> requestCounts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .build(key -> new AtomicInteger(0));

    public boolean isAllowed(String clientIdentifier) {
        return requestCounts.get(clientIdentifier).incrementAndGet() <= MAX_REQUESTS;
    }
}

package com.example.variation3.middleware;

import com.example.variation3.dto.ErrorResponse;
import com.example.variation3.services.RateLimiterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
class VertxHttpFilters {
    private static final Logger LOG = Logger.getLogger(VertxHttpFilters.class);

    @Inject
    RateLimiterService rateLimiterService;
    
    @Inject
    ObjectMapper objectMapper;

    public void filters(@Observes io.vertx.ext.web.Router router) {
        // 1. Logging and Rate Limiting Handler
        router.route().order(100).handler(ctx -> {
            HttpServerRequest request = ctx.request();
            String clientIp = request.remoteAddress().hostAddress();
            
            LOG.infof("Vert.x Filter: Request %s %s from %s", request.method(), request.uri(), clientIp);

            if (!rateLimiterService.isAllowed(clientIp)) {
                ErrorResponse error = new ErrorResponse("Rate limit exceeded", 429, null);
                ctx.response()
                   .setStatusCode(429)
                   .putHeader("Content-Type", "application/json")
                   .end(error.toJson(objectMapper));
                return; // Stop processing
            }
            ctx.next();
        });

        // 2. CORS Handler
        router.route().order(200).handler(ctx -> {
            ctx.response().putHeader("Access-Control-Allow-Origin", "*");
            ctx.response().putHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            ctx.response().putHeader("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization");
            
            // Handle pre-flight OPTIONS request
            if (ctx.request().method() == io.vertx.core.http.HttpMethod.OPTIONS) {
                ctx.response().setStatusCode(204).end();
                return;
            }
            ctx.next();
        });

        // 3. Global Failure Handler
        router.route().last().failureHandler(ctx -> {
            Throwable failure = ctx.failure();
            LOG.error("Vert.x Failure Handler caught:", failure);
            
            ErrorResponse error = new ErrorResponse("An unexpected error occurred", 500, Collections.singletonList(failure.getMessage()));
            if (!ctx.response().ended()) {
                ctx.response()
                   .setStatusCode(500)
                   .putHeader("Content-Type", "application/json")
                   .end(error.toJson(objectMapper));
            }
        });
    }
}

@Provider
class ResponseTransformationFilter implements ContainerResponseFilter {
    // A JAX-RS filter is still the most straightforward way to modify the
    // response body after it has been processed by the JAX-RS resource.
    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        if (responseContext.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            Object entity = responseContext.getEntity();
            Map<String, Object> wrapper = new HashMap<>();
            wrapper.put("data", entity);
            responseContext.setEntity(wrapper);
        }
    }
}

package com.example.variation3.resources;

import com.example.variation3.model.User;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Path("/v3/users")
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

    @GET
    public List<User> getAllUsers() {
        // This will trigger the failure handler if it fails
        if (System.currentTimeMillis() % 10 == 0) { // Simulate a random failure
             throw new IllegalStateException("A simulated random error occurred in the resource!");
        }
        
        User user = new User();
        user.id = UUID.randomUUID();
        user.email = "vertx.user@example.com";
        user.role = com.example.variation3.model.Role.USER;
        user.is_active = false;
        user.created_at = Instant.now();
        return List.of(user);
    }
}