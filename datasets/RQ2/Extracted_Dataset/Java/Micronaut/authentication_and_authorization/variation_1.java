package com.example.classic;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.*;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// --- Domain Models ---

enum Role { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

class User {
    private UUID id;
    private String email;
    private String passwordHash;
    private Role role;
    private boolean isActive;
    private Timestamp createdAt;

    public User(UUID id, String email, String passwordHash, Role role, boolean isActive) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.isActive = isActive;
        this.createdAt = Timestamp.from(Instant.now());
    }

    // Getters
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public boolean isActive() { return isActive; }
}

class Post {
    private UUID id;
    private UUID userId;
    private String title;
    private String content;
    private PostStatus status;

    public Post(UUID userId, String title, String content) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.title = title;
        this.content = content;
        this.status = PostStatus.DRAFT;
    }
    
    // Getters
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public PostStatus getStatus() { return status; }
}

// --- Password Hashing ---

interface PasswordEncoder {
    String encode(String rawPassword);
    boolean matches(String rawPassword, String encodedPassword);
}

@Singleton
class BCryptPasswordEncoder implements PasswordEncoder {
    // This is a mock. In a real app, use a real BCrypt library.
    @Override
    public String encode(String rawPassword) {
        return "bcrypt-hashed-" + rawPassword;
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        return encodedPassword.equals(encode(rawPassword));
    }
}

// --- Data Layer (Mocks) ---

@Singleton
class UserRepository {
    private final Map<String, User> usersByEmail = new ConcurrentHashMap<>();

    public UserRepository(PasswordEncoder passwordEncoder) {
        UUID adminId = UUID.randomUUID();
        User admin = new User(adminId, "admin@example.com", passwordEncoder.encode("admin123"), Role.ADMIN, true);
        
        UUID userId = UUID.randomUUID();
        User user = new User(userId, "user@example.com", passwordEncoder.encode("user123"), Role.USER, true);
        
        usersByEmail.put(admin.getEmail(), admin);
        usersByEmail.put(user.getEmail(), user);
    }

    public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(usersByEmail.get(email));
    }
}

@Singleton
class PostRepository {
    private final Map<UUID, Post> posts = new ConcurrentHashMap<>();

    public Post save(Post post) {
        posts.put(post.getId(), post);
        return post;
    }

    public Optional<Post> findById(UUID id) {
        return Optional.ofNullable(posts.get(id));
    }
}

// --- Service Layer ---

@Singleton
class PostService {
    private final PostRepository postRepository;

    @Inject
    public PostService(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    public Post createPost(String title, String content, UUID userId) {
        Post newPost = new Post(userId, title, content);
        return postRepository.save(newPost);
    }

    public Optional<Post> getPost(UUID id) {
        return postRepository.findById(id);
    }
}

// --- Security: Authentication Provider ---

@Singleton
class UserPasswordAuthProvider implements AuthenticationProvider<HttpRequest<?>> {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Inject
    public UserPasswordAuthProvider(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Publisher<AuthenticationResponse> authenticate(HttpRequest<?> httpRequest, AuthenticationRequest<?, ?> authenticationRequest) {
        String email = authenticationRequest.getIdentity().toString();
        String password = authenticationRequest.getSecret().toString();

        return Mono.create(emitter -> {
            Optional<User> userOptional = userRepository.findByEmail(email);
            if (userOptional.isPresent()) {
                User user = userOptional.get();
                if (user.isActive() && passwordEncoder.matches(password, user.getPasswordHash())) {
                    emitter.success(AuthenticationResponse.success(
                        user.getEmail(),
                        Collections.singletonList(user.getRole().name()),
                        Map.of("userId", user.getId())
                    ));
                } else {
                    emitter.error(new AuthenticationException(new AuthenticationFailed("Invalid credentials or inactive user")));
                }
            } else {
                emitter.error(new AuthenticationException(new AuthenticationFailed("User not found")));
            }
        });
    }
}

// --- Controller Layer ---
// Note: Micronaut Security provides a default /login endpoint that uses the AuthenticationProvider.
// A custom login controller is not needed for standard username/password flow.
// This controller demonstrates securing business endpoints.

@Controller("/posts")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class PostController {
    private final PostService postService;

    @Inject
    public PostController(PostService postService) {
        this.postService = postService;
    }

    @Post
    @Secured("ADMIN")
    public HttpResponse<Post> create(@Body CreatePostRequest request, Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getAttributes().get("userId").toString());
        Post createdPost = postService.createPost(request.title(), request.content(), userId);
        return HttpResponse.created(createdPost);
    }

    @Get("/{id}")
    @Secured({"ADMIN", "USER"})
    public HttpResponse<Post> getById(UUID id, Authentication authentication) {
        // Further authorization (e.g., checking post ownership) could be done in the service layer.
        // For example: if (authentication.getRoles().contains("USER") && !post.getUserId().equals(userId)) ...
        return postService.getPost(id)
                .map(HttpResponse::ok)
                .orElse(HttpResponse.notFound());
    }
}

record CreatePostRequest(String title, String content) {}

/*
--- Necessary application.yml configuration ---
micronaut:
  security:
    enabled: true
    token:
      jwt:
        enabled: true
        signatures:
          secret:
            generator:
              secret: "your-very-long-and-secure-secret-for-jwt"
    endpoints:
      login:
        path: '/login'
      logout:
        path: '/logout'
*/