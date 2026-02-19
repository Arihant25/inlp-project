package com.example.caching.modern;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 1. DOMAIN (using Java Records for immutability where appropriate)
record User(
    UUID id,
    String email,
    String password_hash,
    Role role,
    boolean is_active,
    Timestamp created_at
) {
    public enum Role { ADMIN, USER }
    
    // "Wither" method for non-destructive updates
    public User withEmail(String newEmail) {
        return new User(this.id, newEmail, this.password_hash, this.role, this.is_active, this.created_at);
    }
}

record Post(
    UUID id,
    UUID user_id,
    String title,
    String content,
    Status status
) {
    public enum Status { DRAFT, PUBLISHED }
}

// 2. REPOSITORY (using Optional)
@Repository
class DataRepository {
    private static final Map<UUID, User> userTable = new ConcurrentHashMap<>();
    private static final Map<UUID, Post> postTable = new ConcurrentHashMap<>();

    public Optional<User> fetchUserById(UUID id) {
        System.out.printf("--- DATABASE: Fetching User %s ---\n", id);
        return Optional.ofNullable(userTable.get(id));
    }

    public User persistUser(User user) {
        System.out.printf("--- DATABASE: Persisting User %s ---\n", user.id());
        userTable.put(user.id(), user);
        return user;
    }

    public void deleteUser(UUID id) {
        System.out.printf("--- DATABASE: Deleting User %s ---\n", id);
        userTable.remove(id);
    }
}

// 3. SERVICE LAYER (Functional approach with Optional)
@Service
class UserQueryService {
    private final DataRepository repository;

    public UserQueryService(DataRepository repository) {
        this.repository = repository;
    }

    // Cache-aside: Cache only if the user is active.
    @Cacheable(value = "userCache", key = "#id", unless = "#result == null or !#result.is_active()")
    public User findActiveUser(UUID id) {
        System.out.println(">>> Service logic: findActiveUser");
        return repository.fetchUserById(id)
            .filter(User::is_active)
            .orElse(null);
    }

    // Update operation that also invalidates the cache
    @CacheEvict(value = "userCache", key = "#id")
    public Optional<User> updateUserEmail(UUID id, String newEmail) {
        System.out.println(">>> Service logic: updateUserEmail");
        return repository.fetchUserById(id)
            .map(user -> user.withEmail(newEmail))
            .map(repository::persistUser);
    }

    // Create operation
    public User provisionNewUser(String email, String password) {
        System.out.println(">>> Service logic: provisionNewUser");
        var user = new User(
            UUID.randomUUID(), email, "hashed:" + password,
            User.Role.USER, true, Timestamp.from(Instant.now())
        );
        return repository.persistUser(user);
    }

    // Delete operation with cache invalidation
    @CacheEvict(value = "userCache", key = "#id")
    public void deactivateAndPurgeUser(UUID id) {
        System.out.println(">>> Service logic: deactivateAndPurgeUser");
        repository.deleteUser(id);
    }
}

// 4. CACHE CONFIGURATION
@Configuration
@EnableCaching
class CacheConfig {
    @Bean
    public CacheManager cacheManager() {
        // Using a simple ConcurrentMapCacheManager for this example.
        // For LRU/TTL, CaffeineCacheManager would be used here instead.
        return new ConcurrentMapCacheManager("userCache", "postCache");
    }
}

// 5. CONTROLLER
@RestController
@RequestMapping("/modern/users")
class UserApiController {
    private final UserQueryService userQueryService;

    public UserApiController(UserQueryService userQueryService) {
        this.userQueryService = userQueryService;
    }

    @GetMapping("/{id}")
    public User getUser(@PathVariable UUID id) {
        return userQueryService.findActiveUser(id);
    }

    @PostMapping
    public User createUser(@RequestParam String email, @RequestParam String password) {
        return userQueryService.provisionNewUser(email, password);
    }

    @PatchMapping("/{id}/email")
    public User changeEmail(@PathVariable UUID id, @RequestParam String newEmail) {
        return userQueryService.updateUserEmail(id, newEmail)
            .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @DeleteMapping("/{id}")
    public String deleteUser(@PathVariable UUID id) {
        userQueryService.deactivateAndPurgeUser(id);
        return "User " + id + " purged.";
    }
}

// 6. MAIN APPLICATION
@SpringBootApplication
public class ModernCachingApplication {
    public static void main(String[] args) {
        SpringApplication.run(ModernCachingApplication.class, args);
    }
}