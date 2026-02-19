// Variation 3: The Functional/Reactive-Inspired Developer
// Style: Favors explicit ResponseEntity, uses Streams for transformations, and groups code by feature.
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

package com.example.validation.v3;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.lang.annotation.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// --- Main Application Class ---
@SpringBootApplication
public class FunctionalApp {
    public static void main(String[] args) {
        SpringApplication.run(FunctionalApp.class, args);
    }
}

// --- Domain Models ---
package com.example.validation.v3.domain;

import java.sql.Timestamp;
import java.util.UUID;

enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

class User {
    UUID id;
    String email;
    String passwordHash;
    Role role;
    Boolean isActive;
    Timestamp createdAt;
}

// --- Custom Validator ---
package com.example.validation.v3.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = TitleCaseValidator.class)
@Documented
public @interface IsTitleCase {
    String message() default "Value must be in Title Case.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

class TitleCaseValidator implements ConstraintValidator<IsTitleCase, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true; // Not the responsibility of this validator
        }
        for (String word : value.split(" ")) {
            if (word.isEmpty() || !Character.isUpperCase(word.charAt(0))) {
                return false;
            }
        }
        return true;
    }
}

// --- API Payloads and Views ---
package com.example.validation.v3.api;

import com.example.validation.v3.domain.Status;
import com.example.validation.v3.validation.IsTitleCase;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.util.UUID;

@JacksonXmlRootElement(localName = "PostPayload")
class PostPayload {
    @NotNull(message = "User ID is mandatory")
    @JsonProperty("author_id")
    public UUID userId;

    @NotBlank(message = "Title cannot be blank")
    @Size(min = 5, max = 100)
    @IsTitleCase(message = "Title must be in Title Case")
    @JsonProperty("title")
    public String title;

    @NotBlank
    @JsonProperty("body")
    public String content;

    @NotNull
    @JsonProperty("status")
    public Status status; // Type coercion from String to Enum
}

@JacksonXmlRootElement(localName = "PostView")
class PostView {
    @JsonProperty("post_id")
    public UUID id;
    @JsonProperty("author_id")
    public UUID userId;
    @JsonProperty("title")
    public String title;
    @JsonProperty("status")
    public Status status;
    @JsonProperty("published_at")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT) // Serialize timestamp as epoch milliseconds
    public Timestamp publishedAt;

    public static PostView from(PostPayload payload) {
        var view = new PostView();
        view.id = UUID.randomUUID();
        view.userId = payload.userId;
        view.title = payload.title;
        view.status = payload.status;
        view.publishedAt = Timestamp.from(Instant.now());
        return view;
    }
}

// --- Type Coercion/Conversion Example ---
@Component
class StringToStatusConverter implements Converter<String, Status> {
    @Override
    public Status convert(String source) {
        try {
            return Status.valueOf(source.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Status.DRAFT; // Default value on conversion error
        }
    }
}

// --- Controller and Error Handling ---
@RestController
@RequestMapping("/api/v3")
class PostController {

    @PostMapping(
        path = "/posts",
        consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE},
        produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE}
    )
    public ResponseEntity<PostView> createPost(@Valid @RequestBody PostPayload payload) {
        // Functional-style mapping from payload to view
        PostView view = PostView.from(payload);
        System.out.println("Created post with title: " + view.title);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }
}

@RestControllerAdvice
class ValidationAdvice {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationFailures(MethodArgumentNotValidException ex) {
        Map<String, String> errors = ex.getBindingResult().getAllErrors().stream()
            .map(error -> (FieldError) error)
            .collect(Collectors.toMap(
                FieldError::getField,
                fieldError -> fieldError.getDefaultMessage() == null ? "Invalid value" : fieldError.getDefaultMessage()
            ));

        Map<String, Object> errorResponse = Map.of(
            "message", "Request contains invalid data",
            "details", errors
        );

        return ResponseEntity.badRequest().body(errorResponse);
    }
}