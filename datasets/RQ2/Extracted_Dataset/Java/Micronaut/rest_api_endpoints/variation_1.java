package com.example.classic.model;

import io.micronaut.core.annotation.Introspected;
import java.sql.Timestamp;
import java.util.UUID;

@Introspected
public class User {
    private UUID id;
    private String email;
    private String passwordHash;
    private UserRole role;
    private boolean isActive;
    private Timestamp createdAt;

    public User(UUID id, String email, String passwordHash, UserRole role, boolean isActive, Timestamp createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.isActive = isActive;
        this.createdAt = createdAt;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}

enum UserRole {
    ADMIN, USER
}
```
```java
package com.example.classic.dto;

import com.example.classic.model.UserRole;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Introspected
public record UserCreateDTO(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8) String password,
    @NotNull UserRole role
) {}
```
```java
package com.example.classic.dto;

import com.example.classic.model.UserRole;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

@Introspected
public record UserUpdateDTO(
    @Nullable UserRole role,
    @Nullable Boolean isActive
) {}
```
```java
package com.example.classic.repository;

import com.example.classic.model.User;
import com.example.classic.model.UserRole;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    User save(User user);
    Optional<User> findById(UUID id);
    void deleteById(UUID id);
    Page<User> findAll(Pageable pageable);
    List<User> search(Optional<String> email, Optional<UserRole> role);
    boolean existsByEmail(String email);
}
```
```java
package com.example.classic.repository;

import com.example.classic.model.User;
import com.example.classic.model.UserRole;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Singleton
public class InMemoryUserRepository implements UserRepository {
    private final ConcurrentHashMap<UUID, User> userStore = new ConcurrentHashMap<>();

    public InMemoryUserRepository() {
        // Mock data
        UUID adminId = UUID.randomUUID();
        userStore.put(adminId, new User(adminId, "admin@example.com", "hash1", UserRole.ADMIN, true, Timestamp.from(Instant.now())));
        UUID userId = UUID.randomUUID();
        userStore.put(userId, new User(userId, "user@example.com", "hash2", UserRole.USER, true, Timestamp.from(Instant.now())));
    }

    @Override
    public User save(User user) {
        userStore.put(user.getId(), user);
        return user;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(userStore.get(id));
    }

    @Override
    public void deleteById(UUID id) {
        userStore.remove(id);
    }

    @Override
    public Page<User> findAll(Pageable pageable) {
        List<User> users = userStore.values().stream()
            .sorted(Comparator.comparing(User::getCreatedAt).reversed())
            .skip(pageable.getOffset())
            .limit(pageable.getSize())
            .collect(Collectors.toList());
        return Page.of(users, pageable, userStore.size());
    }

    @Override
    public List<User> search(Optional<String> email, Optional<UserRole> role) {
        return userStore.values().stream()
            .filter(user -> email.map(e -> user.getEmail().contains(e)).orElse(true))
            .filter(user -> role.map(r -> user.getRole() == r).orElse(true))
            .collect(Collectors.toList());
    }
    
    @Override
    public boolean existsByEmail(String email) {
        return userStore.values().stream().anyMatch(u -> u.getEmail().equalsIgnoreCase(email));
    }
}
```
```java
package com.example.classic.service;

import com.example.classic.dto.UserCreateDTO;
import com.example.classic.dto.UserUpdateDTO;
import com.example.classic.model.User;
import com.example.classic.model.UserRole;
import com.example.classic.repository.UserRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class UserService {

    @Inject
    private UserRepository userRepository;

    public User createUser(UserCreateDTO dto) {
        if (userRepository.existsByEmail(dto.email())) {
            throw new IllegalArgumentException("Email already in use");
        }
        // In a real app, hash the password
        String passwordHash = "hashed_" + dto.password();
        User newUser = new User(
            UUID.randomUUID(),
            dto.email(),
            passwordHash,
            dto.role(),
            true,
            Timestamp.from(Instant.now())
        );
        return userRepository.save(newUser);
    }

    public Optional<User> getUserById(UUID id) {
        return userRepository.findById(id);
    }

    public Optional<User> updateUser(UUID id, UserUpdateDTO dto) {
        return userRepository.findById(id).map(user -> {
            if (dto.role() != null) {
                user.setRole(dto.role());
            }
            if (dto.isActive() != null) {
                user.setActive(dto.isActive());
            }
            return userRepository.save(user);
        });
    }

    public boolean deleteUser(UUID id) {
        if (userRepository.findById(id).isPresent()) {
            userRepository.deleteById(id);
            return true;
        }
        return false;
    }

    public Page<User> listUsers(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    public List<User> searchUsers(Optional<String> email, Optional<UserRole> role) {
        return userRepository.search(email, role);
    }
}
```
```java
package com.example.classic.controller;

import com.example.classic.dto.UserCreateDTO;
import com.example.classic.dto.UserUpdateDTO;
import com.example.classic.model.User;
import com.example.classic.model.UserRole;
import com.example.classic.service.UserService;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Validated
@Controller("/users")
public class UserController {

    @Inject
    private UserService userService;

    @Post
    public HttpResponse<User> createUser(@Body @Valid UserCreateDTO createDTO) {
        try {
            User newUser = userService.createUser(createDTO);
            return HttpResponse.created(newUser).location(URI.create("/users/" + newUser.getId()));
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest();
        }
    }

    @Get("/{id}")
    public HttpResponse<User> getUserById(UUID id) {
        return userService.getUserById(id)
            .map(HttpResponse::ok)
            .orElse(HttpResponse.notFound());
    }

    @Put("/{id}")
    public HttpResponse<User> updateUser(UUID id, @Body @Valid UserUpdateDTO updateDTO) {
        return userService.updateUser(id, updateDTO)
            .map(HttpResponse::ok)
            .orElse(HttpResponse.notFound());
    }

    @Delete("/{id}")
    public HttpResponse<Void> deleteUser(UUID id) {
        if (userService.deleteUser(id)) {
            return HttpResponse.noContent();
        }
        return HttpResponse.notFound();
    }

    @Get
    public HttpResponse<Page<User>> listUsers(Pageable pageable) {
        return HttpResponse.ok(userService.listUsers(pageable));
    }

    @Get("/search")
    public HttpResponse<List<User>> searchUsers(@QueryValue Optional<String> email, @QueryValue Optional<UserRole> role) {
        return HttpResponse.ok(userService.searchUsers(email, role));
    }
}