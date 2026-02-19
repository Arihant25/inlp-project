package com.example.reactive;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.*;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.utils.SecurityService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// --- Domain Models ---

enum Role { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

record User(UUID id, String email, String passwordHash, Role role, boolean isActive, Timestamp createdAt) {}
record Post(UUID id, UUID userId, String title, String content, PostStatus status) {}

// --- Password Hashing ---

interface PwdEncoder {
    String hash(String rawPassword);
    boolean check(String rawPassword, String encodedPassword);
}

@Singleton
class MockBcryptEncoder implements PwdEncoder {
    @Override
    public String hash(String rawPassword) { return "hashed-" + rawPassword; }
    @Override
    public boolean check(String rawPassword, String encodedPassword) { return encodedPassword.equals(hash(rawPassword)); }
}

// --- Data Layer (Reactive Mocks) ---

@Singleton
class ReactiveUserRepo {
    private final Map<String, User> userStore = new ConcurrentHashMap<>();

    public ReactiveUserRepo(PwdEncoder pwdEncoder) {
        UUID adminId = UUID.randomUUID();
        userStore.put("admin@example.com", new User(adminId, "admin@example.com", pwdEncoder.hash("securepass"), Role.ADMIN, true, Timestamp.from(Instant.now())));
        UUID userId = UUID.randomUUID();
        userStore.put("user@example.com", new User(userId, "user@example.com", pwdEncoder.hash("userpass"), Role.USER, true, Timestamp.from(Instant.now())));
    }

    public Mono<User> findByEmail(String email) {
        return Mono.justOrEmpty(userStore.get(email));
    }
}

@Singleton
class ReactivePostRepo {
    private final Map<UUID, Post> postStore = new ConcurrentHashMap<>();

    public Mono<Post> save(Post post) {
        return Mono.fromCallable(() -> {
            postStore.put(post.id(), post);
            return post;
        });
    }

    public Mono<Post> findById(UUID id) {
        return Mono.justOrEmpty(postStore.get(id));
    }
    
    public Flux<Post> findByUserId(UUID userId) {
        return Flux.fromIterable(postStore.values())
                   .filter(p -> p.userId().equals(userId));
    }
}

// --- Security: Reactive Authentication Provider ---

@Singleton
class ReactiveAuthProvider implements AuthenticationProvider<HttpRequest<?>> {

    private final ReactiveUserRepo reactiveUserRepo;
    private final PwdEncoder pwdEncoder;

    public ReactiveAuthProvider(ReactiveUserRepo userRepo, PwdEncoder encoder) {
        this.reactiveUserRepo = userRepo;
        this.pwdEncoder = encoder;
    }

    @Override
    public Publisher<AuthenticationResponse> authenticate(HttpRequest<?> httpRequest, AuthenticationRequest<?, ?> authRequest) {
        String identity = (String) authRequest.getIdentity();
        String secret = (String) authRequest.getSecret();

        return reactiveUserRepo.findByEmail(identity)
            .filter(user -> user.isActive() && pwdEncoder.check(secret, user.passwordHash()))
            .map(user -> AuthenticationResponse.success(
                user.email(),
                Collections.singletonList("ROLE_" + user.role().name()), // Prefixing roles is a common convention
                Map.of("uid", user.id())
            ))
            .switchIfEmpty(Mono.error(new AuthenticationException(new AuthenticationFailed(AuthenticationFailureReason.CREDENTIALS_DO_NOT_MATCH))));
    }
}

// --- Controller Layer (Reactive) ---

@Controller("/v2/posts")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class ReactivePostController {

    private final ReactivePostRepo postRepo;
    private final SecurityService securityService;

    @Inject
    public ReactivePostController(ReactivePostRepo postRepo, SecurityService securityService) {
        this.postRepo = postRepo;
        this.securityService = securityService;
    }

    @Post
    @Secured("ROLE_ADMIN")
    public Mono<HttpResponse<Post>> create(@Body PostCreateDTO dto) {
        return securityService.getAuthentication()
            .flatMap(auth -> {
                UUID userId = UUID.fromString(auth.getAttributes().get("uid").toString());
                Post newPost = new Post(UUID.randomUUID(), userId, dto.title(), dto.content(), PostStatus.DRAFT);
                return postRepo.save(newPost);
            })
            .map(HttpResponse::created);
    }

    @Get("/{id}")
    public Mono<HttpResponse<Post>> getById(UUID id) {
        // In a real app, you'd add reactive authorization logic here.
        // For example, checking if the authenticated user owns this post.
        return postRepo.findById(id)
            .map(HttpResponse::ok)
            .defaultIfEmpty(HttpResponse.notFound());
    }

    @Get("/mine")
    @Secured("ROLE_USER")
    public Flux<Post> getMyPosts() {
        return securityService.getAuthentication()
            .map(auth -> UUID.fromString(auth.getAttributes().get("uid").toString()))
            .flatMapMany(postRepo::findByUserId);
    }
}

record PostCreateDTO(String title, String content) {}

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
              secret: "another-very-long-and-secure-secret-for-jwt"
    endpoints:
      login:
        path: '/v2/login'
*/