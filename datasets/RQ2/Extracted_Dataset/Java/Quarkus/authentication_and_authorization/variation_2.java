// Variation 2: The "Functional & Compact" Developer
// Style: Prefers functional composition, streams, and immutability (records).
// Naming: Concise and direct (e.g., login, createToken).
// Structure: Flatter package structure, logic may be co-located in resources or static helpers.

// --- Configuration (place in src/main/resources/application.properties) ---
/*
quarkus.http.auth.jwt.enabled=true
mp.jwt.verify.publickey.location=META-INF/resources/publicKey.pem
mp.jwt.verify.issuer=https://functional.io/issuer

# A private key for signing tokens
smallrye.jwt.sign.key.location=META-INF/resources/privateKey.pem
*/

// --- Package: com.acme.functional.domain ---

package com.acme.functional.domain;

import java.time.Instant;
import java.util.UUID;

enum Role { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

record User(UUID id, String email, String passwordHash, Role role, boolean isActive, Instant createdAt) {}
record Post(UUID id, UUID userId, String title, String content, PostStatus status) {}
record LoginCredentials(String email, String password) {}

// --- Package: com.acme.functional.data ---

package com.acme.functional.data;

import com.acme.functional.domain.Role;
import com.acme.functional.domain.User;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@ApplicationScoped
public class UserStore {
    private static final Map<String, User> USERS = new ConcurrentHashMap<>();

    static {
        Stream.of(
            new User(UUID.randomUUID(), "admin@acme.com", BcryptUtil.bcryptHash("adminpass"), Role.ADMIN, true, Instant.now()),
            new User(UUID.randomUUID(), "user@acme.com", BcryptUtil.bcryptHash("userpass"), Role.USER, true, Instant.now()),
            new User(UUID.randomUUID(), "inactive@acme.com", BcryptUtil.bcryptHash("inactive"), Role.USER, false, Instant.now())
        ).forEach(u -> USERS.put(u.email(), u));
    }

    public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(USERS.get(email));
    }
}

// --- Package: com.acme.functional.security ---

package com.acme.functional.security;

import com.acme.functional.domain.User;
import io.smallrye.jwt.build.Jwt;
import java.time.Duration;
import java.util.Set;

public final class TokenFactory {
    private TokenFactory() {} // Static utility class

    public static String createToken(User user) {
        return Jwt.issuer("https://functional.io/issuer")
                .upn(user.email())
                .subject(user.id().toString())
                .groups(Set.of(user.role().name()))
                .expiresIn(Duration.ofDays(1))
                .sign();
    }
}

// --- Package: com.acme.functional.api ---

package com.acme.functional.api;

import com.acme.functional.data.UserStore;
import com.acme.functional.domain.LoginCredentials;
import com.acme.functional.security.TokenFactory;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.oidc.client.OidcClient;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.Map;

@Path("/v1")
@RequestScoped
public class AuthEndpoints {

    @Inject
    UserStore userStore;
    
    @Inject
    OidcClient oidcClient; // Default OIDC client

    @POST
    @Path("/login")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response login(LoginCredentials credentials) {
        return userStore.findByEmail(credentials.email())
                .filter(User::isActive)
                .filter(user -> BcryptUtil.matches(credentials.password(), user.passwordHash()))
                .map(TokenFactory::createToken)
                .map(token -> Response.ok(Map.of("token", token)).build())
                .orElse(Response.status(Response.Status.UNAUTHORIZED).build());
    }

    @GET
    @Path("/posts/my")
    @RolesAllowed({"USER", "ADMIN"})
    public Response getMyPosts(@Context SecurityContext ctx) {
        return Response.ok("Content for user: " + ctx.getUserPrincipal().getName()).build();
    }

    @POST
    @Path("/posts/publish")
    @RolesAllowed("ADMIN")
    public Response publishPost() {
        return Response.ok("Post published by admin.").build();
    }
    
    @GET
    @Path("/oauth/user-info")
    public Uni<Response> getOauthUserInfo() {
        // Demonstrates using the OIDC client to fetch user info from an OAuth2 provider
        return oidcClient.getUserInfo()
            .onItem().transform(userInfo -> Response.ok(userInfo.getJsonObject()).build());
    }
}