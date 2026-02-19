package com.example.auth.functional;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
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
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

// --- Main Application Class ---
@SpringBootApplication
public class FunctionalAuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(FunctionalAuthApplication.class, args);
    }
}

// --- Domain Model ---
package com.example.auth.functional.domain;

enum Role { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

@Entity
class User implements UserDetails {
    @Id private UUID id;
    private String email;
    private String passwordHash;
    @Enumerated(EnumType.STRING) private Role role;
    private boolean isActive;
    private Timestamp createdAt;

    public User(String email, String passwordHash, Role role) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.isActive = true;
        this.createdAt = Timestamp.from(Instant.now());
    }
    public User() {}

    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.name()));
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
}

// --- Data Transfer Objects (as Records) ---
package com.example.auth.functional.web;

class AuthRecords {
    public record LoginRequest(String email, String password) {}
    public record JwtResponse(String token) {}
    public record PostRecord(UUID id, String title) {}
}

// --- Data Layer ---
package com.example.auth.functional.data;

@Repository
interface UserRepo extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
}

@Repository
interface PostRepo extends JpaRepository<Post, UUID> {}

// --- Mock Data Layer Implementations ---
@Component
class MockUserRepoImpl implements UserRepo {
    private final Map<String, User> userStoreByEmail = new ConcurrentHashMap<>();
    @Override public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(userStoreByEmail.get(email));
    }
    @Override public <S extends User> S save(S entity) {
        userStoreByEmail.put(entity.getEmail(), entity);
        return entity;
    }
    // Other methods omitted for brevity but required for JpaRepository
    @Override public Optional<User> findById(UUID uuid) { return userStoreByEmail.values().stream().filter(u -> u.getId().equals(uuid)).findFirst(); }
    @Override public List<User> findAll() { return new ArrayList<>(userStoreByEmail.values()); }
    @Override public void deleteById(UUID uuid) { userStoreByEmail.values().removeIf(u -> u.getId().equals(uuid)); }
    @Override public long count() { return userStoreByEmail.size(); }
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

@Component
class DataBootstrap implements CommandLineRunner {
    public DataBootstrap(UserRepo userRepo, PasswordEncoder encoder) {
        userRepo.save(new User("admin@example.com", encoder.encode("adminpass"), Role.ADMIN));
        userRepo.save(new User("user@example.com", encoder.encode("userpass"), Role.USER));
    }
}

// --- Security Configuration and Components ---
package com.example.auth.functional.config;

@Service
class AppUserDetailsService implements UserDetailsService {
    private final UserRepo userRepo;
    public AppUserDetailsService(UserRepo userRepo) { this.userRepo = userRepo; }
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepo.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}

@Service
class TokenService {
    private final SecretKey key = Keys.secretKeyFor(SignatureAlgorithm.HS512);

    public String createToken(Authentication auth) {
        return Jwts.builder()
            .setSubject(auth.getName())
            .claim("authorities", auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.joining(",")))
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + 1000 * 60 * 60 * 10))
            .signWith(key)
            .compact();
    }

    public Optional<UsernamePasswordAuthenticationToken> validateToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
            String username = claims.getSubject();
            if (username == null) return Optional.empty();
            
            Collection<? extends GrantedAuthority> authorities =
                Arrays.stream(claims.get("authorities").toString().split(","))
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());

            return Optional.of(new UsernamePasswordAuthenticationToken(username, null, authorities));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}

@Component
class JwtFilter extends OncePerRequestFilter {
    private final TokenService tokenService;
    public JwtFilter(TokenService tokenService) { this.tokenService = tokenService; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        String authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(req, res);
            return;
        }
        String token = authHeader.substring(7);
        tokenService.validateToken(token).ifPresent(authentication -> 
            SecurityContextHolder.getContext().setAuthentication(authentication)
        );
        chain.doFilter(req, res);
    }
}

@Configuration
@EnableWebSecurity
class SecurityConfiguration {
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtFilter jwtFilter) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/login").permitAll()
                .requestMatchers("/api/posts").hasAnyAuthority("USER", "ADMIN")
                .requestMatchers("/api/posts/delete").hasAuthority("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2.defaultSuccessUrl("/api/posts"))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}

// --- Functional Web Layer (Handlers and Router) ---
package com.example.auth.functional.web;

@Component
class AuthHandler {
    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;

    public AuthHandler(AuthenticationManager authenticationManager, TokenService tokenService) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
    }

    public ServerResponse handleLogin(ServerRequest request) throws ServletException, IOException {
        AuthRecords.LoginRequest loginRequest = request.body(AuthRecords.LoginRequest.class);
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(loginRequest.email(), loginRequest.password())
        );
        String token = tokenService.createToken(authentication);
        return ServerResponse.ok().body(new AuthRecords.JwtResponse(token));
    }
}

@Component
class PostHandler {
    // Mocked post data for demonstration
    private final List<AuthRecords.PostRecord> posts = new ArrayList<>(List.of(
        new AuthRecords.PostRecord(UUID.randomUUID(), "Functional Post 1"),
        new AuthRecords.PostRecord(UUID.randomUUID(), "Functional Post 2")
    ));

    public ServerResponse getAllPosts(ServerRequest request) {
        // RBAC is handled by SecurityFilterChain, so we just return the data
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(posts);
    }

    public ServerResponse deletePost(ServerRequest request) {
        // RBAC is handled by SecurityFilterChain
        return ServerResponse.noContent().build();
    }
}

@Configuration
class ApiRoutes {
    @Bean
    public RouterFunction<ServerResponse> route(AuthHandler authHandler, PostHandler postHandler) {
        return RouterFunctions.route()
            .POST("/api/auth/login", authHandler::handleLogin)
            .GET("/api/posts", postHandler::getAllPosts)
            .DELETE("/api/posts/delete", postHandler::deletePost)
            .build();
    }
}