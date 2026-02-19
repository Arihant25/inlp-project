// Variation 4: The All-in-One/Microservice Developer
// Style: Co-locates related classes (DTOs, validators) as inner classes within the controller.
//        This promotes high cohesion for a single feature, common in smaller microservices.
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

package com.example.validation.v4;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import jakarta.validation.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.lang.annotation.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@SpringBootApplication
public class MicroserviceApp {
    public static void main(String[] args) {
        SpringApplication.run(MicroserviceApp.class, args);
    }
}

// --- Domain Enums (could be in their own files) ---
enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

@RestController
@RequestMapping("/api/v4/users")
public class UserResource {

    // --- Custom Validator (Inner Annotation and Class) ---
    @Documented
    @Constraint(validatedBy = PasswordComplexityValidator.class)
    @Target({ElementType.METHOD, ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface PasswordComplexity {
        String message() default "Password does not meet complexity requirements";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static class PasswordComplexityValidator implements ConstraintValidator<PasswordComplexity, String> {
        @Override
        public boolean isValid(String password, ConstraintValidatorContext context) {
            if (password == null) return false;
            // At least 8 chars, one uppercase, one number
            return password.length() >= 8 && password.matches(".*[A-Z].*") && password.matches(".*[0-9].*");
        }
    }

    // --- DTOs (Inner Static Classes) ---
    @JacksonXmlRootElement(localName = "UserRegistration")
    public static class UserRegistrationPayload {
        @NotBlank @Email
        @JsonProperty("email")
        private String email;

        @NotBlank @PasswordComplexity
        @JsonProperty("password")
        private String password;

        @NotNull
        @JsonProperty("role")
        private Role role; // Type coercion from String

        // Getters and Setters
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public Role getRole() { return role; }
        public void setRole(Role role) { this.role = role; }
    }

    @JacksonXmlRootElement(localName = "User")
    public static class UserView {
        @JsonProperty("id")
        private UUID id;
        @JsonProperty("email")
        private String email;
        @JsonIgnore // Hide password hash from serialization
        private String passwordHash;
        @JsonProperty("role")
        private Role role;
        @JsonProperty("active")
        private boolean isActive;
        @JsonProperty("created_at")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd-MM-yyyy hh:mm:ss")
        private Timestamp createdAt;

        public static UserView create(UserRegistrationPayload payload) {
            UserView view = new UserView();
            view.id = UUID.randomUUID();
            view.email = payload.getEmail();
            view.passwordHash = "hashed_" + payload.getPassword(); // Mock hashing
            view.role = payload.getRole();
            view.isActive = true;
            view.createdAt = Timestamp.from(Instant.now());
            return view;
        }
        // Getters and Setters
        public UUID getId() { return id; }
        public String getEmail() { return email; }
        public String getPasswordHash() { return passwordHash; }
        public Role getRole() { return role; }
        public boolean isActive() { return isActive; }
        public Timestamp getCreatedAt() { return createdAt; }
    }

    // --- API Endpoint ---
    @PostMapping(
        consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE},
        produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE}
    )
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@Valid @RequestBody UserRegistrationPayload payload) {
        System.out.println("Registering user: " + payload.getEmail());
        return UserView.create(payload);
    }
}

// --- Centralized Exception Handler ---
@RestControllerAdvice
class GlobalApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more fields are invalid.");
        problemDetail.setTitle("Invalid Input");
        
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.toList());
        
        problemDetail.setProperty("errors", errors);
        
        return problemDetail;
    }
}