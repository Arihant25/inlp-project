// Variation 1: The "Classic Service-Repository" Developer
// Style: Traditional OOP with clear separation of concerns (Resource -> Service -> Repository).
// Naming: Verbose and explicit (e.g., authenticateUser, generateJwtToken).
// Structure: Well-defined layers in separate packages.

// --- Configuration (place in src/main/resources/application.properties) ---
/*
quarkus.http.auth.jwt.enabled=true
mp.jwt.verify.publickey.location=META-INF/resources/publicKey.pem
mp.jwt.verify.issuer=https://acme.com/issuer

# A private key for signing tokens, generated for this example
# In production, use a secure key management system.
smallrye.jwt.sign.key.location=META-INF/resources/privateKey.pem
*/

// --- Package: com.acme.classic.model ---

package com.acme.classic.model;

import java.time.Instant;
import java.util.UUID;

public class User {
    private UUID id;
    private String email;
    private String passwordHash;
    private Role role;
    private boolean isActive;
    private Instant createdAt;

    public User(UUID id, String email, String passwordHash, Role role, boolean isActive) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.isActive = isActive;
        this.createdAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public boolean isActive() { return isActive; }
    public Instant getCreatedAt() { return createdAt; }
}

enum Role { ADMIN, USER }

class Post {
    private UUID id;
    private UUID userId;
    private String title;
    private String content;
    private Status status;
    
    enum Status { DRAFT, PUBLISHED }
    // Getters and Setters...
}


// --- Package: com.acme.classic.repository ---

package com.acme.classic.repository;

import com.acme.classic.model.User;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class UserRepository {
    private final Map<String, User> userStore = new ConcurrentHashMap<>();

    public UserRepository() {
        // Mock data
        User admin = new User(UUID.randomUUID(), "admin@acme.com", BcryptUtil.bcryptHash("admin123"), User.Role.ADMIN, true);
        User user = new User(UUID.randomUUID(), "user@acme.com", BcryptUtil.bcryptHash("user123"), User.Role.USER, true);
        userStore.put(admin.getEmail(), admin);
        userStore.put(user.getEmail(), user);
    }

    public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(userStore.get(email));
    }
}

// --- Package: com.acme.classic.service ---

package com.acme.classic.service;

import com.acme.classic.model.User;
import com.acme.classic.repository.UserRepository;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class AuthenticationService {

    @Inject
    UserRepository userRepository;

    public Optional<String> authenticateUser(String email, String password) {
        Optional<User> userOptional = userRepository.findByEmail(email);

        if (userOptional.isEmpty() || !userOptional.get().isActive()) {
            return Optional.empty();
        }

        User user = userOptional.get();
        if (BcryptUtil.matches(password, user.getPasswordHash())) {
            return Optional.of(generateJwtToken(user));
        }

        return Optional.empty();
    }

    private String generateJwtToken(User user) {
        Set<String> roles = new HashSet<>();
        roles.add(user.getRole().name());

        return Jwt.issuer("https://acme.com/issuer")
                .upn(user.getEmail())
                .subject(user.getId().toString())
                .groups(roles)
                .expiresIn(Duration.ofHours(1))
                .sign();
    }
}

// --- Package: com.acme.classic.web ---

package com.acme.classic.web;

import com.acme.classic.service.AuthenticationService;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.security.Principal;

@Path("/api")
public class AuthResource {

    @Inject
    AuthenticationService authService;

    @POST
    @Path("/login")
    @PermitAll
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_PLAIN)
    public Response login(@FormParam("email") String email, @FormParam("password") String password) {
        return authService.authenticateUser(email, password)
                .map(token -> Response.ok(token).build())
                .orElse(Response.status(Response.Status.UNAUTHORIZED).build());
    }
}

@Path("/api/posts")
@Produces(MediaType.APPLICATION_JSON)
public class PostResource {

    @Inject
    JsonWebToken jwt;
    
    @Inject
    Principal principal;

    @GET
    @Path("/user")
    @RolesAllowed("USER")
    public Response getUserPosts() {
        // In a real app, you'd fetch posts for the logged-in user
        String userId = jwt.getSubject();
        String userEmail = principal.getName();
        return Response.ok("Fetching posts for USER " + userEmail + " (ID: " + userId + ")").build();
    }

    @GET
    @Path("/admin")
    @RolesAllowed("ADMIN")
    public Response getAdminDashboard() {
        return Response.ok("Welcome to the admin dashboard, " + jwt.getName()).build();
    }
}

// --- Package: com.acme.classic.oauth ---
package com.acme.classic.oauth;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.OidcClients;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/oauth")
public class OAuthClientResource {
    
    // This demonstrates injecting the OIDC client.
    // Configuration in application.properties would be needed:
    // quarkus.oidc.client.auth-server-url=...
    // quarkus.oidc.client.client-id=...
    // quarkus.oidc.client.credentials.secret=...
    @Inject
    OidcClients oidcClients;

    @GET
    @Path("/google-token")
    public Uni<Response> getGoogleAccessToken() {
        OidcClient googleClient = oidcClients.getClient("google");
        return googleClient.getTokens().getAccessToken()
            .onItem().transform(token -> Response.ok(token).build());
    }
}