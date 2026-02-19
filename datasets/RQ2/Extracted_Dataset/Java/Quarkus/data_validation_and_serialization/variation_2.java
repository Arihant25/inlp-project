package com.example.modern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 1. DOMAIN MODEL (using Records for immutability and conciseness)
enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

record User(
    UUID id,
    String email,
    String password_hash,
    Role role,
    Boolean is_active,
    Timestamp created_at
) {}

record Post(
    UUID id,
    UUID user_id,
    String title,
    String content,
    Status status
) {}

// 2. CUSTOM VALIDATOR
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StrongPassword.Validator.class)
@interface StrongPassword {
    String message() default "Password must be at least 8 characters long and contain a digit, a lowercase, an uppercase, and a special character.";
    Class<?>[] groups() default {};
    Class<? extends jakarta.validation.Payload>[] payload() default {};

    class Validator implements ConstraintValidator<StrongPassword, String> {
        private static final String PASSWORD_PATTERN = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=])(?=\\S+$).{8,}$";
        
        @Override
        public boolean isValid(String password, ConstraintValidatorContext context) {
            if (password == null) return true; // Let @NotNull handle this
            return password.matches(PASSWORD_PATTERN);
        }
    }
}

// 3. DTOs (using Records) and JAX-RS RESOURCE
@Path("/v2/users")
@ApplicationScoped
public class UserApi {

    // In-memory store for demonstration
    private static final Map<UUID, User> userStore = new ConcurrentHashMap<>();

    // DTO for user creation
    @XmlRootElement // For XML support
    public record CreateUserDto(
        @NotBlank @Email String email,
        @NotBlank @StrongPassword String password,
        @NotNull Role role,
        @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "Phone number must be in E.164 format") String phone
    ) {}

    // DTO for user response
    @XmlRootElement // For XML support
    public record UserView(
        UUID id,
        String email,
        Role role,
        boolean isActive
    ) {}

    @POST
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public Response create(@Valid CreateUserDto dto) {
        var user = new User(
            UUID.randomUUID(),
            dto.email(),
            "hashed:" + dto.password(), // Mock hashing
            dto.role(),
            true,
            Timestamp.from(Instant.now())
        );
        userStore.put(user.id(), user);
        
        var view = new UserView(user.id(), user.email(), user.role(), user.is_active());
        return Response.status(Response.Status.CREATED).entity(view).build();
    }

    @GET
    @Path("/{userId}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public Response getById(@PathParam("userId") UUID userId) {
        // Type coercion from String to UUID is handled by JAX-RS
        var user = userStore.get(userId);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        var view = new UserView(user.id(), user.email(), user.role(), user.is_active());
        return Response.ok(view).build();
    }
}