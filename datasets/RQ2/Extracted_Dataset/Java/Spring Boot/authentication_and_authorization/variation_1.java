package com.example.auth.classic;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

// --- Main Application Class ---
@SpringBootApplication
public class ClassicAuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClassicAuthApplication.class, args);
    }
}

// --- Domain Model ---
package com.example.auth.classic.model;

enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

@Entity
class User implements UserDetails {
    @Id private UUID id;
    private String email;
    private String passwordHash;
    @Enumerated(EnumType.STRING) private UserRole role;
    private boolean isActive;
    private Timestamp createdAt;

    // Constructors, Getters, Setters
    public User() {}
    public User(UUID id, String email, String passwordHash, UserRole role) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.isActive = true;
        this.createdAt = Timestamp.from(Instant.now());
    }
    
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return isActive; }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
}

@Entity
class Post {
    @Id private UUID id;
    private UUID userId;
    private String title;
    private String content;
    @Enumerated(EnumType.STRING) private PostStatus status;
    
    // Getters & Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
}

// --- Data Transfer Objects (DTOs) ---
package com.example.auth.classic.dto;

class AuthRequest {
    private String email;
    private String password;
    public String getEmail() { return email; }
    public String getPassword() { return password; }
}

class AuthResponse {
    private String token;
    public AuthResponse(String token) { this.token = token; }
    public String getToken() { return token; }
}

class PostDto {
    private UUID id;
    private String title;
    public PostDto(UUID id, String title) { this.id = id; this.title = title; }
    public UUID getId() { return id; }
    public String getTitle() { return title; }
}

// --- Repository Layer ---
package com.example.auth.classic.repository;

@Repository
interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
}

@Repository
interface PostRepository extends JpaRepository<Post, UUID> {}

// --- Mock Repository Implementations for Compilability ---
@Component
class MockDataInitializer implements CommandLineRunner {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public MockDataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        User admin = new User(UUID.randomUUID(), "admin@example.com", passwordEncoder.encode("admin123"), UserRole.ADMIN);
        User user = new User(UUID.randomUUID(), "user@example.com", passwordEncoder.encode("user123"), UserRole.USER);
        ((MockUserRepositoryImpl) userRepository).save(admin);
        ((MockUserRepositoryImpl) userRepository).save(user);
    }
}

@Repository
class MockUserRepositoryImpl implements UserRepository {
    private final Map<UUID, User> userStore = new ConcurrentHashMap<>();
    @Override public Optional<User> findByEmail(String email) {
        return userStore.values().stream().filter(u -> u.getEmail().equals(email)).findFirst();
    }
    @Override public <S extends User> S save(S entity) {
        if (entity.getId() == null) {
            // This is a mock, in reality, we would not cast
            ((User)entity).id = UUID.randomUUID();
        }
        userStore.put(entity.getId(), entity);
        return entity;
    }
    // Implement other JpaRepository methods as needed for the example to compile
    @Override public Optional<User> findById(UUID uuid) { return Optional.ofNullable(userStore.get(uuid)); }
    @Override public List<User> findAll() { return new ArrayList<>(userStore.values()); }
    @Override public void deleteById(UUID uuid) { userStore.remove(uuid); }
    @Override public long count() { return userStore.size(); }
    @Override public <S extends User> List<S> saveAll(Iterable<S> entities) { return null; }
    @Override public void flush() {}
    @Override public <S extends User> S saveAndFlush(S entity) { return null; }
    @Override public <S extends User> List<S> saveAllAndFlush(Iterable<S> entities) { return null; }
    @Override public void deleteAllInBatch(Iterable<User> entities) {}
    @Override public void deleteAllByIdInBatch(Iterable<UUID> uuids) {}
    @Override public void deleteAllInBatch() {}
    @Override public User getOne(UUID uuid) { return null; }
    @Override public User getById(UUID uuid) { return null; }
    @Override public User getReferenceById(UUID uuid) { return null; }
    @Override public <S extends User> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
    @Override public <S extends User> List<S> findAll(org.springframework.data.domain.Example<S> example) { return List.of(); }
    @Override public <S extends User> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return List.of(); }
    @Override public <S extends User> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return null; }
    @Override public <S extends User> long count(org.springframework.data.domain.Example<S> example) { return 0; }
    @Override public <S extends User> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
    @Override public <S extends User, R> R findBy(org.springframework.data.domain.Example<S> example, Function<org.springframework.data.jpa.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
    @Override public List<User> findAll(org.springframework.data.domain.Sort sort) { return List.of(); }
    @Override public org.springframework.data.domain.Page<User> findAll(org.springframework.data.domain.Pageable pageable) { return null; }
    @Override public List<User> findAllById(Iterable<UUID> uuids) { return List.of(); }
    @Override public boolean existsById(UUID uuid) { return false; }
    @Override public void delete(User entity) {}
    @Override public void deleteAllById(Iterable<? extends UUID> uuids) {}
    @Override public void deleteAll(Iterable<? extends User> entities) {}
    @Override public void deleteAll() {}
}

// --- Security Layer ---
package com.example.auth.classic.security;

@Service
class UserDetailsServiceImpl implements UserDetailsService {
    private final UserRepository userRepository;
    public UserDetailsServiceImpl(UserRepository userRepository) { this.userRepository = userRepository; }
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));
    }
}

@Component
class JwtTokenProvider {
    private static final SecretKey SECRET_KEY = Keys.secretKeyFor(SignatureAlgorithm.HS512);
    private static final long EXPIRATION_TIME = 864_000_000; // 10 days

    public String generateToken(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        return Jwts.builder()
                .setSubject(userDetails.getUsername())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(SECRET_KEY)
                .compact();
    }

    public String getUsernameFromToken(String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }

    public boolean validateToken(String token, UserDetails userDetails) {
        final String username = getUsernameFromToken(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }

    private <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = Jwts.parserBuilder().setSigningKey(SECRET_KEY).build().parseClaimsJws(token).getBody();
        return claimsResolver.apply(claims);
    }

    private boolean isTokenExpired(String token) {
        final Date expiration = getClaimFromToken(token, Claims::getExpiration);
        return expiration.before(new Date());
    }
}

@Component
class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenProvider tokenProvider;
    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, UserDetailsService userDetailsService) {
        this.tokenProvider = tokenProvider;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String username = null;
        String authToken = null;
        if (header != null && header.startsWith("Bearer ")) {
            authToken = header.substring(7);
            try {
                username = tokenProvider.getUsernameFromToken(authToken);
            } catch (Exception e) {
                logger.warn("An error occurred while parsing JWT token", e);
            }
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            if (tokenProvider.validateToken(authToken, userDetails)) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }
}

@Configuration
@EnableWebSecurity
@EnableMethodSecurity // Enables @PreAuthorize
class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**", "/oauth2/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2.defaultSuccessUrl("/api/posts"))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

// --- Service Layer ---
package com.example.auth.classic.service;

@Service
class AuthenticationService {
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;

    public AuthenticationService(AuthenticationManager authenticationManager, JwtTokenProvider tokenProvider) {
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
    }

    public String authenticateAndGetToken(String email, String password) {
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(email, password)
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return tokenProvider.generateToken(authentication);
    }
}

// --- Controller Layer ---
package com.example.auth.classic.controller;

@RestController
@RequestMapping("/api/auth")
class AuthenticationController {
    private final AuthenticationService authenticationService;

    public AuthenticationController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest authRequest) {
        String token = authenticationService.authenticateAndGetToken(authRequest.getEmail(), authRequest.getPassword());
        return ResponseEntity.ok(new AuthResponse(token));
    }
}

@RestController
@RequestMapping("/api/posts")
class PostController {
    // Mocked post data for demonstration
    private final List<PostDto> posts = new ArrayList<>(List.of(
        new PostDto(UUID.randomUUID(), "First Post"),
        new PostDto(UUID.randomUUID(), "Second Post")
    ));

    @GetMapping
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<List<PostDto>> getAllPosts() {
        return ResponseEntity.ok(posts);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<PostDto> createPost(@RequestBody PostDto newPost) {
        posts.add(newPost);
        return ResponseEntity.ok(newPost);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deletePost(@PathVariable UUID id) {
        posts.removeIf(p -> p.getId().equals(id));
        return ResponseEntity.noContent().build();
    }
}