package com.example.lean.model;

import io.micronaut.core.annotation.Introspected;
import java.sql.Timestamp;
import java.util.UUID;

@Introspected
public class UserModel {
    private UUID id;
    private String email;
    private String passwordHash;
    private UserRoleEnum role;
    private boolean isActive;
    private Timestamp createdAt;

    public UserModel() {}

    public UserModel(UUID id, String email, String passwordHash, UserRoleEnum role, boolean isActive, Timestamp createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.isActive = isActive;
        this.createdAt = createdAt;
    }

    // Standard Getters & Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public UserRoleEnum getRole() { return role; }
    public void setRole(UserRoleEnum role) { this.role = role; }
    public boolean getIsActive() { return isActive; }
    public void setIsActive(boolean isActive) { this.isActive = isActive; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}

enum UserRoleEnum {
    ADMIN, USER
}
```
```java
package com.example.lean.dto;

import com.example.lean.model.UserRoleEnum;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

@Introspected
public class UserDto {
    @Nullable @Email private String email;
    @Nullable private String password;
    @Nullable private UserRoleEnum role;
    @Nullable private Boolean isActive;

    // Getters and Setters
    @Nullable public String getEmail() { return email; }
    public void setEmail(@Nullable String email) { this.email = email; }
    @Nullable public String getPassword() { return password; }
    public void setPassword(@Nullable String password) { this.password = password; }
    @Nullable public UserRoleEnum getRole() { return role; }
    public void setRole(@Nullable UserRoleEnum role) { this.role = role; }
    @Nullable public Boolean getIsActive() { return isActive; }
    public void setIsActive(@Nullable Boolean isActive) { this.isActive = isActive; }
}
```
```java
package com.example.lean.repository;

import com.example.lean.model.UserModel;
import com.example.lean.model.UserRoleEnum;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import java.util.Optional;
import java.util.UUID;

public interface UserDataRepository {
    UserModel create(UserModel user);
    UserModel update(UserModel user);
    Optional<UserModel> findUserById(UUID id);
    Optional<UserModel> findByEmail(String email);
    boolean removeById(UUID id);
    Page<UserModel> listAll(Pageable pageable);
    Page<UserModel> filterBy(String email, UserRoleEnum role, Pageable pageable);
}
```
```java
package com.example.lean.repository;

import com.example.lean.model.UserModel;
import com.example.lean.model.UserRoleEnum;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
public class MockUserDataRepository implements UserDataRepository {
    private final Map<UUID, UserModel> db = new ConcurrentHashMap<>();

    public MockUserDataRepository() {
        UserModel u1 = new UserModel(UUID.randomUUID(), "admin.lean@example.com", "hash1", UserRoleEnum.ADMIN, true, Timestamp.from(Instant.now()));
        UserModel u2 = new UserModel(UUID.randomUUID(), "user.lean@example.com", "hash2", UserRoleEnum.USER, false, Timestamp.from(Instant.now()));
        db.put(u1.getId(), u1);
        db.put(u2.getId(), u2);
    }

    @Override
    public UserModel create(UserModel user) {
        db.put(user.getId(), user);
        return user;
    }

    @Override
    public UserModel update(UserModel user) {
        db.replace(user.getId(), user);
        return user;
    }

    @Override
    public Optional<UserModel> findUserById(UUID id) {
        return Optional.ofNullable(db.get(id));
    }
    
    @Override
    public Optional<UserModel> findByEmail(String email) {
        return db.values().stream().filter(u -> u.getEmail().equalsIgnoreCase(email)).findFirst();
    }

    @Override
    public boolean removeById(UUID id) {
        return db.remove(id) != null;
    }

    @Override
    public Page<UserModel> listAll(Pageable pageable) {
        List<UserModel> content = db.values().stream()
            .skip(pageable.getOffset())
            .limit(pageable.getSize())
            .collect(Collectors.toList());
        return Page.of(content, pageable, db.size());
    }

    @Override
    public Page<UserModel> filterBy(String email, UserRoleEnum role, Pageable pageable) {
        Stream<UserModel> stream = db.values().stream();
        if (email != null && !email.isBlank()) {
            stream = stream.filter(u -> u.getEmail().toLowerCase().contains(email.toLowerCase()));
        }
        if (role != null) {
            stream = stream.filter(u -> u.getRole() == role);
        }
        List<UserModel> allFiltered = stream.collect(Collectors.toList());
        List<UserModel> pagedContent = allFiltered.stream()
            .skip(pageable.getOffset())
            .limit(pageable.getSize())
            .collect(Collectors.toList());
        return Page.of(pagedContent, pageable, allFiltered.size());
    }
}
```
```java
package com.example.lean.controller;

import com.example.lean.dto.UserDto;
import com.example.lean.model.UserModel;
import com.example.lean.model.UserRoleEnum;
import com.example.lean.repository.UserDataRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Controller("/api/users")
public class UserApiController {

    private final UserDataRepository userDataRepository;

    @Inject
    public UserApiController(UserDataRepository userDataRepository) {
        this.userDataRepository = userDataRepository;
    }

    @Post
    @Status(HttpStatus.CREATED)
    public UserModel createUser(@Body @Valid UserDto userDto) {
        if (userDto.getEmail() == null || userDto.getPassword() == null || userDto.getRole() == null) {
            throw new IllegalArgumentException("Email, password, and role are required for creation.");
        }
        if (userDataRepository.findByEmail(userDto.getEmail()).isPresent()) {
            throw new IllegalStateException("Email is already taken.");
        }
        
        UserModel newUser = new UserModel(
            UUID.randomUUID(),
            userDto.getEmail(),
            "hashed:" + userDto.getPassword(), // Hashing simulation
            userDto.getRole(),
            true,
            Timestamp.from(Instant.now())
        );
        return userDataRepository.create(newUser);
    }

    @Get("/{userId}")
    public Optional<UserModel> findUser(UUID userId) {
        return userDataRepository.findUserById(userId);
    }

    @Patch("/{userId}")
    public HttpResponse<UserModel> updateUser(UUID userId, @Body UserDto patchDto) {
        return userDataRepository.findUserById(userId)
            .map(existingUser -> {
                if (patchDto.getRole() != null) {
                    existingUser.setRole(patchDto.getRole());
                }
                if (patchDto.getIsActive() != null) {
                    existingUser.setIsActive(patchDto.getIsActive());
                }
                return HttpResponse.ok(userDataRepository.update(existingUser));
            })
            .orElse(HttpResponse.notFound());
    }

    @Delete("/{userId}")
    public HttpResponse<Void> deleteUser(UUID userId) {
        return userDataRepository.removeById(userId) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    @Get
    public Page<UserModel> listUsers(
        @QueryValue @Nullable String email,
        @QueryValue @Nullable UserRoleEnum role,
        Pageable pageable
    ) {
        if (email != null || role != null) {
            return userDataRepository.filterBy(email, role, pageable);
        }
        return userDataRepository.listAll(pageable);
    }
}