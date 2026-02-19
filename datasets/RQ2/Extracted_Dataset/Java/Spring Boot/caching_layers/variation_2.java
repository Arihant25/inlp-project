package com.example.caching.pragmatic;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/*
 To enable Caffeine with this approach, add to application.properties:
 spring.cache.type=caffeine
 spring.cache.caffeine.spec=maximumSize=500,expireAfterAccess=600s
*/

// 1. DOMAIN (with Lombok)
@Data
class User {
    private UUID id;
    private String email;
    private String password_hash;
    private Role role;
    private Boolean is_active;
    private Timestamp created_at;
    public enum Role { ADMIN, USER }
}

@Data
class Post {
    private UUID id;
    private UUID user_id;
    private String title;
    private String content;
    private Status status;
    public enum Status { DRAFT, PUBLISHED }
}

// 2. MOCK DATA STORE
@Component
class DataStore {
    final Map<UUID, User> users = new ConcurrentHashMap<>();
    final Map<UUID, Post> posts = new ConcurrentHashMap<>();

    public DataStore() {
        // Pre-populate with some data
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setEmail("test@example.com");
        user.setRole(User.Role.USER);
        user.setIs_active(true);
        user.setCreated_at(Timestamp.from(Instant.now()));
        users.put(userId, user);
    }
}

// 3. SERVICE LAYER (concise, no interfaces)
@Service
@RequiredArgsConstructor
public class UserPostService {

    private final DataStore db;

    // --- User Operations ---

    @Cacheable(value = "users", key = "#id")
    public Optional<User> getUser(UUID id) {
        System.out.println("DB-READ: Fetching user " + id);
        // Simulating DB latency
        try { Thread.sleep(500); } catch (InterruptedException e) {}
        return Optional.ofNullable(db.users.get(id));
    }

    @CachePut(value = "users", key = "#result.id")
    public User saveUser(String email, String password) {
        System.out.println("DB-WRITE: Creating user for " + email);
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setPassword_hash("hash(" + password + ")");
        u.setRole(User.Role.USER);
        u.setIs_active(true);
        u.setCreated_at(Timestamp.from(Instant.now()));
        db.users.put(u.getId(), u);
        return u;
    }

    @CacheEvict(value = "users", key = "#id")
    public void deleteUser(UUID id) {
        System.out.println("DB-DELETE: Removing user " + id);
        db.users.remove(id);
    }

    // --- Post Operations ---

    @Cacheable(value = "posts", key = "#id")
    public Optional<Post> getPost(UUID id) {
        System.out.println("DB-READ: Fetching post " + id);
        return Optional.ofNullable(db.posts.get(id));
    }

    @CachePut(value = "posts", key = "#result.id")
    public Post savePost(UUID userId, String title, String content) {
        System.out.println("DB-WRITE: Creating post '" + title + "'");
        Post p = new Post();
        p.setId(UUID.randomUUID());
        p.setUser_id(userId);
        p.setTitle(title);
        p.setContent(content);
        p.setStatus(Post.Status.PUBLISHED);
        db.posts.put(p.getId(), p);
        return p;
    }
}

// 4. CONTROLLER
@RestController
@RequestMapping("/pragmatic")
@RequiredArgsConstructor
class ApiController {

    private final UserPostService service;

    @GetMapping("/users/{id}")
    public User findUser(@PathVariable UUID id) {
        return service.getUser(id).orElse(null);
    }

    @PostMapping("/users")
    public User createUser(@RequestParam String email, @RequestParam String password) {
        return service.saveUser(email, password);
    }

    @DeleteMapping("/users/{id}")
    public void removeUser(@PathVariable UUID id) {
        service.deleteUser(id);
    }
    
    @GetMapping("/posts/{id}")
    public Post findPost(@PathVariable UUID id) {
        return service.getPost(id).orElse(null);
    }
}

// 5. MAIN APPLICATION
@SpringBootApplication
@EnableCaching
public class PragmaticCachingApplication {
    public static void main(String[] args) {
        SpringApplication.run(PragmaticCachingApplication.class, args);
    }
}