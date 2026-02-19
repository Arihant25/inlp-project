// Variation 1: The Classic OOP/Enterprise Developer
// Style: Highly structured, verbose naming, clear separation of concerns in distinct packages.
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

package com.example.validation.v1;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// --- Main Application Class ---
@SpringBootApplication
public class ClassicApp {
    public static void main(String[] args) {
        SpringApplication.run(ClassicApp.class, args);
    }
}

// --- Model Layer (Entities) ---
package com.example.validation.v1.model;

import java.sql.Timestamp;
import java.util.UUID;

enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

class User {
    private UUID id;
    private String email;
    private String passwordHash;
    private Role role;
    private Boolean isActive;
    private Timestamp createdAt;
    // Getters and Setters
}

class Post {
    private UUID id;
    private UUID userId;
    private String title;
    private String content;
    private Status status;
    // Getters and Setters
}


// --- Custom Validator ---
package com.example.validation.v1.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PhoneNumberValidator.class)
@Documented
public @interface ValidPhoneNumber {
    String message() default "Invalid phone number format. Expected format: +[country code][number]";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

class PhoneNumberValidator implements ConstraintValidator<ValidPhoneNumber, String> {
    @Override
    public boolean isValid(String phoneField, ConstraintValidatorContext context) {
        if (phoneField == null || phoneField.isBlank()) {
            return true; // Optional field
        }
        // Simple regex for international phone numbers
        return phoneField.matches("^\\+(?:[0-9] ?){6,14}[0-9]$");
    }
}

// --- DTO Layer (Data Transfer Objects) ---
package com.example.validation.v1.dto;

import com.example.validation.v1.model.Role;
import com.example.validation.v1.validation.ValidPhoneNumber;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.sql.Timestamp;
import java.util.UUID;

@JacksonXmlRootElement(localName = "UserCreationRequest")
public class UserCreationRequestDTO {

    @NotEmpty(message = "Email cannot be empty.")
    @Email(message = "Email should be valid.")
    @JsonProperty("user_email")
    private String email;

    @NotEmpty(message = "Password cannot be empty.")
    @Size(min = 8, max = 30, message = "Password must be between 8 and 30 characters.")
    @JsonProperty("user_password")
    private String password;

    @NotNull(message = "Role must be specified.")
    @JsonProperty("user_role")
    private Role role;

    @ValidPhoneNumber
    @JsonProperty("phone_number")
    private String phoneNumber;

    // Getters and Setters
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@JacksonXmlRootElement(localName = "UserResponse")
public class UserResponseDTO {
    @JsonProperty("user_id")
    private UUID id;
    @JsonProperty("email_address")
    private String email;
    @JsonProperty("role")
    private Role role;
    @JsonProperty("is_active")
    private Boolean isActive;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    @JsonProperty("creation_date")
    private Timestamp createdAt;

    public UserResponseDTO(UUID id, String email, Role role, Boolean isActive, Timestamp createdAt) {
        this.id = id;
        this.email = email;
        this.role = role;
        this.isActive = isActive;
        this.createdAt = createdAt;
    }
    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}

// --- Exception Handling Layer ---
package com.example.validation.v1.exception;

import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Map<String, Object> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", "error");
        response.put("message", "Input validation failed");
        response.put("errors", errors);
        return response;
    }
}

// --- Controller Layer ---
package com.example.validation.v1.controller;

import com.example.validation.v1.dto.UserCreationRequestDTO;
import com.example.validation.v1.dto.UserResponseDTO;
import com.example.validation.v1.model.Role;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserManagementController {

    @PostMapping(
        consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE},
        produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE}
    )
    public ResponseEntity<UserResponseDTO> registerUser(@Valid @RequestBody UserCreationRequestDTO userCreationRequest) {
        // In a real app, you would hash the password and save the user to a database.
        // Here we just mock the response.
        System.out.println("Received user for creation: " + userCreationRequest.getEmail());

        UserResponseDTO response = new UserResponseDTO(
            UUID.randomUUID(),
            userCreationRequest.getEmail(),
            userCreationRequest.getRole(),
            true,
            Timestamp.from(Instant.now())
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}