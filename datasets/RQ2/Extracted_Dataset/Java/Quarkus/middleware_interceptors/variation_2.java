// Variation 2: The "Annotation-Driven & CDI" Approach
// This variation uses custom annotations and CDI interceptors to apply middleware
// declaratively on specific JAX-RS resource methods. This provides fine-grained
// control and makes the resource's behavior explicit.

package com.example.variation2.model;

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

package com.example.variation2.dto;

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
}

package com.example.variation2.middleware.bindings;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;
import jakarta.ws.rs.NameBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Loggable {}

@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RateLimited {
    @Nonbinding int requestsPerMinute() default 100;
}

@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface TransformResponse {}


package com.example.variation2.services;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.concurrent.Semaphore;

@ApplicationScoped
class RateLimiterService {
    private final LoadingCache<String, Semaphore> limiters = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .build(key -> new Semaphore(Integer.parseInt(key.split(":")[1])));

    public boolean tryAcquire(String clientIdentifier, int permits) {
        // Key includes permit count to create different limiters for different annotations
        String key = clientIdentifier + ":" + permits;
        return limiters.get(key).tryAcquire();
    }
}

package com.example.variation2.middleware.interceptors;

import com.example.variation2.middleware.bindings.Loggable;
import com.example.variation2.middleware.bindings.RateLimited;
import com.example.variation2.services.RateLimiterService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

@Loggable
@Interceptor
@Priority(2000)
class LoggingInterceptor {
    private static final Logger LOG = Logger.getLogger(LoggingInterceptor.class);

    @AroundInvoke
    public Object logMethodEntry(InvocationContext context) throws Exception {
        LOG.infof("Executing method: %s", context.getMethod().getName());
        return context.proceed();
    }
}

@RateLimited(requestsPerMinute = 0) // Dummy value, real one is on the method
@Interceptor
@Priority(1000) // Higher priority to run before logging
class RateLimitingInterceptor {
    @Inject
    RateLimiterService rateLimiterService;

    @Context
    HttpHeaders headers;

    private String getClientIp() {
        String ip = headers.getHeaderString("X-Forwarded-For");
        return (ip != null && !ip.isEmpty()) ? ip.split(",")[0].trim() : "127.0.0.1";
    }

    @AroundInvoke
    public Object enforceRateLimit(InvocationContext context) throws Exception {
        RateLimited annotation = context.getMethod().getAnnotation(RateLimited.class);
        if (annotation != null) {
            int limit = annotation.requestsPerMinute();
            if (!rateLimiterService.tryAcquire(getClientIp(), limit)) {
                return Response.status(429)
                        .entity(new com.example.variation2.dto.ErrorResponse("Too Many Requests", 429, null))
                        .build();
            }
        }
        return context.proceed();
    }
}

package com.example.variation2.middleware.filters;

import com.example.variation2.middleware.bindings.TransformResponse;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.util.HashMap;
import java.util.Map;

@Provider
class CorsFilter implements ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        responseContext.getHeaders().add("Access-Control-Allow-Origin", "*");
        responseContext.getHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        responseContext.getHeaders().add("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization");
    }
}

@TransformResponse
@Provider
class ResponseTransformationFilter implements ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        Object currentEntity = responseContext.getEntity();
        if (currentEntity != null && !(currentEntity instanceof com.example.variation2.dto.ErrorResponse)) {
            Map<String, Object> wrappedResponse = new HashMap<>();
            wrappedResponse.put("data", currentEntity);
            responseContext.setEntity(wrappedResponse);
        }
    }
}

package com.example.variation2.exceptions;

import com.example.variation2.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.stream.Collectors;

@Provider
class ValidationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {
    @Override
    public Response toResponse(ConstraintViolationException exception) {
        var details = exception.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.toList());
        ErrorResponse error = new ErrorResponse("Validation Failed", 400, details);
        return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
    }
}

@Provider
class GenericExceptionMapper implements ExceptionMapper<Throwable> {
    @Override
    public Response toResponse(Throwable exception) {
        ErrorResponse error = new ErrorResponse("Internal Server Error", 500, null);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
    }
}

package com.example.variation2.resources;

import com.example.variation2.middleware.bindings.Loggable;
import com.example.variation2.middleware.bindings.RateLimited;
import com.example.variation2.middleware.bindings.TransformResponse;
import com.example.variation2.model.User;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Path("/v2/users")
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

    @GET
    @Loggable
    @RateLimited(requestsPerMinute = 50)
    @TransformResponse
    public List<User> getAllUsers() {
        // Mock data for demonstration
        User user = new User();
        user.id = UUID.randomUUID();
        user.email = "annotated.user@example.com";
        user.role = com.example.variation2.model.Role.ADMIN;
        user.is_active = true;
        user.created_at = Instant.now();
        return List.of(user);
    }
}