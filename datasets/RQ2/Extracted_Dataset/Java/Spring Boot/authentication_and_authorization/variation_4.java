package com.example.auth.pragmatic;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
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

@SpringBootApplication
public class PragmaticAuthApp {
    public static void main(String[] args) {
        SpringApplication.run(PragmaticAuthApp.class, args);
    }
}

// --- Data Layer (with Lombok) ---
package com.example.auth.pragmatic.data;

enum Role { ADMIN, USER }
enum Status { DRAFT, PUBLISHED }

@Data @NoArgsConstructor @AllArgsConstructor
@Entity
class User implements UserDetails {
    @Id private UUID id;
    private String email;
    private String passwordHash;
    @Enumerated(EnumType.STRING) private Role role;
    private boolean isActive;
    private Timestamp createdAt;

    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return isActive; }
}

@Data @NoArgsConstructor @AllArgsConstructor
@Entity
class Post {
    @Id private UUID id;
    private UUID userId;
    private String title;
    private String content;
    @Enumerated(EnumType.STRING) private Status status;
}

@Repository
interface UserRepo extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
}

// --- Mock Repo Implementation ---
@Repository
class MockUserRepo implements UserRepo {
    private final Map<UUID, User> store = new ConcurrentHashMap<>();
    @Override public Optional<User> findByEmail(String email) {
        return store.values().stream().filter(u -> u.getEmail().equals(email)).findFirst();
    }
    @Override public <S extends User> S save(S entity) {
        if (entity.getId() == null) entity.setId(UUID.randomUUID());
        store.put(entity.getId(), entity);
        return entity;
    }
    // Other methods omitted for brevity
    @Override public Optional<User> findById(UUID uuid) { return Optional.ofNullable(store.get(uuid)); }
    @Override public List<User> findAll() { return new ArrayList<>(store.values()); }
    @Override public void deleteById(UUID uuid) { store.remove(uuid); }
    @Override public long count() { return store.size(); }
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
package com.example.auth.pragmatic.security;

@Service
@RequiredArgsConstructor
class UserDetailsSvc implements UserDetailsService {
    private final UserRepo userRepo;
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepo.findByEmail(username).orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}

@Component
class JwtProvider {
    private final SecretKey SECRET = Keys.secretKeyFor(SignatureAlgorithm.HS512);
    private final long validityInMs = 3600000; // 1h

    public String createToken(String username, Role role) {
        Claims claims = Jwts.claims().setSubject(username);
        claims.put("auth", List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        Date now = new Date();
        return Jwts.builder()
            .setClaims(claims)
            .setIssuedAt(now)
            .setExpiration(new Date(now.getTime() + validityInMs))
            .signWith(SECRET)
            .compact();
    }

    public Optional<Authentication> getAuthentication(String token) {
        try {
            Claims claims = Jwts.parserBuilder().setSigningKey(SECRET).build().parseClaimsJws(token).getBody();
            String username = claims.getSubject();
            // In a real app, you'd load UserDetails from DB here to ensure user is still valid
            List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + Role.USER.name())); // Simplified for example
            return Optional.of(new UsernamePasswordAuthenticationToken(username, "", authorities));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}

@Component
@RequiredArgsConstructor
class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtProvider jwtProvider;
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException {
        try {
            String token = Optional.ofNullable(req.getHeader("Authorization"))
                .filter(h -> h.startsWith("Bearer "))
                .map(h -> h.substring(7))
                .orElse(null);

            if (token != null) {
                jwtProvider.getAuthentication(token)
                    .ifPresent(auth -> SecurityContextHolder.getContext().setAuthentication(auth));
            }
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
        }
        try {
            chain.doFilter(req, res);
        } catch (ServletException e) {
            throw new RuntimeException(e);
        }
    }
}

@Configuration
@RequiredArgsConstructor
class SecConfig {
    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/login", "/login/oauth2/**").permitAll()
                .requestMatchers("/api/posts/admin/**").hasRole("ADMIN")
                .anyRequest().hasAnyRole("USER", "ADMIN")
            )
            .oauth2Login(c -> c.defaultSuccessUrl("/api/posts"));
        return http.build();
    }
}

// --- Web Layer (Consolidated Controller) ---
package com.example.auth.pragmatic.web;

class AuthDto {
    @Data static class Login { private String email; private String password; }
    @Data @AllArgsConstructor static class Token { private String jwt; }
    @Data @AllArgsConstructor static class PostView { private UUID id; private String title; }
}

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {
    private final AuthenticationManager authManager;
    private final JwtProvider jwtProvider;
    private final UserRepo userRepo;

    @PostMapping("/login")
    public ResponseEntity<AuthDto.Token> login(@RequestBody AuthDto.Login loginDetails) {
        authManager.authenticate(new UsernamePasswordAuthenticationToken(loginDetails.getEmail(), loginDetails.getPassword()));
        User user = userRepo.findByEmail(loginDetails.getEmail()).orElseThrow();
        String token = jwtProvider.createToken(user.getEmail(), user.getRole());
        return ResponseEntity.ok(new AuthDto.Token(token));
    }

    @GetMapping("/posts")
    public ResponseEntity<List<AuthDto.PostView>> getPosts() {
        // Mock data
        return ResponseEntity.ok(List.of(
            new AuthDto.PostView(UUID.randomUUID(), "My First Post"),
            new AuthDto.PostView(UUID.randomUUID(), "Another Post")
        ));
    }

    @DeleteMapping("/posts/admin/{id}")
    public ResponseEntity<Void> deletePost(@PathVariable UUID id) {
        // Admin-only logic
        System.out.println("Admin deleted post " + id);
        return ResponseEntity.noContent().build();
    }
}

// --- Data Seeder ---
@Component
@RequiredArgsConstructor
class DbSeeder implements CommandLineRunner {
    private final UserRepo userRepo;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        userRepo.save(new User(UUID.randomUUID(), "admin@test.com", passwordEncoder.encode("pass"), Role.ADMIN, true, Timestamp.from(Instant.now())));
        userRepo.save(new User(UUID.randomUUID(), "user@test.com", passwordEncoder.encode("pass"), Role.USER, true, Timestamp.from(Instant.now())));
    }
}