// Variation 4: The "Panache Enthusiast" Developer
// Style: Leverages Quarkus Panache for data access, resulting in a thin service layer.
// Naming: Follows Panache conventions (e.g., UserEntity, findByEmail).
// Structure: Active Record pattern co-locates data and access logic in the entity itself.

// --- Configuration (place in src/main/resources/application.properties) ---
/*
# In-memory H2 database for Panache
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1
quarkus.hibernate-orm.database.generation=drop-and-create

# JWT Security
quarkus.http.auth.jwt.enabled=true
mp.jwt.verify.publickey.location=META-INF/resources/publicKey.pem
mp.jwt.verify.issuer=https://panache.dev/issuer
smallrye.jwt.sign.key.location=META-INF/resources/privateKey.pem
*/

// --- Data Loading (place in src/main/resources/import.sql) ---
/*
INSERT INTO UserEntity(id, email, passwordHash, role, isActive, createdAt) VALUES ('8f9b7a7b-8d3c-4a2e-8e1f-9b7a7b8d3c4a', 'admin@panache.dev', '$2a$10$Q.e.q.q.Q.e.q.q.Q.e.q.u5J3g9h3g9h3g9h3g9h3g9h3g9h3g9', 'ADMIN', true, NOW());
INSERT INTO UserEntity(id, email, passwordHash, role, isActive, createdAt) VALUES ('1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d', 'user@panache.dev', '$2a$10$R.f.R.f.R.f.R.f.R.f.R.u5J3g9h3g9h3g9h3g9h3g9h3g9h3g9', 'USER', true, NOW());
-- Note: The hashes are placeholders. Real app would generate them.
-- For this example, the code will hash the passwords and insert them.
*/

// --- Package: org.quarkus.panache.model ---

package org.quarkus.panache.model;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Entity
public class UserEntity extends PanacheEntityBase {
    @Id
    public UUID id;
    @Column(unique = true, nullable = false)
    public String email;
    @Column(nullable = false)
    public String passwordHash;
    @Enumerated(EnumType.STRING)
    public Role role;
    public boolean isActive;
    public Instant createdAt;

    public static Optional<UserEntity> findByEmail(String email) {
        return find("email", email).firstResultOptional();
    }

    public boolean verifyPassword(String password) {
        return BcryptUtil.matches(password, this.passwordHash);
    }
}

@Entity
public class PostEntity extends PanacheEntityBase {
    @Id
    public UUID id;
    public UUID userId;
    public String title;
    public String content;
    @Enumerated(EnumType.STRING)
    public Status status;
}

enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

// --- Package: org.quarkus.panache.service ---

package org.quarkus.panache.service;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.quarkus.panache.model.Role;
import org.quarkus.panache.model.UserEntity;
import io.quarkus.elytron.security.common.BcryptUtil;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class AuthService {

    @Transactional
    public void setupInitialUsers() {
        if (UserEntity.count() == 0) {
            UserEntity admin = new UserEntity();
            admin.id = UUID.randomUUID();
            admin.email = "admin@panache.dev";
            admin.passwordHash = BcryptUtil.bcryptHash("panache_admin");
            admin.role = Role.ADMIN;
            admin.isActive = true;
            admin.createdAt = Instant.now();
            admin.persist();

            UserEntity user = new UserEntity();
            user.id = UUID.randomUUID();
            user.email = "user@panache.dev";
            user.passwordHash = BcryptUtil.bcryptHash("panache_user");
            user.role = Role.USER;
            user.isActive = true;
            user.createdAt = Instant.now();
            user.persist();
        }
    }

    public Optional<String> login(String email, String password) {
        return UserEntity.findByEmail(email)
            .filter(user -> user.isActive)
            .filter(user -> user.verifyPassword(password))
            .map(this::generateToken);
    }

    private String generateToken(UserEntity user) {
        return Jwt.issuer("https://panache.dev/issuer")
            .upn(user.email)
            .subject(user.id.toString())
            .groups(Set.of(user.role.name()))
            .expiresIn(Duration.ofMinutes(60))
            .sign();
    }
}

// --- Package: org.quarkus.panache.resource ---

package org.quarkus.panache.resource;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.quarkus.panache.service.AuthService;
import io.quarkus.oidc.client.OidcClient;
import io.smallrye.mutiny.Uni;

@Path("/api/v2")
public class AuthenticationResource {

    @Inject
    AuthService authService;
    
    @Inject
    OidcClient oidcClient;

    // Use startup event to create initial users
    void onStart(@Observes StartupEvent ev) {
        authService.setupInitialUsers();
    }

    public static class LoginPayload {
        public String email;
        public String password;
    }

    @POST
    @Path("/login")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response login(LoginPayload payload) {
        return authService.login(payload.email, payload.password)
            .map(token -> Response.ok(token).build())
            .orElse(Response.status(Response.Status.UNAUTHORIZED).build());
    }

    @GET
    @Path("/posts")
    @RolesAllowed({"ADMIN", "USER"})
    public Response getPosts(@Context SecurityContext ctx) {
        return Response.ok("Accessing posts as " + ctx.getUserPrincipal().getName()).build();
    }

    @DELETE
    @Path("/users/{id}")
    @RolesAllowed("ADMIN")
    public Response deleteUser(@PathParam("id") UUID id) {
        // Panache active record pattern in action
        boolean deleted = UserEntity.deleteById(id);
        if (deleted) {
            return Response.noContent().build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    
    @GET
    @Path("/proxy-oauth")
    @PermitAll
    public Uni<String> proxyToOauthService() {
        // Demonstrates using the OIDC client to get a token and (conceptually) call another service
        return oidcClient.getTokens().getAccessToken();
    }
}