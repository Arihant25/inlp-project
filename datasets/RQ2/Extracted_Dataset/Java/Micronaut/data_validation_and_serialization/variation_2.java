package com.example.variation2;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.validation.Validated;
import jakarta.inject.Singleton;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import javax.validation.Valid;
import java.lang.annotation.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// --- Custom Validator Definition ---

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Constraint(validatedBy = PhoneNumberConstraintValidator.class)
@interface PhoneNumberConstraint {
    String message() default "Invalid phone number";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

class PhoneNumberConstraintValidator implements ConstraintValidator<PhoneNumberConstraint, String> {
    private static final String PHONE_REGEX = "^\\+[1-9]\\d{1,14}$";
    @Override
    public boolean isValid(String phoneField, ConstraintValidatorContext cxt) {
        return phoneField == null || phoneField.matches(PHONE_REGEX);
    }
}

// --- Domain Enums ---

enum UserRole { ADMIN, USER }
enum PublicationStatus { DRAFT, PUBLISHED }

// --- DTOs using Java Records ---

@Introspected
record UserCreationRequest(
    @NotBlank @Email String email,
    @Size(min = 8) String password,
    @NotNull UserRole role,
    @PhoneNumberConstraint String phone
) {}

@Introspected
record UserRepresentation(
    UUID id,
    String email,
    UserRole role,
    boolean isActive,
    Timestamp createdAt
) {}

@Introspected
record PostCreationRequest(
    @NotNull UUID userId,
    @NotBlank String title,
    String content,
    @NotNull PublicationStatus status
) {}

@Introspected
record ApiErrorResponse(String message, Map<String, List<String>> details) {}

// --- Custom Exception Handler for Formatted Error Messages ---

@Singleton
class ValidationExceptionHandler implements ExceptionHandler<ConstraintViolationException, HttpResponse<ApiErrorResponse>> {
    @Override
    public HttpResponse<ApiErrorResponse> handle(HttpRequest request, ConstraintViolationException exception) {
        Map<String, List<String>> errors = exception.getConstraintViolations().stream()
            .collect(Collectors.groupingBy(
                cv -> cv.getPropertyPath().toString(),
                Collectors.mapping(cv -> cv.getMessage(), Collectors.toList())
            ));

        var errorResponse = new ApiErrorResponse("Validation Failed", errors);
        return HttpResponse.badRequest(errorResponse);
    }
}

// --- Controller using Records ---

@Validated
@Controller("/v2/modern")
public class ApiController {

    private static final Map<UUID, UserRepresentation> userDb = new ConcurrentHashMap<>();
    private static final Map<UUID, PostCreationRequest> postDb = new ConcurrentHashMap<>();

    @Post(uri = "/users", consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<UserRepresentation> registerUser(@Body @Valid UserCreationRequest req) {
        var user = new UserRepresentation(
            UUID.randomUUID(),
            req.email(),
            req.role(),
            true,
            Timestamp.from(Instant.now())
        );
        userDb.put(user.id(), user);
        return HttpResponse.status(HttpStatus.CREATED).body(user);
    }

    @Get("/users/{id}")
    public HttpResponse<UserRepresentation> findUser(UUID id) {
        return HttpResponse.ok(userDb.get(id));
    }

    @Post(uri = "/posts", consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<String> publishPost(@Body @Valid PostCreationRequest postReq) {
        if (!userDb.containsKey(postReq.userId())) {
            return HttpResponse.badRequest("Invalid user ID provided.");
        }
        UUID postId = UUID.randomUUID();
        postDb.put(postId, postReq);
        return HttpResponse.created("Post " + postId + " created successfully.");
    }
}