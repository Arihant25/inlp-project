package com.example.variation3;

import io.micronaut.core.annotation.Creator;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.convert.TypeConverter;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.validation.Validated;
import jakarta.inject.Singleton;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import javax.validation.Valid;
import java.lang.annotation.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// --- Domain Enums ---
enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

// --- Value Object and Custom Type Converter ---
@Introspected
class PhoneNumber {
    private final String value;
    @Creator
    public PhoneNumber(String value) {
        if (value == null || !value.matches("^\\+[1-9]\\d{1,14}$")) {
            throw new IllegalArgumentException("Invalid phone number format");
        }
        this.value = value;
    }
    public String getValue() { return value; }
    @Override public String toString() { return value; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PhoneNumber that = (PhoneNumber) o;
        return Objects.equals(value, that.value);
    }
    @Override public int hashCode() { return Objects.hash(value); }
}

@Singleton
class PhoneNumberConverter implements TypeConverter<String, PhoneNumber> {
    @Override
    public Optional<PhoneNumber> convert(String object, Class<PhoneNumber> targetType, io.micronaut.core.convert.ConversionContext context) {
        try {
            return Optional.of(new PhoneNumber(object));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

// --- Custom Validator with Dependency Injection ---
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Constraint(validatedBy = UniqueEmailValidator.class)
@interface UniqueEmail {
    String message() default "Email address is already in use";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

@Singleton
class UniqueEmailValidator implements ConstraintValidator<UniqueEmail, String> {
    private final UserRepository userRepository;

    public UniqueEmailValidator(UserRepository userRepository) { // DI in action
        this.userRepository = userRepository;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) return true;
        return userRepository.findByEmail(value).isEmpty();
    }
}

// --- Mock Repository for DI ---
interface UserRepository {
    Optional<UserOutput> findByEmail(String email);
    UserOutput save(String email, String passwordHash, Role role);
}

@Singleton
class InMemoryUserRepository implements UserRepository {
    private final Map<String, UserOutput> usersByEmail = new ConcurrentHashMap<>();
    @Override
    public Optional<UserOutput> findByEmail(String email) {
        return Optional.ofNullable(usersByEmail.get(email));
    }
    @Override
    public UserOutput save(String email, String passwordHash, Role role) {
        var user = new UserOutput(UUID.randomUUID(), email, role, true, Timestamp.from(Instant.now()));
        usersByEmail.put(email, user);
        return user;
    }
}

// --- DTOs (using Records) ---
@Introspected
record UserInput(
    @UniqueEmail @NotBlank @Email String email,
    @NotBlank String password,
    @NotNull Role role
) {}

@Introspected
record UserOutput(UUID id, String email, Role role, boolean isActive, Timestamp createdAt) {}

// --- Controller with explicit HttpResponse ---
@Validated
@Controller("/v3/functional")
public class ResourceEndpoint {

    private final UserRepository userRepository;

    public ResourceEndpoint(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Post("/users")
    public HttpResponse<UserOutput> createUser(@Body @Valid UserInput input) {
        // Password hashing would happen here
        String passwordHash = "hashed_" + input.password();
        UserOutput savedUser = userRepository.save(input.email(), passwordHash, input.role());
        return HttpResponse.created(savedUser);
    }

    @Get("/users/by-email")
    public HttpResponse<UserOutput> getUserByEmail(@QueryValue String email) {
        return userRepository.findByEmail(email)
            .map(HttpResponse::ok)
            .orElse(HttpResponse.notFound());
    }
}