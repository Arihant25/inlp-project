package com.example.restapipattern.v4;

import jakarta.validation.Valid;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// --- Main Application Class ---
@SpringBootApplication
public class EnterpriseApp {
    public static void main(String[] args) {
        SpringApplication.run(EnterpriseApp.class, args);
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
    public void setActive(boolean active) { this.isActive = active; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}

// --- DTOs ---
// Using records for conciseness
record UserDto(UUID id, String email, Role role, boolean isActive, Timestamp createdAt) {}
record UserCreateDto(String email, String password, Role role) {}
record UserUpdateDto(String email, Role role, Boolean isActive) {}

// --- Specification Pattern for Querying (Simulated) ---
@FunctionalInterface
interface Specification<T> {
    boolean isSatisfiedBy(T item);

    default Specification<T> and(Specification<T> other) {
        return (item) -> this.isSatisfiedBy(item) && other.isSatisfiedBy(item);
    }
}

class UserSpecifications {
    public static Specification<User> emailContains(String email) {
        return user -> !StringUtils.hasText(email) || user.getEmail().toLowerCase().contains(email.toLowerCase());
    }

    public static Specification<User> isActive(Boolean status) {
        return user -> status == null || user.isActive() == status;
    }

    public static Specification<User> hasRole(Role role) {
        return user -> role == null || user.getRole() == role;
    }
}

// --- Repository Layer (Simulated JpaSpecificationExecutor) ---
@Repository
class UserRepository {
    private final Map<UUID, User> DB = new ConcurrentHashMap<>();

    public UserRepository() {
        User u1 = new User(); u1.setId(UUID.randomUUID()); u1.setEmail("admin.spec@example.com"); u1.setRole(Role.ADMIN); u1.setActive(true); u1.setCreatedAt(Timestamp.from(Instant.now()));
        User u2 = new User(); u2.setId(UUID.randomUUID()); u2.setEmail("user.spec@example.com"); u2.setRole(Role.USER); u2.setActive(true); u2.setCreatedAt(Timestamp.from(Instant.now()));
        User u3 = new User(); u3.setId(UUID.randomUUID()); u3.setEmail("inactive.spec@example.com"); u3.setRole(Role.USER); u3.setActive(false); u3.setCreatedAt(Timestamp.from(Instant.now()));
        DB.put(u1.getId(), u1);
        DB.put(u2.getId(), u2);
        DB.put(u3.getId(), u3);
    }

    public User save(User user) {
        if (user.getId() == null) user.setId(UUID.randomUUID());
        DB.put(user.getId(), user);
        return user;
    }

    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(DB.get(id));
    }

    public void deleteById(UUID id) {
        DB.remove(id);
    }

    public Page<User> findAll(Specification<User> spec, Pageable pageable) {
        List<User> results = DB.values().stream()
            .filter(spec::isSatisfiedBy)
            .collect(Collectors.toList());

        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), results.size());
        List<User> pageContent = start > results.size() ? Collections.emptyList() : results.subList(start, end);

        return new PageImpl<>(pageContent, pageable, results.size());
    }
}

// --- Service Layer ---
@Service
class UserService {
    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public UserService(UserRepository userRepository, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
    }

    public UserDto createUser(UserCreateDto dto) {
        User user = userMapper.toEntity(dto);
        User savedUser = userRepository.save(user);
        return userMapper.toDto(savedUser);
    }

    public Optional<UserDto> findUserById(UUID id) {
        return userRepository.findById(id).map(userMapper::toDto);
    }

    public Page<UserDto> findUsersByCriteria(String email, Boolean isActive, Role role, Pageable pageable) {
        Specification<User> spec = UserSpecifications.emailContains(email)
            .and(UserSpecifications.isActive(isActive))
            .and(UserSpecifications.hasRole(role));
        
        return userRepository.findAll(spec, pageable).map(userMapper::toDto);
    }

    public UserDto updateUser(UUID id, UserUpdateDto dto) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        
        userMapper.updateEntityFromDto(dto, user);
        User updatedUser = userRepository.save(user);
        return userMapper.toDto(updatedUser);
    }

    public void deleteUser(UUID id) {
        if (userRepository.findById(id).isEmpty()) {
            throw new NoSuchElementException("User not found: " + id);
        }
        userRepository.deleteById(id);
    }
}

// --- Mapper Component ---
@Component
class UserMapper {
    public UserDto toDto(User user) {
        return new UserDto(user.getId(), user.getEmail(), user.getRole(), user.isActive(), user.getCreatedAt());
    }

    public User toEntity(UserCreateDto dto) {
        User user = new User();
        user.setEmail(dto.email());
        user.setPasswordHash("hashed:" + dto.password()); // Placeholder
        user.setRole(dto.role());
        user.setActive(true);
        user.setCreatedAt(Timestamp.from(Instant.now()));
        return user;
    }

    public void updateEntityFromDto(UserUpdateDto dto, User user) {
        if (dto.email() != null) user.setEmail(dto.email());
        if (dto.role() != null) user.setRole(dto.role());
        if (dto.isActive() != null) user.setActive(dto.isActive());
    }
}

// --- Controller Layer ---
@RestController
@RequestMapping("/api/v4/users")
class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto postUser(@Valid @RequestBody UserCreateDto dto) {
        return userService.createUser(dto);
    }

    @GetMapping("/{id}")
    public UserDto getUser(@PathVariable UUID id) {
        return userService.findUserById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping
    public Page<UserDto> searchUsers(
        @RequestParam(required = false) String email,
        @RequestParam(required = false) Boolean isActive,
        @RequestParam(required = false) Role role,
        Pageable pageable) {
        return userService.findUsersByCriteria(email, isActive, role, pageable);
    }

    @PutMapping("/{id}")
    public UserDto putUser(@PathVariable UUID id, @RequestBody UserUpdateDto dto) {
        try {
            return userService.updateUser(id, dto);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        try {
            userService.deleteUser(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }
}