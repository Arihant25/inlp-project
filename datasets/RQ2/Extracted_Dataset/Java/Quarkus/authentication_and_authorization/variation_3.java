// Variation 3: The "Security-Focused" Developer
// Style: Emphasizes security best practices, explicit DTOs, and dedicated security components.
// Naming: Uses security-centric terminology (e.g., Credential, Principal, JwtProvider).
// Structure: Highly modular with packages for DTOs, security, model, etc.

// --- Configuration (place in src/main/resources/application.properties) ---
/*
quarkus.http.auth.jwt.enabled=true
mp.jwt.verify.publickey.location=META-INF/resources/publicKey.pem
mp.jwt.verify.issuer=https://secure.corp/auth
smallrye.jwt.sign.key.location=META-INF/resources/privateKey.pem
*/

// --- Package: com.secureapp.model ---

package com.secureapp.model;

import java.time.Instant;
import java.util.UUID;

public class UserAccount {
    public UUID id;
    public String email;
    public String passwordHash;
    public UserRole role;
    public boolean isActive;
    public Instant createdAt;
}

public enum UserRole { ADMIN, USER }

public class BlogPost {
    public UUID id;
    public UUID userId;
    public String title;
    public String content;
    public enum PostStatus { DRAFT, PUBLISHED }
    public PostStatus status;
}

// --- Package: com.secureapp.dto ---

package com.secureapp.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class LoginRequest {
    @NotBlank(message = "Email cannot be blank")
    @Email(message = "Email should be valid")
    public String email;

    @NotBlank(message = "Password cannot be blank")
    @Size(min = 8, message = "Password must be at least 8 characters")
    public String password;
}

public class TokenResponse {
    public String accessToken;
    public long expiresIn;
    public String tokenType = "Bearer";

    public TokenResponse(String accessToken, long expiresIn) {
        this.accessToken = accessToken;
        this.expiresIn = expiresIn;
    }
}

// --- Package: com.secureapp.repository ---

package com.secureapp.repository;

import com.secureapp.model.UserAccount;
import com.secureapp.model.UserRole;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class UserAccountRepository {
    private final Map<String, UserAccount> userDb = new ConcurrentHashMap<>();

    public UserAccountRepository() {
        UserAccount admin = new UserAccount();
        admin.id = UUID.randomUUID();
        admin.email = "sysadmin@secure.corp";
        admin.passwordHash = BcryptUtil.bcryptHash("Str0ngP@ssw0rd!");
        admin.role = UserRole.ADMIN;
        admin.isActive = true;
        admin.createdAt = Instant.now();
        userDb.put(admin.email, admin);
    }

    public Optional<UserAccount> findByEmail(String email) {
        return Optional.ofNullable(userDb.get(email));
    }
}

// --- Package: com.secureapp.security ---

package com.secureapp.security;

import com.secureapp.model.UserAccount;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Duration;
import java.util.Collections;

@ApplicationScoped
public class JwtProvider {

    @ConfigProperty(name = "mp.jwt.verify.issuer")
    String issuer;

    private final long DURATION_SECONDS = 3600;

    public String issueToken(UserAccount user) {
        return Jwt.issuer(issuer)
                .upn(user.email)
                .subject(user.id.toString())
                .groups(Collections.singleton(user.role.name()))
                .expiresIn(Duration.ofSeconds(DURATION_SECONDS))
                .sign();
    }
    
    public long getDuration() {
        return DURATION_SECONDS;
    }
}

// --- Package: com.secureapp.service ---

package com.secureapp.service;

import com.secureapp.dto.LoginRequest;
import com.secureapp.dto.TokenResponse;
import com.secureapp.repository.UserAccountRepository;
import com.secureapp.security.JwtProvider;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotAuthorizedException;

@ApplicationScoped
public class AuthenticationService {

    @Inject
    UserAccountRepository userAccountRepo;

    @Inject
    JwtProvider jwtProvider;

    public TokenResponse processLogin(LoginRequest credentials) {
        UserAccount user = userAccountRepo.findByEmail(credentials.email)
                .orElseThrow(() -> new NotAuthorizedException("Invalid credentials"));

        if (!user.isActive) {
            throw new NotAuthorizedException("User account is disabled");
        }

        if (!BcryptUtil.matches(credentials.password, user.passwordHash)) {
            throw new NotAuthorizedException("Invalid credentials");
        }

        String token = jwtProvider.issueToken(user);
        return new TokenResponse(token, jwtProvider.getDuration());
    }
}

// --- Package: com.secureapp.controller ---

package com.secureapp.controller;

import com.secureapp.dto.LoginRequest;
import com.secureapp.dto.TokenResponse;
import com.secureapp.service.AuthenticationService;
import io.quarkus.oidc.client.Tokens;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthController {

    @Inject
    AuthenticationService authService;

    @POST
    @Path("/login")
    public Response login(@Valid LoginRequest loginRequest) {
        try {
            TokenResponse token = authService.processLogin(loginRequest);
            return Response.ok(token).build();
        } catch (NotAuthorizedException e) {
            return Response.status(Response.Status.UNAUTHORIZED).entity(e.getMessage()).build();
        }
    }
}

@Path("/secure")
@Authenticated
public class SecureDataController {

    @Inject
    SecurityIdentity securityIdentity;
    
    @Inject
    @io.quarkus.oidc.client.OidcClient("github") // Named client
    io.smallrye.mutiny.Uni<Tokens> githubTokens;

    @GET
    @Path("/me")
    public Response me() {
        return Response.ok(securityIdentity.getPrincipal().getName()).build();
    }

    @DELETE
    @Path("/posts/{id}")
    @RolesAllowed("ADMIN")
    public Response deletePost(@PathParam("id") String id) {
        // Logic to delete post
        return Response.ok("Post " + id + " deleted by admin: " + securityIdentity.getPrincipal().getName()).build();
    }
    
    @GET
    @Path("/github/token")
    public io.smallrye.mutiny.Uni<String> getGithubToken() {
        // Demonstrates using a named OIDC client to get a token for a 3rd party service
        return githubTokens.onItem().transform(Tokens::getAccessToken);
    }
}