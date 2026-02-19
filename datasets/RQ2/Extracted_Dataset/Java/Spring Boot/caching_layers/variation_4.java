package com.example.caching.configurable;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/*
 In application.properties, you would define:
 app.caching.specs.users.ttl=15
 app.caching.specs.users.max-size=250
 app.caching.specs.posts.ttl=5
 app.caching.specs.posts.max-size=1000
*/

// 1. DOMAIN
class User {
    private UUID id; String email; String password_hash; Role role; Boolean is_active; Timestamp created_at;
    public enum Role { ADMIN, USER }
    public User(UUID id, String email) { this.id = id; this.email = email; this.is_active = true; this.created_at = Timestamp.from(Instant.now()); }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
class Post {
    private UUID id; UUID user_id; String title; String content; Status status;
    public enum Status { DRAFT, PUBLISHED }
    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
}

// 2. CACHE CONSTANTS
final class CacheConstants {
    private CacheConstants() {}
    public static final String USERS_CACHE = "users";
    public static final String POSTS_CACHE = "posts";
}

// 3. CONFIGURATION PROPERTIES
@ConfigurationProperties(prefix = "app.caching")
class AppCacheProperties {
    private Map<String, CacheSpec> specs;
    public static class CacheSpec {
        private Integer ttl; // in minutes
        private Integer maxSize;
        public Integer getTtl() { return ttl; }
        public void setTtl(Integer ttl) { this.ttl = ttl; }
        public Integer getMaxSize() { return maxSize; }
        public void setMaxSize(Integer maxSize) { this.maxSize = maxSize; }
    }
    public Map<String, CacheSpec> getSpecs() { return specs; }
    public void setSpecs(Map<String, CacheSpec> specs) { this.specs = specs; }
}

// 4. CACHE CONFIGURATION
@Configuration
@EnableCaching
@EnableConfigurationProperties(AppCacheProperties.class)
class CacheManagerConfig {
    @Autowired
    private AppCacheProperties cacheProperties;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        if (cacheProperties.getSpecs() != null) {
            cacheProperties.getSpecs().forEach((cacheName, spec) -> {
                Caffeine<Object, Object> builder = Caffeine.newBuilder()
                    .expireAfterWrite(spec.getTtl(), TimeUnit.MINUTES)
                    .maximumSize(spec.getMaxSize());
                manager.registerCustomCache(cacheName, builder.build());
                System.out.printf("Configured cache '%s': TTL=%dm, MaxSize=%d%n", cacheName, spec.getTtl(), spec.getMaxSize());
            });
        }
        // Add a default fallback cache
        manager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).maximumSize(100));
        return manager;
    }
}

// 5. MOCK REPOSITORY
@Component
class MockDatabase {
    private final Map<UUID, User> userStore = new ConcurrentHashMap<>();
    public Optional<User> findUser(UUID id) {
        System.out.println("... Mock DB query for user " + id);
        return Optional.ofNullable(userStore.get(id));
    }
    public User saveUser(User user) {
        System.out.println("... Mock DB save for user " + user.getId());
        userStore.put(user.getId(), user);
        return user;
    }
    public void deleteUser(UUID id) {
        System.out.println("... Mock DB delete for user " + id);
        userStore.remove(id);
    }
}

// 6. SERVICE
@Service
class ConfigurableUserService {
    private final MockDatabase database;

    public ConfigurableUserService(MockDatabase database) { this.database = database; }

    @Cacheable(cacheNames = CacheConstants.USERS_CACHE, key = "#userId")
    public User retrieveUser(UUID userId) {
        return database.findUser(userId).orElse(null);
    }

    @CachePut(cacheNames = CacheConstants.USERS_CACHE, key = "#userId")
    public User modifyUser(UUID userId, String newEmail) {
        User user = database.findUser(userId).orElseThrow(RuntimeException::new);
        user.setEmail(newEmail);
        return database.saveUser(user);
    }

    @CacheEvict(cacheNames = CacheConstants.USERS_CACHE, key = "#userId")
    public void removeUser(UUID userId) {
        database.deleteUser(userId);
    }

    public User registerUser(String email) {
        return database.saveUser(new User(UUID.randomUUID(), email));
    }
}

// 7. CONTROLLER
@RestController
@RequestMapping("/configurable/users")
class UserResource {
    private final ConfigurableUserService userService;
    public UserResource(ConfigurableUserService userService) { this.userService = userService; }

    @GetMapping("/{id}")
    public User getUser(@PathVariable("id") UUID id) { return userService.retrieveUser(id); }

    @PostMapping
    public User postUser(@RequestParam String email) { return userService.registerUser(email); }

    @PutMapping("/{id}")
    public User putUser(@PathVariable("id") UUID id, @RequestParam String email) { return userService.modifyUser(id, email); }

    @DeleteMapping("/{id}")
    public void deleteUser(@PathVariable("id") UUID id) { userService.removeUser(id); }
}

// 8. MAIN APPLICATION
@SpringBootApplication
public class ConfigurableCachingApplication {
    public static void main(String[] args) {
        // Mock properties for standalone run
        System.setProperty("app.caching.specs.users.ttl", "15");
        System.setProperty("app.caching.specs.users.max-size", "250");
        System.setProperty("app.caching.specs.posts.ttl", "5");
        System.setProperty("app.caching.specs.posts.max-size", "1000");
        SpringApplication.run(ConfigurableCachingApplication.class, args);
    }
}