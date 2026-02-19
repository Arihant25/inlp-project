package com.example.rules;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.rules.SecurityRuleResult;
import io.micronaut.web.router.RouteMatch;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// --- Domain Models ---
enum Role { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }
record User(UUID id, String email, String password_hash, Role role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}

// --- Data Layer (Mocks) ---
// For brevity, using simple mocks. A real app would have proper repositories.
@Singleton
class DataStore {
    public final Map<UUID, User> users = new ConcurrentHashMap<>();
    public final Map<UUID, Post> posts = new ConcurrentHashMap<>();

    public DataStore() {
        // Mock data
        UUID adminId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        users.put(adminId, new User(adminId, "admin@example.com", "hashed", Role.ADMIN, true, Timestamp.from(Instant.now())));
        users.put(userId, new User(userId, "user@example.com", "hashed", Role.USER, true, Timestamp.from(Instant.now())));

        UUID post1Id = UUID.randomUUID();
        posts.put(post1Id, new Post(post1Id, userId, "User's Post", "Content by user", PostStatus.PUBLISHED));
    }

    public Optional<Post> findPostById(UUID id) {
        return Optional.ofNullable(posts.get(id));
    }
}

// --- Security: Centralized Authorization Rule ---

@Singleton
class PostSecurityRule implements SecurityRule<HttpRequest<?>> {
    public static final int ORDER = 0; // Higher precedence
    private final DataStore dataStore;

    @Inject
    public PostSecurityRule(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public Publisher<SecurityRuleResult> check(HttpRequest<?> request, @io.micronaut.core.annotation.Nullable Authentication authentication) {
        if (authentication == null) {
            return Mono.just(SecurityRuleResult.REJECTED);
        }

        RouteMatch<?> routeMatch = request.getAttribute(io.micronaut.web.router.Router.class)
                                          .flatMap(router -> router.route(request.getMethod(), request.getPath()))
                                          .orElse(null);

        if (routeMatch == null || !routeMatch.getUriMatchTemplate().toString().startsWith("/rules/posts")) {
            return Mono.just(SecurityRuleResult.UNKNOWN); // Not our concern
        }

        // ADMIN can do anything to posts
        if (authentication.getRoles().contains("ADMIN")) {
            return Mono.just(SecurityRuleResult.ALLOWED);
        }

        // Rule for viewing a specific post: /rules/posts/{id}
        if (request.getMethod().toString().equals("GET") && routeMatch.getVariableValues().containsKey("id")) {
            UUID postId = UUID.fromString(routeMatch.getVariableValues().get("id").toString());
            UUID userId = UUID.fromString(authentication.getAttributes().get("userId").toString());

            return Mono.fromCallable(() -> dataStore.findPostById(postId)
                .map(post -> {
                    if (post.user_id().equals(userId)) {
                        return SecurityRuleResult.ALLOWED; // User owns the post
                    }
                    return SecurityRuleResult.REJECTED; // User does not own the post
                })
                .orElse(SecurityRuleResult.ALLOWED)); // Allow to proceed to controller to generate a 404
        }
        
        // Rule for creating a post: POST /rules/posts
        if (request.getMethod().toString().equals("POST")) {
            // Any authenticated user can attempt to create a post
            return Mono.just(SecurityRuleResult.ALLOWED);
        }

        return Mono.just(SecurityRuleResult.UNKNOWN);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}


// --- Controller Layer ---
// This controller is simpler, as complex logic is in PostSecurityRule.
// It relies on a simple IS_AUTHENTICATED check, and the rule handles the rest.
// Note: An AuthenticationProvider is still required for login, but is omitted here for brevity
// as it would be identical to the one in Variation 1.

@Controller("/rules/posts")
@Secured(SecurityRule.IS_AUTHENTICATED) // A blanket check, the rule provides fine-grained control
public class PostControllerWithRules {

    private final DataStore dataStore;

    @Inject
    public PostControllerWithRules(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Post
    public HttpResponse<Post> create(@Body PostDTO dto, Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getAttributes().get("userId").toString());
        Post newPost = new Post(UUID.randomUUID(), userId, dto.title(), dto.content(), PostStatus.DRAFT);
        dataStore.posts.put(newPost.id(), newPost);
        return HttpResponse.created(newPost);
    }

    @Get("/{id}")
    public HttpResponse<Post> getById(UUID id) {
        // The PostSecurityRule has already verified that the user is authorized to see this post.
        // If the rule had rejected, this method would never be called.
        return dataStore.findPostById(id)
            .map(HttpResponse::ok)
            .orElse(HttpResponse.notFound());
    }
}

record PostDTO(String title, String content) {}

/*
--- Necessary application.yml configuration ---
micronaut:
  security:
    enabled: true
    # This example assumes JWT tokens are generated by a login process
    # similar to Variation 1. The key is demonstrating the authorization rule.
    token:
      jwt:
        enabled: true
        signatures:
          secret:
            generator:
              secret: "a-third-very-long-and-secure-secret-for-jwt"
*/