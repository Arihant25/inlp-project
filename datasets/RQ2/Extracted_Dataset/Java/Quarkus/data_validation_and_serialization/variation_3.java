package com.example.functional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// 1. DOMAIN MODEL (Immutable Records)
enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

record User(UUID id, String email, String password_hash, Role role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, Status status) {}

// 2. CUSTOM VALIDATOR
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NoAdminOnCreateValidator.class)
@interface NoAdminOnCreate {
    String message() default "Cannot create a user with ADMIN role via this endpoint.";
    Class<?>[] groups() default {};
    Class<? extends jakarta.validation.Payload>[] payload() default {};
}

class NoAdminOnCreateValidator implements ConstraintValidator<NoAdminOnCreate, Role> {
    @Override
    public boolean isValid(Role role, ConstraintValidatorContext context) {
        return role != Role.ADMIN;
    }
}

// 3. DTOs & MAPPERS
record UserCreationRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 10) String password,
    @NotNull @NoAdminOnCreate Role role
) {}

@XmlRootElement // For XML support
record UserResponse(UUID id, String email, Role role, Timestamp createdAt) {}

final class UserMapper {
    private UserMapper() {} // Prevent instantiation

    public static User fromDto(UserCreationRequest dto) {
        return new User(
            UUID.randomUUID(),
            dto.email(),
            "hashed_pw_" + dto.password().hashCode(),
            dto.role(),
            true,
            Timestamp.from(Instant.now())
        );
    }

    public static UserResponse toDto(User user) {
        return new UserResponse(user.id(), user.email(), user.role(), user.created_at());
    }
}

// 4. DATA ACCESS / SERVICE LAYER
@ApplicationScoped
class UserRepository {
    private final Map<UUID, User> db = new ConcurrentHashMap<>();

    public User save(User user) {
        db.put(user.id(), user);
        return user;
    }

    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(db.get(id));
    }
}

// 5. CUSTOM EXCEPTION FORMATTING
@Provider
class FunctionalValidationMapper implements ExceptionMapper<ConstraintViolationException> {
    @Override
    public Response toResponse(ConstraintViolationException exception) {
        Map<String, String> errors = exception.getConstraintViolations().stream()
            .collect(Collectors.toMap(
                v -> v.getPropertyPath().toString(),
                v -> v.getMessage()
            ));
        
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(Map.of("validationErrors", errors))
            .type(MediaType.APPLICATION_JSON_TYPE) // Force JSON for error response
            .build();
    }
}

// 6. JAX-RS RESOURCE
@Path("/v3/users")
@Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
@Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
public class UserResource {

    @Inject
    UserRepository userRepository;

    @POST
    public Response processUserCreation(@Valid UserCreationRequest request) {
        return Optional.of(request)
            .map(UserMapper::fromDto)
            .map(userRepository::save)
            .map(UserMapper::toDto)
            .map(dto -> Response.status(Response.Status.CREATED).entity(dto).build())
            .orElse(Response.serverError().build()); // Should not happen
    }

    @GET
    @Path("/{id}")
    public Response findUser(@PathParam("id") UUID id) {
        return userRepository.findById(id)
            .map(UserMapper::toDto)
            .map(dto -> Response.ok(dto).build())
            .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }
}