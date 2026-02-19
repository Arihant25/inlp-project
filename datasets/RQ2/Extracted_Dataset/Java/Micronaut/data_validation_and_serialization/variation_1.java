package com.example.variation1;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
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
import jakarta.validation.constraints.Size;

import javax.validation.Valid;
import java.lang.annotation.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// --- Custom Validator Definition ---

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Constraint(validatedBy = PhoneNumberValidator.class)
public @interface ValidPhoneNumber {
    String message() default "Invalid phone number format. Expected format: +[country code][number]";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

class PhoneNumberValidator implements ConstraintValidator<ValidPhoneNumber, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true; // Let @NotBlank handle this
        }
        // Simple regex for international phone numbers
        return value.matches("^\\+[1-9]\\d{1,14}$");
    }
}

// --- Domain Enums ---

enum Role {
    ADMIN, USER
}

enum PostStatus {
    DRAFT, PUBLISHED
}

// --- Data Transfer Objects (DTOs) ---

@Introspected
class CreateUserRequestDto {
    @NotBlank(message = "Email cannot be blank")
    @Email(message = "Must be a valid email format")
    private String email;

    @NotBlank(message = "Password cannot be blank")
    @Size(min = 8, message = "Password must be at least 8 characters long")
    private String password;

    @NotNull(message = "Role must be provided")
    private Role role;

    @ValidPhoneNumber
    private String phone;

    // Standard Getters and Setters
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
}

@Introspected
class UserResponseDto {
    private UUID id;
    private String email;
    private Role role;
    private boolean isActive;
    private Timestamp createdAt;

    public UserResponseDto(UUID id, String email, Role role, boolean isActive, Timestamp createdAt) {
        this.id = id;
        this.email = email;
        this.role = role;
        this.isActive = isActive;
        this.createdAt = createdAt;
    }

    // Standard Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}

@Introspected
class CreatePostRequestDto {
    @NotNull(message = "User ID is required")
    private UUID userId;

    @NotBlank(message = "Title cannot be blank")
    @Size(max = 255)
    private String title;

    private String content;

    @NotNull
    private PostStatus status;

    // Standard Getters and Setters
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public PostStatus getStatus() { return status; }
    public void setStatus(PostStatus status) { this.status = status; }
}

// --- Mock Data Store ---
@Singleton
class MockDataStore {
    public final Map<UUID, UserResponseDto> users = new ConcurrentHashMap<>();
    public final Map<UUID, CreatePostRequestDto> posts = new ConcurrentHashMap<>();
}


// --- Controller ---

@Validated
@Controller("/v1/enterprise")
public class UserPostController {

    private final MockDataStore dataStore;

    public UserPostController(MockDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Post(uri = "/users", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    public HttpResponse<UserResponseDto> createUser(@Body @Valid CreateUserRequestDto createUserRequest) {
        // In a real app, you'd hash the password and save to a DB
        UserResponseDto newUser = new UserResponseDto(
            UUID.randomUUID(),
            createUserRequest.getEmail(),
            createUserRequest.getRole(),
            true,
            Timestamp.from(Instant.now())
        );
        dataStore.users.put(newUser.getId(), newUser);
        return HttpResponse.created(newUser);
    }

    @Post(uri = "/posts", consumes = MediaType.APPLICATION_XML, produces = MediaType.APPLICATION_XML)
    public HttpResponse<CreatePostRequestDto> createPost(@Body @Valid CreatePostRequestDto createPostRequest) {
        if (!dataStore.users.containsKey(createPostRequest.getUserId())) {
            return HttpResponse.badRequest(createPostRequest); // User not found
        }
        UUID postId = UUID.randomUUID();
        dataStore.posts.put(postId, createPostRequest);
        // In a real app, you'd return a full PostResponseDto with the ID
        return HttpResponse.created(createPostRequest);
    }

    @Get(uri = "/users/{id}", produces = MediaType.APPLICATION_JSON)
    public HttpResponse<UserResponseDto> getUserById(UUID id) {
        UserResponseDto user = dataStore.users.get(id);
        if (user != null) {
            return HttpResponse.ok(user);
        } else {
            return HttpResponse.notFound();
        }
    }
}