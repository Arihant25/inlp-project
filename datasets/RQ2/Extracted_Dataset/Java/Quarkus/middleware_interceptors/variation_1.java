// Variation 1: The "Classic JAX-RS" Approach
// This variation uses separate, focused classes for each piece of middleware,
// registered globally using the JAX-RS @Provider annotation. It represents a
// traditional, clean, and highly decoupled architecture.

package com.example.variation1.model;

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

package com.example.variation1.dto;

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

class ApiResponse<T> {
    public T data;

    public ApiResponse(T data) {
        this.data = data;
    }
}

package com.example.variation1.exceptions;

import jakarta.ws.rs.core.Response;

public class RateLimitException extends RuntimeException {
    public RateLimitException(String message) {
        super(message);
    }
}

package com.example.variation1.services;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.concurrent.Semaphore;

@ApplicationScoped
class RateLimiterService {
    private static final int MAX_REQUESTS_PER_MINUTE = 100;

    private final LoadingCache<String, Semaphore> limiters = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .build(key -> new Semaphore(MAX_REQUESTS_PER_MINUTE));

    public boolean tryAcquire(String clientIdentifier) {
        return limiters.get(clientIdentifier).tryAcquire();
    }
}

package com.example.variation1.filters;

import com.example.variation1.exceptions.RateLimitException;
import com.example.variation1.services.RateLimiterService;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

@Provider
class RequestLoggingFilter implements ContainerRequestFilter {
    private static final Logger LOG = Logger.getLogger(RequestLoggingFilter.class);

    @Context
    SecurityContext securityContext;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String user = securityContext.getUserPrincipal() != null ? securityContext.getUserPrincipal().getName() : "anonymous";
        LOG.infof("Request received: %s %s from user: %s",
                requestContext.getMethod(),
                requestContext.getUriInfo().getPath(),
                user);
    }
}

@Provider
class CorsFilter implements jakarta.ws.rs.container.ContainerResponseFilter {
    @Override
    public void filter(jakarta.ws.rs.container.ContainerRequestContext requestContext,
                       jakarta.ws.rs.container.ContainerResponseContext responseContext) {
        responseContext.getHeaders().add("Access-Control-Allow-Origin", "*");
        responseContext.getHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        responseContext.getHeaders().add("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization");
    }
}

@Provider
class RateLimitingFilter implements ContainerRequestFilter {
    @Inject
    RateLimiterService rateLimiterService;

    @Context
    jakarta.ws.rs.core.HttpHeaders headers;

    // In a real app, use a more robust IP detection mechanism
    private String getClientIp() {
        String ip = headers.getHeaderString("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) {
            return ip.split(",")[0].trim();
        }
        // Fallback, not safe behind a proxy
        return "127.0.0.1";
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String clientIp = getClientIp();
        if (!rateLimiterService.tryAcquire(clientIp)) {
            throw new RateLimitException("Rate limit exceeded for IP: " + clientIp);
        }
    }
}

package com.example.variation1.interceptors;

import com.example.variation1.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import java.io.IOException;
import java.io.OutputStream;

@Provider
class ResponseWrapperInterceptor implements WriterInterceptor {

    @Inject
    ObjectMapper objectMapper;

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        Object originalEntity = context.getEntity();

        // Avoid wrapping our standard error responses
        if (originalEntity instanceof com.example.variation1.dto.ErrorResponse) {
            context.proceed();
            return;
        }

        ApiResponse<Object> wrappedResponse = new ApiResponse<>(originalEntity);
        
        // We need to replace the output stream to write our wrapped response
        OutputStream originalStream = context.getOutputStream();
        context.setOutputStream(new OutputStream() {
             @Override
             public void write(int b) throws IOException {
                 // This stream is now a black hole, we write directly below
             }
        });
        
        context.setEntity(wrappedResponse);
        // Manually serialize and write the new wrapped entity
        objectMapper.writeValue(originalStream, wrappedResponse);
    }
}


package com.example.variation1.exceptions.mappers;

import com.example.variation1.dto.ErrorResponse;
import com.example.variation1.exceptions.RateLimitException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Collections;
import org.jboss.logging.Logger;

@Provider
class GenericExceptionMapper implements ExceptionMapper<Throwable> {
    private static final Logger LOG = Logger.getLogger(GenericExceptionMapper.class);

    @Override
    public Response toResponse(Throwable exception) {
        LOG.error("Unhandled exception caught", exception);
        ErrorResponse error = new ErrorResponse("An internal server error occurred.", 500, Collections.singletonList(exception.getMessage()));
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
    }
}

@Provider
class RateLimitExceptionMapper implements ExceptionMapper<RateLimitException> {
    @Override
    public Response toResponse(RateLimitException exception) {
        ErrorResponse error = new ErrorResponse("Too Many Requests", 429, Collections.singletonList(exception.getMessage()));
        return Response.status(429).entity(error).build();
    }
}

package com.example.variation1.resources;

import com.example.variation1.model.User;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Path("/v1/users")
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

    @GET
    public List<User> getAllUsers() {
        // Mock data for demonstration
        User user = new User();
        user.id = UUID.randomUUID();
        user.email = "classic.user@example.com";
        user.role = com.example.variation1.model.Role.USER;
        user.is_active = true;
        user.created_at = Instant.now();
        return List.of(user);
    }
}