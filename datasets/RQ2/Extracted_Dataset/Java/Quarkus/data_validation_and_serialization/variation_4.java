package com.example.pragmatic;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Valid;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.xml.bind.annotation.XmlRootElement;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 1. VALIDATION GROUPS
interface OnCreate {}
interface OnUpdate {}

// 2. DOMAIN & DTOs (kept in the same file for minimalism)
enum Role { ADMIN, USER }

@XmlRootElement(name = "user")
class UserData {
    // ID is null on creation, not null on update/response
    @Null(groups = OnCreate.class, message = "ID must be null on creation")
    @NotNull(groups = OnUpdate.class, message = "ID is required for updates")
    public UUID id;

    @NotBlank(groups = {OnCreate.class, Default.class}, message = "Email is required")
    @Email(groups = {OnCreate.class, OnUpdate.class, Default.class}, message = "Invalid email format")
    public String email;

    @NotBlank(groups = OnCreate.class, message = "Password is required on creation")
    public String password_hash;

    @NotNull(groups = {OnCreate.class, Default.class}, message = "Role is required")
    @ValidRole(groups = {OnCreate.class, OnUpdate.class, Default.class})
    public Role role;

    public Boolean is_active;
    public Timestamp created_at;
}

// 3. CUSTOM VALIDATOR
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidRole.Validator.class)
@interface ValidRole {
    String message() default "Role is not valid";
    Class<?>[] groups() default {};
    Class<? extends jakarta.validation.Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidRole, Role> {
        @Override
        public boolean isValid(Role value, ConstraintValidatorContext context) {
            // Simple check to ensure role is not null. More complex logic could go here.
            return value != null;
        }
    }
}

// 4. JAX-RS RESOURCE (Service logic is directly inside)
@Path("/v4/users")
@ApplicationScoped
@Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
@Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
public class MinimalUserResource {

    private static final Map<UUID, UserData> userDb = new ConcurrentHashMap<>();

    @POST
    public Response create(
        @Valid @ConvertGroup(from = Default.class, to = OnCreate.class) UserData user
    ) {
        user.id = UUID.randomUUID();
        user.created_at = Timestamp.from(Instant.now());
        user.is_active = true;
        // In a real app, hash the password here.
        userDb.put(user.id, user);
        return Response.status(Response.Status.CREATED).entity(user).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        UserData user = userDb.get(id);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        // Type coercion from path param string to UUID is automatic
        return Response.ok(user).build();
    }
    
    // Mock Post entity for schema completeness
    static class Post {
        public UUID id;
        public UUID user_id;
        public String title;
        public String content;
        public enum Status { DRAFT, PUBLISHED }
        public Status status;
    }
}