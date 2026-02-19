package com.example.restapipattern.v3;

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
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

// --- Main Application Class ---
@SpringBootApplication
public class FunctionalApp {
    public static void main(String[] args) {
        SpringApplication.run(FunctionalApp.class, args);
    }
}

// --- Domain Model ---
enum Role { ADMIN, USER }

// Using a mutable class for the "entity" to simulate JPA behavior
class User {
    private UUID id;
    private String email;
    private String passwordHash;
    private Role role;
    private boolean isActive;
    private Timestamp createdAt;
    
    // Getters & Setters
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

// --- DTOs using Java Records ---
record UserDTO(UUID id, String email, Role role, boolean isActive, Timestamp createdAt) {
    public static UserDTO fromEntity(User user) {
        return new UserDTO(user.getId(), user.getEmail(), user.getRole(), user.isActive(), user.getCreatedAt());
    }
}

record CreateUserCmd(@NotBlank @Email String email, @NotBlank @Size(min = 8) String password, @NotNull Role role) {}

record UpdateUserCmd(@Email String email, Role role, Boolean isActive) {}

// --- Exception Handling ---
class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(UUID id) {
        super("Could not find user " + id);
    }
}

@ControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleUserNotFound(UserNotFoundException e) {
        return Map.of("error", e.getMessage());
    }
}

// --- Repository Layer ---
@Repository
class UserStorage {
    private final Map<UUID, User> userStore = new ConcurrentHashMap<>();

    public UserStorage() {
        // Seed data
        Stream.of(
            new User() {{ setId(UUID.randomUUID()); setEmail("admin.func@example.com"); setRole(Role.ADMIN); setActive(true); setCreatedAt(Timestamp.from(Instant.now())); }},
            new User() {{ setId(UUID.randomUUID()); setEmail("user.func@example.com"); setRole(Role.USER); setActive(false); setCreatedAt(Timestamp.from(Instant.now())); }}
        ).forEach(user -> userStore.put(user.getId(), user));
    }

    public User save(User user) {
        if (user.getId() == null) user.setId(UUID.randomUUID());
        userStore.put(user.getId(), user);
        return user;
    }

    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(userStore.get(id));
    }

    public Page<User> findAll(Predicate<User> filter, Pageable pageable) {
        var filteredList = userStore.values().stream().filter(filter).toList();
        var pageList = filteredList.stream()
            .skip(pageable.getOffset())
            .limit(pageable.getPageSize())
            .toList();
        return new PageImpl<>(pageList, pageable, filteredList.size());
    }

    public void deleteById(UUID id) {
        userStore.remove(id);
    }
}

// --- Service Layer ---
@Service
class UserManager {
    private final UserStorage userStorage;

    public UserManager(UserStorage userStorage) {
        this.userStorage = userStorage;
    }

    public User create(CreateUserCmd cmd) {
        var user = new User();
        user.setEmail(cmd.email());
        user.setPasswordHash("hashed::" + cmd.password()); // Hashing logic placeholder
        user.setRole(cmd.role());
        user.setActive(true);
        user.setCreatedAt(Timestamp.from(Instant.now()));
        return userStorage.save(user);
    }

    public Optional<User> findById(UUID id) {
        return userStorage.findById(id);
    }

    public Page<User> findBy(String email, Boolean isActive, Pageable pageable) {
        Predicate<User> predicate = user -> true;
        if (email != null && !email.isBlank()) {
            predicate = predicate.and(user -> user.getEmail().toLowerCase().contains(email.toLowerCase()));
        }
        if (isActive != null) {
            predicate = predicate.and(user -> user.isActive() == isActive);
        }
        return userStorage.findAll(predicate, pageable);
    }

    public User update(UUID id, UpdateUserCmd cmd) {
        return userStorage.findById(id)
            .map(user -> {
                Optional.ofNullable(cmd.email()).ifPresent(user::setEmail);
                Optional.ofNullable(cmd.role()).ifPresent(user::setRole);
                Optional.ofNullable(cmd.isActive()).ifPresent(user::setActive);
                return userStorage.save(user);
            })
            .orElseThrow(() -> new UserNotFoundException(id));
    }

    public void delete(UUID id) {
        if (userStorage.findById(id).isEmpty()) {
            throw new UserNotFoundException(id);
        }
        userStorage.deleteById(id);
    }
}

// --- Controller Layer ---
@RestController
@RequestMapping("/api/v3/users")
class UserEndpoint {
    private final UserManager userManager;

    public UserEndpoint(UserManager userManager) {
        this.userManager = userManager;
    }

    @PostMapping
    public ResponseEntity<UserDTO> createUser(@Valid @RequestBody CreateUserCmd cmd) {
        var createdUser = userManager.create(cmd);
        return new ResponseEntity<>(UserDTO.fromEntity(createdUser), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public UserDTO getUserById(@PathVariable UUID id) {
        return userManager.findById(id)
            .map(UserDTO::fromEntity)
            .orElseThrow(() -> new UserNotFoundException(id));
    }

    @GetMapping
    public Page<UserDTO> listUsers(
        @RequestParam(required = false) String email,
        @RequestParam(required = false) Boolean isActive,
        Pageable pageable) {
        return userManager.findBy(email, isActive, pageable).map(UserDTO::fromEntity);
    }

    @PutMapping("/{id}")
    public UserDTO updateUser(@PathVariable UUID id, @RequestBody UpdateUserCmd cmd) {
        var updatedUser = userManager.update(id, cmd);
        return UserDTO.fromEntity(updatedUser);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable UUID id) {
        userManager.delete(id);
    }
}