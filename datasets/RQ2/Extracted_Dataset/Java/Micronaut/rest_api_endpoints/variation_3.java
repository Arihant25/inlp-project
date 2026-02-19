package com.example.reactive.model;

import io.micronaut.core.annotation.Introspected;
import java.sql.Timestamp;
import java.util.UUID;

@Introspected
public class UserEntity {
    private UUID id;
    private String email;
    private String passwordHash;
    private Role role;
    private boolean isActive;
    private Timestamp createdAt;

    public UserEntity(UUID id, String email, String passwordHash, Role role, boolean isActive, Timestamp createdAt) {
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
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}

enum Role {
    ADMIN, USER
}
```
```java
package com.example.reactive.dto;

import com.example.reactive.model.Role;
import com.example.reactive.model.UserEntity;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Introspected
public record UserRequest(@NotBlank @Email String email, @NotBlank String password, @NotNull Role role) {}
```
```java
package com.example.reactive.dto;

import com.example.reactive.model.Role;
import com.example.reactive.model.UserEntity;
import io.micronaut.core.annotation.Introspected;
import java.sql.Timestamp;
import java.util.UUID;

@Introspected
public record UserResponse(UUID id, String email, Role role, boolean isActive, Timestamp createdAt) {
    public static UserResponse fromEntity(UserEntity entity) {
        return new UserResponse(entity.getId(), entity.getEmail(), entity.getRole(), entity.isActive(), entity.getCreatedAt());
    }
}
```
```java
package com.example.reactive.repository;

import com.example.reactive.model.Role;
import com.example.reactive.model.UserEntity;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.UUID;

public interface ReactiveUserRepository {
    Mono<UserEntity> save(UserEntity user);
    Mono<UserEntity> findById(UUID id);
    Mono<Boolean> existsByEmail(String email);
    Mono<Void> deleteById(UUID id);
    Flux<UserEntity> findAll(Pageable pageable);
    Mono<Long> count();
    Flux<UserEntity> search(String emailQuery, Role role);
}
```
```java
package com.example.reactive.repository;

import com.example.reactive.model.Role;
import com.example.reactive.model.UserEntity;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Singleton
public class InMemoryReactiveUserRepository implements ReactiveUserRepository {
    private final Map<UUID, UserEntity> store = new ConcurrentHashMap<>();

    public InMemoryReactiveUserRepository() {
        UUID id1 = UUID.randomUUID();
        store.put(id1, new UserEntity(id1, "admin.reactive@example.com", "hash", Role.ADMIN, true, Timestamp.from(Instant.now())));
        UUID id2 = UUID.randomUUID();
        store.put(id2, new UserEntity(id2, "user.reactive@example.com", "hash", Role.USER, true, Timestamp.from(Instant.now())));
    }

    @Override
    public Mono<UserEntity> save(UserEntity user) {
        return Mono.fromCallable(() -> {
            store.put(user.getId(), user);
            return user;
        });
    }

    @Override
    public Mono<UserEntity> findById(UUID id) {
        return Mono.justOrEmpty(store.get(id));
    }

    @Override
    public Mono<Boolean> existsByEmail(String email) {
        return Mono.just(store.values().stream().anyMatch(u -> u.getEmail().equalsIgnoreCase(email)));
    }

    @Override
    public Mono<Void> deleteById(UUID id) {
        return Mono.fromRunnable(() -> store.remove(id));
    }

    @Override
    public Flux<UserEntity> findAll(Pageable pageable) {
        return Flux.fromIterable(store.values())
            .skip(pageable.getOffset())
            .take(pageable.getSize());
    }
    
    @Override
    public Mono<Long> count() {
        return Mono.just((long) store.size());
    }

    @Override
    public Flux<UserEntity> search(String emailQuery, Role role) {
        Stream<UserEntity> stream = store.values().stream();
        if (emailQuery != null) {
            stream = stream.filter(u -> u.getEmail().contains(emailQuery));
        }
        if (role != null) {
            stream = stream.filter(u -> u.getRole() == role);
        }
        return Flux.fromStream(stream);
    }
}
```
```java
package com.example.reactive.service;

import com.example.reactive.dto.UserRequest;
import com.example.reactive.dto.UserResponse;
import com.example.reactive.model.Role;
import com.example.reactive.model.UserEntity;
import com.example.reactive.repository.ReactiveUserRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Singleton
public class ReactiveUserService {
    private final ReactiveUserRepository userRepository;

    public ReactiveUserService(ReactiveUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Mono<UserEntity> create(UserRequest request) {
        return userRepository.existsByEmail(request.email())
            .flatMap(exists -> {
                if (exists) {
                    return Mono.error(new IllegalArgumentException("Email already exists"));
                }
                UserEntity newUser = new UserEntity(
                    UUID.randomUUID(),
                    request.email(),
                    "hashed_password", // Password hashing
                    request.role(),
                    true,
                    Timestamp.from(Instant.now())
                );
                return userRepository.save(newUser);
            });
    }

    public Mono<UserEntity> getById(UUID id) {
        return userRepository.findById(id);
    }

    public Mono<UserEntity> update(UUID id, Role newRole, Boolean newIsActive) {
        return userRepository.findById(id)
            .flatMap(user -> {
                if (newRole != null) user.setRole(newRole);
                if (newIsActive != null) user.setActive(newIsActive);
                return userRepository.save(user);
            });
    }

    public Mono<Void> delete(UUID id) {
        return userRepository.deleteById(id);
    }

    public Mono<Page<UserResponse>> list(Pageable pageable) {
        Flux<UserResponse> content = userRepository.findAll(pageable).map(UserResponse::fromEntity);
        Mono<Long> total = userRepository.count();
        return content.collectList().zipWith(total)
            .map(tuple -> Page.of(tuple.getT1(), pageable, tuple.getT2()));
    }

    public Flux<UserResponse> search(String email, Role role) {
        return userRepository.search(email, role).map(UserResponse::fromEntity);
    }
}
```
```java
package com.example.reactive.controller;

import com.example.reactive.dto.UserRequest;
import com.example.reactive.dto.UserResponse;
import com.example.reactive.model.Role;
import com.example.reactive.service.ReactiveUserService;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.core.annotation.Nullable;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Controller("/reactive/users")
public class ReactiveUserController {

    private final ReactiveUserService userService;

    public ReactiveUserController(ReactiveUserService userService) {
        this.userService = userService;
    }

    @Post
    public Mono<HttpResponse<UserResponse>> createUser(@Body @Valid Mono<UserRequest> userRequest) {
        return userRequest.flatMap(userService::create)
            .map(userEntity -> HttpResponse
                .created(UserResponse.fromEntity(userEntity))
                .location(URI.create("/reactive/users/" + userEntity.getId()))
            )
            .onErrorReturn(IllegalArgumentException.class, HttpResponse.badRequest());
    }

    @Get("/{id}")
    public Mono<HttpResponse<UserResponse>> getUser(UUID id) {
        return userService.getById(id)
            .map(userEntity -> HttpResponse.ok(UserResponse.fromEntity(userEntity)))
            .defaultIfEmpty(HttpResponse.notFound());
    }

    @Put("/{id}")
    public Mono<HttpResponse<UserResponse>> updateUser(UUID id, @Body Map<String, Object> updates) {
        Role role = updates.containsKey("role") ? Role.valueOf(updates.get("role").toString()) : null;
        Boolean isActive = updates.containsKey("isActive") ? (Boolean) updates.get("isActive") : null;
        
        return userService.update(id, role, isActive)
            .map(userEntity -> HttpResponse.ok(UserResponse.fromEntity(userEntity)))
            .defaultIfEmpty(HttpResponse.notFound());
    }

    @Delete("/{id}")
    public Mono<HttpResponse<Void>> deleteUser(UUID id) {
        return userService.delete(id).then(Mono.just(HttpResponse.noContent()));
    }

    @Get
    public Mono<Page<UserResponse>> listUsers(Pageable pageable) {
        return userService.list(pageable);
    }

    @Get("/search")
    public Flux<UserResponse> searchUsers(@QueryValue @Nullable String email, @QueryValue @Nullable Role role) {
        return userService.search(email, role);
    }
}