// Variation 2: The Pragmatic/Modern Developer
// Style: Uses Java Records for DTOs, concise naming, and a flatter package structure.
// Assumes the following dependencies in pom.xml:
// <dependency>
//     <groupId>org.springframework.boot</groupId>
//     <artifactId>spring-boot-starter-web</artifactId>
// </dependency>
// <dependency>
//     <groupId>org.springframework.boot</groupId>
//     <artifactId>spring-boot-starter-validation</artifactId>
// </dependency>
// <dependency>
//     <groupId>com.fasterxml.jackson.dataformat</groupId>
//     <artifactId>jackson-dataformat-xml</artifactId>
// </dependency>

package com.example.validation.v2;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.lang.annotation.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// --- Main Application Class ---
@SpringBootApplication
public class ModernApp {
    public static void main(String[] args) {
        SpringApplication.run(ModernApp.class, args);
    }
}

// --- Shared Domain Enums ---
package com.example.validation.v2.domain;

enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

// --- Custom Validator (in a shared package) ---
package com.example.validation.v2.shared;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StrongPassword.PasswordValidator.class)
@Documented
public @interface StrongPassword {
    String message() default "Password must be at least 8 characters long and contain at least one digit, one lowercase letter, one uppercase letter, and one special character.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class PasswordValidator implements ConstraintValidator<StrongPassword, String> {
        private static final String PASSWORD_PATTERN = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=!])(?=\\S+$).{8,}$";

        @Override
        public boolean isValid(String password, ConstraintValidatorContext context) {
            return password != null && password.matches(PASSWORD_PATTERN);
        }
    }
}

// --- DTOs using Java Records ---
package com.example.validation.v2.dto;

import com.example.validation.v2.domain.Role;
import com.example.validation.v2.shared.StrongPassword;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.sql.Timestamp;
import java.util.UUID;

@JacksonXmlRootElement(localName = "CreateUserRequest")
record CreateUserRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @JsonProperty("email")
    String email,

    @NotBlank(message = "Password is required")
    @StrongPassword
    @JsonProperty("password")
    String password,

    @NotNull(message = "Role is required")
    @JsonProperty("role")
    Role role
) {}

@JacksonXmlRootElement(localName = "UserView")
record UserView(
    @JsonProperty("id")
    UUID id,

    @JsonProperty("email")
    String email,

    @JsonProperty("role")
    Role role,

    @JsonProperty("active")
    boolean isActive,

    @JsonProperty("joined_at")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    Timestamp joinedAt
) {}

// --- Global Error Handler ---
package com.example.validation.v2.config;

import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiErrorHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, Object> onMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        Map<String, String> errors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (existing, replacement) -> existing));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", HttpStatus.UNPROCESSABLE_ENTITY.value());
        body.put("error", "Validation Error");
        body.put("field_errors", errors);

        return body;
    }
}

// --- API Controller ---
package com.example.validation.v2.api;

import com.example.validation.v2.dto.CreateUserRequest;
import com.example.validation.v2.dto.UserView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2")
public class UserController {

    @PostMapping(
        path = "/users",
        consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE},
        produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE}
    )
    @ResponseStatus(HttpStatus.CREATED)
    public UserView createUser(@Valid @RequestBody CreateUserRequest request) {
        // Logic to create user...
        System.out.println("Creating user with email: " + request.email());

        // Return a new User view
        return new UserView(
            UUID.randomUUID(),
            request.email(),
            request.role(),
            true,
            Timestamp.from(Instant.now())
        );
    }
}