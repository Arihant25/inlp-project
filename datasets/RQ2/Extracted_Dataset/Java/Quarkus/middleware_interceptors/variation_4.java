// Variation 4: The "Pragmatic & Consolidated" Approach
// This variation consolidates related middleware concerns into fewer classes.
// A single class handles multiple filter interfaces, and another class acts as a
// container for all exception mappers. This reduces boilerplate and file count,
// which can be desirable in smaller services.

package com.example.variation4.model;

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

package com.example.variation4.dto;

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
    public Instant timestamp = Instant.now();

    public ApiResponse(T data) {
        this.data = data;
    }
}

package com.example.variation4.services;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.concurrent.Semaphore;

@ApplicationScoped
class RateLimiterService {
    private final LoadingCache<String, Semaphore> limiters = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .build(key -> new Semaphore(100));

    public Semaphore getLimiter(String clientIdentifier) {
        return limiters.get(clientIdentifier);
    }
}

package com.example.variation4.middleware;

import com.example.variation4.dto.ApiResponse;
import com.example.variation4.dto.ErrorResponse;
import com.example.variation4.services.RateLimiterService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.jboss.logging.Logger;
import java.io.IOException;
import java.util.Collections;

@Provider
@PreMatching // Run this filter before JAX-RS resource matching, crucial for CORS pre-flight
@Priority(Priorities.AUTHENTICATION)
class GlobalHttpPipeline implements ContainerRequestFilter, ContainerResponseFilter {
    private static final Logger LOG = Logger.getLogger(GlobalHttpPipeline.class);

    @Inject
    RateLimiterService rateLimiterService;

    @Context
    private jakarta.ws.rs.core.UriInfo uriInfo;

    @Context
    private jakarta.ws.rs.core.HttpHeaders httpHeaders;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // 1. Logging
        LOG.infof("Request Pipeline: %s %s", requestContext.getMethod(), uriInfo.getPath());

        // 2. Rate Limiting
        String clientIp = httpHeaders.getHeaderString("X-Forwarded-For");
        if (clientIp == null) clientIp = "127.0.0.1";
        if (!rateLimiterService.getLimiter(clientIp).tryAcquire()) {
            ErrorResponse error = new ErrorResponse("Too many requests", 429, null);
            requestContext.abortWith(Response.status(429).entity(error).build());
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        // 3. CORS Handling
        responseContext.getHeaders().add("Access-Control-Allow-Origin", "*");
        responseContext.getHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        responseContext.getHeaders().add("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization");
    }
}

@Provider
class ResponseWrappingInterceptor implements WriterInterceptor {
    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
        Object entity = context.getEntity();
        if (entity instanceof ErrorResponse || entity instanceof ApiResponse) {
            context.proceed();
            return;
        }
        context.setEntity(new ApiResponse<>(entity));
        context.proceed();
    }
}

class ApplicationExceptionMappers {
    private static final Logger LOG = Logger.getLogger(ApplicationExceptionMappers.class);

    @Provider
    public static class GenericExceptionMapper implements ExceptionMapper<Throwable> {
        @Override
        public Response toResponse(Throwable exception) {
            LOG.error("Caught unhandled exception", exception);
            ErrorResponse error = new ErrorResponse("Internal Server Error", 500, Collections.singletonList(exception.getMessage()));
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
        }
    }

    @Provider
    public static class NotFoundExceptionMapper implements ExceptionMapper<jakarta.ws.rs.NotFoundException> {
        @Override
        public Response toResponse(jakarta.ws.rs.NotFoundException exception) {
            ErrorResponse error = new ErrorResponse("Resource Not Found", 404, Collections.singletonList(exception.getMessage()));
            return Response.status(Response.Status.NOT_FOUND).entity(error).build();
        }
    }
}

package com.example.variation4.resources;

import com.example.variation4.model.User;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Path("/v4/users")
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

    @GET
    public List<User> getAllUsers() {
        User user = new User();
        user.id = UUID.randomUUID();
        user.email = "consolidated.user@example.com";
        user.role = com.example.variation4.model.Role.USER;
        user.is_active = true;
        user.created_at = Instant.now();
        return List.of(user);
    }

    @GET
    @Path("/{id}")
    public User getUserById(@PathParam("id") UUID id) {
        // This will trigger the NotFoundExceptionMapper if the path is wrong
        // but for a valid UUID, we simulate a found user.
        User user = new User();
        user.id = id;
        user.email = "found.user@example.com";
        user.role = com.example.variation4.model.Role.ADMIN;
        user.is_active = true;
        user.created_at = Instant.now();
        return user;
    }
}