package com.example.oauth;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.oauth2.client.clientconfiguration.ClientConfiguration;
import io.micronaut.security.oauth2.configuration.OauthClientConfiguration;
import io.micronaut.security.oauth2.endpoint.authorization.state.State;
import io.micronaut.security.oauth2.endpoint.token.response.OauthAuthenticationMapper;
import io.micronaut.security.oauth2.endpoint.token.response.TokenResponse;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// --- Domain Models ---
enum Role { ADMIN, USER }
record User(UUID id, String email, Role role, boolean isActive, Timestamp createdAt) {}
// Post entity is omitted for brevity as this variation focuses on OAuth2 user mapping.

// --- User Management Service (Mock) ---
// In a real app, this would interact with a database.
@Singleton
class UserService {
    private final Map<String, User> usersByEmail = new ConcurrentHashMap<>();

    public UserService() {
        // Pre-populate an admin user. In a real system, this would be managed.
        UUID adminId = UUID.randomUUID();
        User admin = new User(adminId, "admin@corp.com", Role.ADMIN, true, Timestamp.from(Instant.now()));
        usersByEmail.put(admin.email(), admin);
    }

    public User findOrCreateByEmail(String email) {
        return usersByEmail.computeIfAbsent(email, e -> {
            UUID newId = UUID.randomUUID();
            // New users from OAuth2 get the USER role by default.
            return new User(newId, e, Role.USER, true, Timestamp.from(Instant.now()));
        });
    }
}

// --- OAuth2 Authentication Mapper ---
// This is the core of the integration. It maps the OAuth2 provider's response
// to a local user and their roles.

@Named("github") // This name must match the OAuth2 client configuration name.
@Singleton
class GithubAuthenticationMapper implements OauthAuthenticationMapper {

    private final UserService userService;

    @Inject
    public GithubAuthenticationMapper(UserService userService) {
        this.userService = userService;
    }

    @Override
    public Publisher<AuthenticationResponse> createAuthenticationResponse(
            TokenResponse tokenResponse,
            @io.micronaut.core.annotation.Nullable State state) {
        
        // In a real scenario, you would make an HTTP call to the OAuth provider's user info endpoint
        // using the access token from tokenResponse. Here, we mock the response.
        // For GitHub, this would be `https://api.github.com/user`.
        
        // Mocked response from the user info endpoint.
        Map<String, Object> githubUserAttributes = Map.of(
            "login", "john.doe",
            "id", 12345,
            "email", "john.doe.oauth@example.com" // This is the key piece of info
        );

        return Mono.fromCallable(() -> {
            String email = (String) githubUserAttributes.get("email");
            if (email == null) {
                return AuthenticationResponse.failure("Email not found in OAuth2 provider response.");
            }

            // Find or create a local user based on the email from the provider.
            User localUser = userService.findOrCreateByEmail(email);

            if (!localUser.isActive()) {
                return AuthenticationResponse.failure("User account is disabled.");
            }

            // Create the Micronaut Authentication object with local roles and attributes.
            return AuthenticationResponse.success(
                localUser.email(),
                Collections.singletonList(localUser.role().name()),
                Map.of(
                    "userId", localUser.id(),
                    "provider", "github"
                )
            );
        });
    }
}

// --- Controller Layer ---
// Demonstrates accessing the mapped user principal.

@Controller("/user")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class UserProfileController {

    @Get("/me")
    public HttpResponse<Map<String, Object>> me(Authentication authentication) {
        // The 'authentication' object is now populated by our GithubAuthenticationMapper.
        Map<String, Object> profile = Map.of(
            "email", authentication.getName(),
            "roles", authentication.getRoles(),
            "attributes", authentication.getAttributes()
        );
        return HttpResponse.ok(profile);
    }
}

@Controller("/admin")
@Secured("ADMIN") // This endpoint is only accessible if the mapped user has the ADMIN role.
public class AdminController {

    @Get("/dashboard")
    public String dashboard(Authentication authentication) {
        return "Welcome to the admin dashboard, " + authentication.getName() + "!";
    }
}

/*
--- Necessary application.yml configuration ---
micronaut:
  security:
    enabled: true
    oauth2:
      enabled: true
      clients:
        github: # This name must match the @Named annotation on the mapper
          client-id: "${GITHUB_CLIENT_ID}"
          client-secret: "${GITHUB_CLIENT_SECRET}"
          authorization:
            url: "https://github.com/login/oauth/authorize"
          token:
            url: "https://github.com/login/oauth/access_token"
    # Session-based management is common for OAuth2 web flows
    session:
      enabled: true
      login-success-target-url: "/user/me"
      login-failure-target-url: "/login/authFailed"
*/