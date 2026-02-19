package com.example.cqrs.domain;

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
    public void setActive(boolean active) { this.isActive = active; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}

enum UserRole {
    ADMIN, USER
}
```
```java
package com.example.cqrs.common;

import com.example.cqrs.domain.User;
import com.example.cqrs.domain.UserRole;
import jakarta.inject.Singleton;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class InMemoryUserStore {
    public final Map<UUID, User> store = new ConcurrentHashMap<>();

    public InMemoryUserStore() {
        UUID id1 = UUID.randomUUID();
        store.put(id1, new User(id1, "admin.cqrs@example.com", "hash", UserRole.ADMIN, true, Timestamp.from(Instant.now())));
        UUID id2 = UUID.randomUUID();
        store.put(id2, new User(id2, "user.cqrs@example.com", "hash", UserRole.USER, true, Timestamp.from(Instant.now())));
    }
}
```
```java
package com.example.cqrs.command.model;

import com.example.cqrs.domain.UserRole;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Introspected
public record CreateUserCommand(@NotBlank @Email String email, @NotBlank String password, @NotNull UserRole role) {}
```
```java
package com.example.cqrs.command.model;

import com.example.cqrs.domain.UserRole;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

@Introspected
public record UpdateUserCommand(@Nullable UserRole role, @Nullable Boolean isActive) {}
```
```java
package com.example.cqrs.command.handler;

import com.example.cqrs.command.model.CreateUserCommand;
import com.example.cqrs.command.model.UpdateUserCommand;
import com.example.cqrs.common.InMemoryUserStore;
import com.example.cqrs.domain.User;
import jakarta.inject.Singleton;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class UserCommandHandler {
    private final InMemoryUserStore userStore;

    public UserCommandHandler(InMemoryUserStore userStore) {
        this.userStore = userStore;
    }

    public User handle(CreateUserCommand command) {
        boolean emailExists = userStore.store.values().stream()
            .anyMatch(u -> u.getEmail().equalsIgnoreCase(command.email()));
        if (emailExists) {
            throw new IllegalStateException("Email already exists");
        }
        User user = new User(
            UUID.randomUUID(),
            command.email(),
            "hashed:" + command.password(),
            command.role(),
            true,
            Timestamp.from(Instant.now())
        );
        userStore.store.put(user.getId(), user);
        return user;
    }

    public Optional<User> handle(UUID id, UpdateUserCommand command) {
        User user = userStore.store.get(id);
        if (user == null) {
            return Optional.empty();
        }
        if (command.role() != null) {
            user.setRole(command.role());
        }
        if (command.isActive() != null) {
            user.setActive(command.isActive());
        }
        userStore.store.put(id, user);
        return Optional.of(user);
    }

    public boolean handle(UUID id) { // Delete command
        return userStore.store.remove(id) != null;
    }
}
```
```java
package com.example.cqrs.command.controller;

import com.example.cqrs.command.handler.UserCommandHandler;
import com.example.cqrs.command.model.CreateUserCommand;
import com.example.cqrs.command.model.UpdateUserCommand;
import com.example.cqrs.domain.User;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;

@Validated
@Controller("/cmd/users")
public class UserCommandController {
    private final UserCommandHandler commandHandler;

    public UserCommandController(UserCommandHandler commandHandler) {
        this.commandHandler = commandHandler;
    }

    @Post
    public HttpResponse<User> createUser(@Body @Valid CreateUserCommand command) {
        try {
            User createdUser = commandHandler.handle(command);
            return HttpResponse.created(createdUser).location(URI.create("/query/users/" + createdUser.getId()));
        } catch (IllegalStateException e) {
            return HttpResponse.badRequest();
        }
    }

    @Put("/{id}")
    public HttpResponse<User> updateUser(UUID id, @Body @Valid UpdateUserCommand command) {
        return commandHandler.handle(id, command)
            .map(HttpResponse::ok)
            .orElse(HttpResponse.notFound());
    }

    @Delete("/{id}")
    public HttpResponse<Void> deleteUser(UUID id) {
        return commandHandler.handle(id) ? HttpResponse.noContent() : HttpResponse.notFound();
    }
}
```
```java
package com.example.cqrs.query.model;

import com.example.cqrs.domain.User;
import com.example.cqrs.domain.UserRole;
import io.micronaut.core.annotation.Introspected;
import java.sql.Timestamp;
import java.util.UUID;

@Introspected
public record UserView(UUID id, String email, UserRole role, boolean isActive, Timestamp createdAt) {
    public static UserView fromDomain(User user) {
        return new UserView(user.getId(), user.getEmail(), user.getRole(), user.isActive(), user.getCreatedAt());
    }
}
```
```java
package com.example.cqrs.query.handler;

import com.example.cqrs.common.InMemoryUserStore;
import com.example.cqrs.domain.UserRole;
import com.example.cqrs.query.model.UserView;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
public class UserQueryHandler {
    private final InMemoryUserStore userStore;

    public UserQueryHandler(InMemoryUserStore userStore) {
        this.userStore = userStore;
    }

    public Optional<UserView> handle(UUID id) {
        return Optional.ofNullable(userStore.store.get(id)).map(UserView::fromDomain);
    }

    public Page<UserView> handle(Pageable pageable) {
        List<UserView> content = userStore.store.values().stream()
            .map(UserView::fromDomain)
            .skip(pageable.getOffset())
            .limit(pageable.getSize())
            .collect(Collectors.toList());
        return Page.of(content, pageable, userStore.store.size());
    }

    public List<UserView> handle(String email, UserRole role) {
        Stream<UserView> stream = userStore.store.values().stream().map(UserView::fromDomain);
        if (email != null) {
            stream = stream.filter(uv -> uv.email().contains(email));
        }
        if (role != null) {
            stream = stream.filter(uv -> uv.role() == role);
        }
        return stream.collect(Collectors.toList());
    }
}
```
```java
package com.example.cqrs.query.controller;

import com.example.cqrs.domain.UserRole;
import com.example.cqrs.query.handler.UserQueryHandler;
import com.example.cqrs.query.model.UserView;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.core.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Controller("/query/users")
public class UserQueryController {
    private final UserQueryHandler queryHandler;

    public UserQueryController(UserQueryHandler queryHandler) {
        this.queryHandler = queryHandler;
    }

    @Get("/{id}")
    public Optional<UserView> getUserById(UUID id) {
        return queryHandler.handle(id);
    }

    @Get
    public Page<UserView> listUsers(Pageable pageable) {
        return queryHandler.handle(pageable);
    }

    @Get("/search")
    public List<UserView> searchUsers(@QueryValue @Nullable String email, @QueryValue @Nullable UserRole role) {
        return queryHandler.handle(email, role);
    }
}