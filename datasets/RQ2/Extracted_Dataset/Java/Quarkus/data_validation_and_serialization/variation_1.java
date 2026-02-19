package com.example.classic;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
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
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// 1. DOMAIN MODEL
enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

class User {
    public UUID id;
    public String email;
    public String password_hash;
    public Role role;
    public Boolean is_active;
    public Timestamp created_at;
}

// 2. CUSTOM VALIDATOR
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PhoneNumberValidator.class)
@Documented
@interface PhoneNumber {
    String message() default "Invalid phone number format. Expected format: +[country code][number]";
    Class<?>[] groups() default {};
    Class<? extends jakarta.validation.Payload>[] payload() default {};
}

class PhoneNumberValidator implements ConstraintValidator<PhoneNumber, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true; // Not responsible for null/blank checks
        }
        // Simple validation: starts with '+' and has 10 to 15 digits.
        return value.matches("^\\+[0-9]{10,15}$");
    }
}

// 3. DATA TRANSFER OBJECTS (DTOs)
@XmlRootElement(name = "createUserRequest")
@XmlAccessorType(XmlAccessType.FIELD)
class CreateUserRequest {
    @NotBlank(message = "Email cannot be blank")
    @Email(message = "Email should be valid")
    @XmlElement
    public String email;

    @NotBlank(message = "Password cannot be blank")
    @Size(min = 8, message = "Password must be at least 8 characters long")
    @XmlElement
    public String password;

    @NotNull(message = "Role must be provided")
    @XmlElement
    public Role role;

    @PhoneNumber
    @XmlElement
    public String phone;
}

@XmlRootElement(name = "userResponse")
@XmlAccessorType(XmlAccessType.FIELD)
class UserResponse {
    @XmlElement public UUID id;
    @XmlElement public String email;
    @XmlElement public Role role;
    @XmlElement public Boolean isActive;
    @XmlElement public Timestamp createdAt;

    public static UserResponse fromUser(User user) {
        UserResponse dto = new UserResponse();
        dto.id = user.id;
        dto.email = user.email;
        dto.role = user.role;
        dto.isActive = user.is_active;
        dto.createdAt = user.created_at;
        return dto;
    }
}

// 4. SERVICE LAYER
@ApplicationScoped
class UserService {
    private final Map<UUID, User> userDatabase = new ConcurrentHashMap<>();

    public User createUser(CreateUserRequest request) {
        User newUser = new User();
        newUser.id = UUID.randomUUID();
        newUser.email = request.email;
        newUser.password_hash = "hashed_" + request.password; // Mock hashing
        newUser.role = request.role;
        newUser.is_active = true;
        newUser.created_at = Timestamp.from(Instant.now());
        userDatabase.put(newUser.id, newUser);
        return newUser;
    }

    public User findUserById(UUID id) {
        return userDatabase.get(id);
    }
}

// 5. ERROR HANDLING
@XmlRootElement(name = "errorResponse")
@XmlAccessorType(XmlAccessType.FIELD)
class ErrorResponse {
    @XmlElement public String message;
    @XmlElement public List<ValidationError> details;
}

@XmlRootElement(name = "validationError")
@XmlAccessorType(XmlAccessType.FIELD)
class ValidationError {
    @XmlElement public String field;
    @XmlElement public String error;
}

@Provider
class ValidationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {
    @Override
    public Response toResponse(ConstraintViolationException exception) {
        ErrorResponse errorResponse = new ErrorResponse();
        errorResponse.message = "Validation Failed";
        errorResponse.details = exception.getConstraintViolations().stream()
                .map(violation -> {
                    ValidationError error = new ValidationError();
                    error.field = violation.getPropertyPath().toString();
                    error.error = violation.getMessage();
                    return error;
                })
                .collect(Collectors.toList());

        return Response.status(Response.Status.BAD_REQUEST)
                .entity(errorResponse)
                .build();
    }
}

// 6. JAX-RS RESOURCE
@Path("/v1/users")
@ApplicationScoped
public class UserResource {

    @Inject
    UserService userService;

    @POST
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public Response createUser(@Valid CreateUserRequest request) {
        User newUser = userService.createUser(request);
        UserResponse responseDto = UserResponse.fromUser(newUser);
        return Response.status(Response.Status.CREATED).entity(responseDto).build();
    }

    @GET
    @Path("/{id}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public Response getUserById(@PathParam("id") UUID id) {
        User user = userService.findUserById(id);
        if (user != null) {
            return Response.ok(UserResponse.fromUser(user)).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }
}