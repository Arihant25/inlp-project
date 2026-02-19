package com.example.caching.classic;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
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
import java.util.concurrent.TimeUnit;

// 1. DOMAIN MODELS
class User {
    private UUID id;
    private String email;
    private String passwordHash;
    private Role role;
    private Boolean isActive;
    private Timestamp createdAt;
    public enum Role { ADMIN, USER }
    // Constructors, Getters, Setters
    public User(UUID id, String email, String passwordHash, Role role) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.isActive = true;
        this.createdAt = Timestamp.from(Instant.now());
    }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }
    public Timestamp getCreatedAt() { return createdAt; }
}

class Post {
    private UUID id;
    private UUID userId;
    private String title;
    private String content;
    private Status status;
    public enum Status { DRAFT, PUBLISHED }
    // Constructors, Getters, Setters
    public Post(UUID id, UUID userId, String title, String content) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.content = content;
        this.status = Status.DRAFT;
    }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
}

// 2. REPOSITORY LAYER (MOCK)
interface UserRepository {
    Optional<User> findById(UUID id);
    User save(User user);
    void deleteById(UUID id);
}

@Repository
class MockUserRepository implements UserRepository {
    private final Map<UUID, User> database = new ConcurrentHashMap<>();

    @Override
    public Optional<User> findById(UUID id) {
        System.out.println("DATABASE HIT: Searching for user with ID: " + id);
        return Optional.ofNullable(database.get(id));
    }

    @Override
    public User save(User user) {
        System.out.println("DATABASE HIT: Saving user with ID: " + user.getId());
        database.put(user.getId(), user);
        return user;
    }

    @Override
    public void deleteById(UUID id) {
        System.out.println("DATABASE HIT: Deleting user with ID: " + id);
        database.remove(id);
    }
}

// 3. SERVICE LAYER
interface UserService {
    User findUserById(UUID id);
    User updateUserEmail(UUID id, String newEmail);
    void deleteUser(UUID id);
    User createNewUser(String email, String password);
}

@Service
@CacheConfig(cacheNames = "users")
class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Cacheable(key = "#id") // Cache-Aside pattern implementation
    public User findUserById(UUID id) {
        System.out.println("SERVICE LOGIC: Executing findUserById for " + id);
        return userRepository.findById(id).orElse(null);
    }

    @Override
    @CachePut(key = "#id") // Updates the cache with the new return value
    public User updateUserEmail(UUID id, String newEmail) {
        System.out.println("SERVICE LOGIC: Executing updateUserEmail for " + id);
        User user = userRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("User not found"));
        user.setEmail(newEmail);
        return userRepository.save(user);
    }

    @Override
    @CacheEvict(key = "#id") // Invalidates/removes the entry from the cache
    public void deleteUser(UUID id) {
        System.out.println("SERVICE LOGIC: Executing deleteUser for " + id);
        userRepository.deleteById(id);
    }

    @Override
    // No caching annotation here, let the find method cache it on first read
    public User createNewUser(String email, String password) {
        System.out.println("SERVICE LOGIC: Executing createNewUser for " + email);
        User newUser = new User(UUID.randomUUID(), email, "hashed_"+password, User.Role.USER);
        return userRepository.save(newUser);
    }
}

// 4. CACHE CONFIGURATION
@Configuration
@EnableCaching
class CacheConfiguration {

    public static final String USERS_CACHE = "users";
    public static final String POSTS_CACHE = "posts";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(USERS_CACHE, POSTS_CACHE);
        
        // Configure User Cache: LRU + Time-based expiration
        Caffeine<Object, Object> userCacheBuilder = Caffeine.newBuilder()
            .maximumSize(100) // LRU eviction when size is exceeded
            .expireAfterWrite(10, TimeUnit.MINUTES); // Time-based expiration
        cacheManager.setCaffeine(userCacheBuilder);

        // Can register specific configurations per cache name
        cacheManager.registerCustomCache(POSTS_CACHE,
            Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build());
        
        return cacheManager;
    }
}

// 5. CONTROLLER
@RestController
@RequestMapping("/classic/users")
class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public User createUser(@RequestParam String email, @RequestParam String password) {
        return userService.createNewUser(email, password);
    }

    @GetMapping("/{id}")
    public User getUser(@PathVariable UUID id) {
        return userService.findUserById(id);
    }

    @PutMapping("/{id}")
    public User updateUser(@PathVariable UUID id, @RequestParam String email) {
        return userService.updateUserEmail(id, email);
    }

    @DeleteMapping("/{id}")
    public String deleteUser(@PathVariable UUID id) {
        userService.deleteUser(id);
        return "User " + id + " deleted.";
    }
}

// 6. MAIN APPLICATION
@SpringBootApplication
public class ClassicCachingApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClassicCachingApplication.class, args);
    }
}