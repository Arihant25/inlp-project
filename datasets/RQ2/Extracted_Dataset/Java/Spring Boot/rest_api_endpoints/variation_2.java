package com.example.restapipattern.v2;

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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// --- Main Application Class ---
@SpringBootApplication
public class PragmaticApp {
    public static void main(String[] args) {
        SpringApplication.run(PragmaticApp.class, args);
    }
}

// --- Domain Model ---
enum Role { ADMIN, USER }

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

// --- Exception Handling ---
@ResponseStatus(HttpStatus.NOT_FOUND)
class ResourceNotFound extends RuntimeException {
    public ResourceNotFound(String message) { super(message); }
}

// --- Mapper Utility ---
class UserMapper {
    public static UserView toUserView(User user) {
        return new UserView(user.getId(), user.getEmail(), user.getRole(), user.isActive(), user.getCreatedAt());
    }

    public static User toEntity(CreateUser req) {
        User user = new User();
        user.setEmail(req.getEmail());
        // In a real app, hash the password here
        user.setPasswordHash("hashed_" + req.getPassword());
        user.setRole(req.getRole());
        user.setActive(true);
        return user;
    }
}

// --- Repository Layer (simulated) ---
@Repository
class UserRepo {
    private final Map<UUID, User> db = new ConcurrentHashMap<>();

    public UserRepo() {
        // Mock data
        User u1 = new User(); u1.setId(UUID.randomUUID()); u1.setEmail("admin@corp.com"); u1.setRole(Role.ADMIN); u1.setActive(true); u1.setCreatedAt(Timestamp.from(Instant.now()));
        User u2 = new User(); u2.setId(UUID.randomUUID()); u2.setEmail("user@corp.com"); u2.setRole(Role.USER); u2.setActive(true); u2.setCreatedAt(Timestamp.from(Instant.now()));
        db.put(u1.getId(), u1);
        db.put(u2.getId(), u2);
    }

    public User save(User user) {
        if (user.getId() == null) user.setId(UUID.randomUUID());
        if (user.getCreatedAt() == null) user.setCreatedAt(Timestamp.from(Instant.now()));
        db.put(user.getId(), user);
        return user;
    }

    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(db.get(id));
    }

    public Page<User> findByCriteria(String email, Boolean isActive, Pageable pageable) {
        List<User> users = db.values().stream()
            .filter(u -> email == null || u.getEmail().contains(email))
            .filter(u -> isActive == null || u.isActive() == isActive)
            .collect(Collectors.toList());
        
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), users.size());
        List<User> pageContent = start > users.size() ? Collections.emptyList() : users.subList(start, end);
        
        return new PageImpl<>(pageContent, pageable, users.size());
    }

    public void delete(User user) {
        db.remove(user.getId());
    }
}

// --- Service Layer ---
@Service
class UserSvc {
    private final UserRepo userRepo;

    public UserSvc(UserRepo userRepo) {
        this.userRepo = userRepo;
    }

    public User create(User user) {
        // Additional business logic could go here
        return userRepo.save(user);
    }

    public User find(UUID id) {
        return userRepo.findById(id).orElseThrow(() -> new ResourceNotFound("User " + id + " not found"));
    }

    public Page<User> findAll(String email, Boolean isActive, Pageable pageable) {
        return userRepo.findByCriteria(email, isActive, pageable);
    }

    public User update(UUID id, UserController.PatchUser patch) {
        User user = find(id);
        patch.getEmail().ifPresent(user::setEmail);
        patch.getRole().ifPresent(user::setRole);
        patch.getIsActive().ifPresent(user::setActive);
        return userRepo.save(user);
    }

    public void delete(UUID id) {
        User user = find(id);
        userRepo.delete(user);
    }
}

// --- Controller Layer with Inner DTOs ---
@RestController
@RequestMapping("/api/v2/users")
class UserController {

    // --- DTOs as public static inner classes ---
    public static class CreateUser {
        @NotBlank @Email private String email;
        @NotBlank @Size(min = 8) private String password;
        @NotNull private Role role;
        // Getters & Setters
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public Role getRole() { return role; }
        public void setRole(Role role) { this.role = role; }
    }

    public static class PatchUser {
        private String email;
        private Role role;
        private Boolean isActive;
        // Using Optional for partial updates
        public Optional<String> getEmail() { return Optional.ofNullable(email); }
        public Optional<Role> getRole() { return Optional.ofNullable(role); }
        public Optional<Boolean> getIsActive() { return Optional.ofNullable(isActive); }
        // Setters
        public void setEmail(String email) { this.email = email; }
        public void setRole(Role role) { this.role = role; }
        public void setIsActive(Boolean active) { isActive = active; }
    }

    public static class UserView {
        private UUID id;
        private String email;
        private Role role;
        private boolean isActive;
        private Timestamp createdAt;
        // Constructor, Getters
        public UserView(UUID id, String email, Role role, boolean isActive, Timestamp createdAt) {
            this.id = id; this.email = email; this.role = role; this.isActive = isActive; this.createdAt = createdAt;
        }
        public UUID getId() { return id; }
        public String getEmail() { return email; }
        public Role getRole() { return role; }
        public boolean isActive() { return isActive; }
        public Timestamp getCreatedAt() { return createdAt; }
    }

    private final UserSvc userSvc;

    public UserController(UserSvc userSvc) {
        this.userSvc = userSvc;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView createUser(@Valid @RequestBody CreateUser req) {
        User userEntity = UserMapper.toEntity(req);
        User createdUser = userSvc.create(userEntity);
        return UserMapper.toUserView(createdUser);
    }

    @GetMapping("/{id}")
    public UserView getUser(@PathVariable UUID id) {
        return UserMapper.toUserView(userSvc.find(id));
    }

    @GetMapping
    public Page<UserView> listUsers(
        @RequestParam(required = false) String email,
        @RequestParam(required = false) Boolean isActive,
        Pageable pageable) {
        return userSvc.findAll(email, isActive, pageable).map(UserMapper::toUserView);
    }

    @PatchMapping("/{id}")
    public UserView patchUser(@PathVariable UUID id, @RequestBody PatchUser patch) {
        User updatedUser = userSvc.update(id, patch);
        return UserMapper.toUserView(updatedUser);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable UUID id) {
        userSvc.delete(id);
    }
}