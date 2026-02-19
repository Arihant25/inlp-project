package com.example.restapipattern.v1;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// --- Main Application Class ---
@SpringBootApplication
public class ClassicApp {
    public static void main(String[] args) {
        SpringApplication.run(ClassicApp.class, args);
    }
}

// --- Domain Model ---
enum Role {
    ADMIN, USER
}

class User {
    private UUID id;
    private String email;
    private String passwordHash;
    private Role role;
    private boolean isActive;
    private Timestamp createdAt;

    // Constructors, Getters, Setters
    public User() {}

    public User(String email, String passwordHash, Role role) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.isActive = true;
        this.createdAt = Timestamp.from(Instant.now());
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}

// --- Data Transfer Objects (DTOs) ---
class CreateUserRequest {
    @NotBlank @Email
    private String email;
    @NotBlank @Size(min = 8)
    private String password;
    @NotNull
    private Role role;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
}

class UpdateUserRequest {
    @Email
    private String email;
    private Role role;
    private Boolean isActive;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }
}

class UserResponse {
    private UUID id;
    private String email;
    private Role role;
    private boolean isActive;
    private Timestamp createdAt;

    public static UserResponse fromEntity(User user) {
        UserResponse dto = new UserResponse();
        dto.setId(user.getId());
        dto.setEmail(user.getEmail());
        dto.setRole(user.getRole());
        dto.setActive(user.isActive());
        dto.setCreatedAt(user.getCreatedAt());
        return dto;
    }
    
    // Getters and Setters
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

// --- Exception Handling ---
class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}

@ControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Object> handleResourceNotFound(ResourceNotFoundException ex) {
        return new ResponseEntity<>(Map.of("error", ex.getMessage()), HttpStatus.NOT_FOUND);
    }
}

// --- Repository Layer ---
interface UserRepository {
    User save(User user);
    Optional<User> findById(UUID id);
    Page<User> findAll(Pageable pageable);
    Page<User> search(String email, Boolean isActive, Pageable pageable);
    void deleteById(UUID id);
    boolean existsByEmail(String email);
}

@Repository
class InMemoryUserRepository implements UserRepository {
    private final Map<UUID, User> database = new ConcurrentHashMap<>();

    public InMemoryUserRepository() {
        // Mock data
        User admin = new User("admin@example.com", "hashed_password_1", Role.ADMIN);
        User user1 = new User("user1@example.com", "hashed_password_2", Role.USER);
        User user2 = new User("user2@example.com", "hashed_password_3", Role.USER);
        user2.setActive(false);
        database.put(admin.getId(), admin);
        database.put(user1.getId(), user1);
        database.put(user2.getId(), user2);
    }

    @Override
    public User save(User user) {
        if (user.getId() == null) {
            user.setId(UUID.randomUUID());
            user.setCreatedAt(Timestamp.from(Instant.now()));
        }
        database.put(user.getId(), user);
        return user;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(database.get(id));
    }

    @Override
    public Page<User> findAll(Pageable pageable) {
        List<User> users = database.values().stream().collect(Collectors.toList());
        return getPage(users, pageable);
    }

    @Override
    public Page<User> search(String email, Boolean isActive, Pageable pageable) {
        List<User> filteredUsers = database.values().stream()
            .filter(user -> email == null || user.getEmail().toLowerCase().contains(email.toLowerCase()))
            .filter(user -> isActive == null || user.isActive() == isActive)
            .collect(Collectors.toList());
        return getPage(filteredUsers, pageable);
    }
    
    private Page<User> getPage(List<User> users, Pageable pageable) {
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), users.size());
        if (start > users.size()) {
            return new PageImpl<>(List.of(), pageable, users.size());
        }
        return new PageImpl<>(users.subList(start, end), pageable, users.size());
    }

    @Override
    public void deleteById(UUID id) {
        database.remove(id);
    }

    @Override
    public boolean existsByEmail(String email) {
        return database.values().stream().anyMatch(user -> user.getEmail().equalsIgnoreCase(email));
    }
}

// --- Service Layer ---
interface UserService {
    UserResponse createUser(CreateUserRequest request);
    UserResponse getUserById(UUID id);
    Page<UserResponse> listUsers(String email, Boolean isActive, Pageable pageable);
    UserResponse updateUser(UUID id, UpdateUserRequest request);
    void deleteUser(UUID id);
}

@Service
class UserServiceImpl implements UserService {
    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email is already in use.");
        }
        // In a real app, hash the password here
        String passwordHash = "hashed_" + request.getPassword();
        User newUser = new User(request.getEmail(), passwordHash, request.getRole());
        User savedUser = userRepository.save(newUser);
        return UserResponse.fromEntity(savedUser);
    }

    @Override
    public UserResponse getUserById(UUID id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        return UserResponse.fromEntity(user);
    }

    @Override
    public Page<UserResponse> listUsers(String email, Boolean isActive, Pageable pageable) {
        Page<User> userPage = userRepository.search(email, isActive, pageable);
        return userPage.map(UserResponse::fromEntity);
    }

    @Override
    public UserResponse updateUser(UUID id, UpdateUserRequest request) {
        User existingUser = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        if (request.getEmail() != null) {
            existingUser.setEmail(request.getEmail());
        }
        if (request.getRole() != null) {
            existingUser.setRole(request.getRole());
        }
        if (request.getIsActive() != null) {
            existingUser.setActive(request.getIsActive());
        }

        User updatedUser = userRepository.save(existingUser);
        return UserResponse.fromEntity(updatedUser);
    }

    @Override
    public void deleteUser(UUID id) {
        if (userRepository.findById(id).isEmpty()) {
            throw new ResourceNotFoundException("User not found with id: " + id);
        }
        userRepository.deleteById(id);
    }
}

// --- Controller Layer ---
@RestController
@RequestMapping("/api/v1/users")
class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponse createdUser = userService.createUser(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(createdUser.getId())
            .toUri();
        return ResponseEntity.created(location).body(createdUser);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @GetMapping
    public ResponseEntity<Page<UserResponse>> listUsers(
        @RequestParam(required = false) String email,
        @RequestParam(required = false) Boolean isActive,
        Pageable pageable) {
        return ResponseEntity.ok(userService.listUsers(email, isActive, pageable));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userService.updateUser(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}