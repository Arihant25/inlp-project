package com.example.auth.centric;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
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
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;
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
public class SecurityCentricApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecurityCentricApplication.class, args);
    }
}

// --- Domain Layer ---
package com.example.auth.centric.domain;

enum RoleEnum { ADMIN, USER }
enum StatusEnum { DRAFT, PUBLISHED }

@Entity(name = "users")
class UserEntity {
    @Id private UUID id;
    @Column(unique = true, nullable = false) private String email;
    private String passwordHash;
    @Enumerated(EnumType.STRING) private RoleEnum role;
    private boolean isActive;
    private Timestamp createdAt;

    public UserEntity() {}
    public UserEntity(String email, String passwordHash, RoleEnum role) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.isActive = true;
        this.createdAt = Timestamp.from(Instant.now());
    }
    
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public RoleEnum getRole() { return role; }
    public boolean isActive() { return isActive; }
}

@Entity(name = "posts")
class PostEntity {
    @Id private UUID id;
    private UUID userId;
    private String title;
    private String content;
    @Enumerated(EnumType.STRING) private StatusEnum status;
}

// --- Persistence Layer ---
package com.example.auth.centric.persistence;

@Repository
interface UserPersistence extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByEmail(String email);
}

@Repository
interface PostPersistence extends JpaRepository<PostEntity, UUID> {}

// --- Mock Persistence Implementations ---
@Repository
class MockUserPersistence implements UserPersistence {
    private final Map<String, UserEntity> db = new ConcurrentHashMap<>();
    @Override public Optional<UserEntity> findByEmail(String email) { return Optional.ofNullable(db.get(email)); }
    @Override public <S extends UserEntity> S save(S entity) { db.put(entity.getEmail(), entity); return entity; }
    // Other methods omitted for brevity
    @Override public Optional<UserEntity> findById(UUID uuid) { return db.values().stream().filter(u -> u.getId().equals(uuid)).findFirst(); }
    @Override public List<UserEntity> findAll() { return new ArrayList<>(db.values()); }
    @Override public void deleteById(UUID uuid) { db.values().removeIf(u -> u.getId().equals(uuid)); }
    @Override public long count() { return db.size(); }
    @Override public <S extends UserEntity> List<S> saveAll(Iterable<S> entities) { return null; }
    @Override public void flush() {}
    @Override public <S extends UserEntity> S saveAndFlush(S entity) { return null; }
    @Override public <S extends UserEntity> List<S> saveAllAndFlush(Iterable<S> entities) { return null; }
    @Override public void deleteAllInBatch(Iterable<UserEntity> entities) {}
    @Override public void deleteAllByIdInBatch(Iterable<UUID> uuids) {}
    @Override public void deleteAllInBatch() {}
    @Override public UserEntity getOne(UUID uuid) { return null; }
    @Override public UserEntity getById(UUID uuid) { return null; }
    @Override public UserEntity getReferenceById(UUID uuid) { return null; }
    @Override public <S extends UserEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
    @Override public <S extends UserEntity> List<S> findAll(org.springframework.data.domain.Example<S> example) { return List.of(); }
    @Override public <S extends UserEntity> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return List.of(); }
    @Override public <S extends UserEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return null; }
    @Override public <S extends UserEntity> long count(org.springframework.data.domain.Example<S> example) { return 0; }
    @Override public <S extends UserEntity> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
    @Override public <S extends UserEntity, R> R findBy(org.springframework.data.domain.Example<S> example, Function<org.springframework.data.jpa.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
    @Override public List<UserEntity> findAll(org.springframework.data.domain.Sort sort) { return List.of(); }
    @Override public org.springframework.data.domain.Page<UserEntity> findAll(org.springframework.data.domain.Pageable pageable) { return null; }
    @Override public List<UserEntity> findAllById(Iterable<UUID> uuids) { return List.of(); }
    @Override public boolean existsById(UUID uuid) { return false; }
    @Override public void delete(UserEntity entity) {}
    @Override public void deleteAllById(Iterable<? extends UUID> uuids) {}
    @Override public void deleteAll(Iterable<? extends UserEntity> entities) {}
    @Override public void deleteAll() {}
}

@Component
class SeedData implements CommandLineRunner {
    public SeedData(UserPersistence userPersistence, PasswordEncoder passwordEncoder) {
        userPersistence.save(new UserEntity("admin@example.com", passwordEncoder.encode("secure_admin"), RoleEnum.ADMIN));
        userPersistence.save(new UserEntity("user@example.com", passwordEncoder.encode("secure_user"), RoleEnum.USER));
    }
}

// --- Authentication & Authorization Sub-domain ---
package com.example.auth.centric.auth;

// --- Principal Sub-package ---
record UserDetailsAdapter(UserEntity user) implements UserDetails {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }
    @Override public String getPassword() { return user.getPasswordHash(); }
    @Override public String getUsername() { return user.getEmail(); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return user.isActive(); }
}

@Service("principalUserDetailsService")
class PrincipalUserDetailsService implements UserDetailsService {
    private final UserPersistence userPersistence;
    public PrincipalUserDetailsService(UserPersistence userPersistence) { this.userPersistence = userPersistence; }
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userPersistence.findByEmail(email)
            .map(UserDetailsAdapter::new)
            .orElseThrow(() -> new UsernameNotFoundException("Email not found: " + email));
    }
}

// --- JWT Sub-package ---
@Service
class JwtIssuer {
    private final SecretKey key = Keys.secretKeyFor(SignatureAlgorithm.HS512);
    public String issueToken(String subject, String role) {
        return Jwts.builder()
            .setSubject(subject)
            .claim("role", role)
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + 3600 * 1000))
            .signWith(key)
            .compact();
    }
    public Claims parseToken(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
    }
}

@Component
class JwtValidationFilter extends OncePerRequestFilter {
    private final JwtIssuer jwtIssuer;
    private final UserDetailsService userDetailsService;
    public JwtValidationFilter(JwtIssuer jwtIssuer, UserDetailsService userDetailsService) {
        this.jwtIssuer = jwtIssuer;
        this.userDetailsService = userDetailsService;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        final String authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(req, res);
            return;
        }
        final String token = authHeader.substring(7);
        try {
            Claims claims = jwtIssuer.parseToken(token);
            String username = claims.getSubject();
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        } catch (Exception e) {
            // Token is invalid
        }
        chain.doFilter(req, res);
    }
}

// --- OAuth2 Sub-package ---
@Service
class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private final UserPersistence userPersistence;
    public CustomOAuth2UserService(UserPersistence userPersistence) { this.userPersistence = userPersistence; }
    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) {
        OAuth2User oAuth2User = new DefaultOAuth2UserService().loadUser(userRequest);
        String email = oAuth2User.getAttribute("email");
        userPersistence.findByEmail(email).orElseGet(() -> {
            UserEntity newUser = new UserEntity(email, null, RoleEnum.USER);
            return userPersistence.save(newUser);
        });
        return oAuth2User;
    }
}

// --- Password Sub-package ---
@Service
class PasswordAuthService {
    private final AuthenticationManager authenticationManager;
    private final JwtIssuer jwtIssuer;
    private final UserPersistence userPersistence;

    public PasswordAuthService(AuthenticationManager authenticationManager, JwtIssuer jwtIssuer, UserPersistence userPersistence) {
        this.authenticationManager = authenticationManager;
        this.jwtIssuer = jwtIssuer;
        this.userPersistence = userPersistence;
    }

    public String attemptLogin(String email, String password) {
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(email, password)
        );
        UserEntity user = userPersistence.findByEmail(email).orElseThrow();
        return jwtIssuer.issueToken(user.getEmail(), user.getRole().name());
    }
}

// --- Main Security Configuration ---
package com.example.auth.centric.config;

@Configuration
class ApplicationSecurityConfig {
    @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception { return config.getAuthenticationManager(); }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtValidationFilter jwtValidationFilter, OAuth2UserService<OAuth2UserRequest, OAuth2User> oAuth2UserService) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(jwtValidationFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/posts").hasAnyRole("USER", "ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/posts/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo -> userInfo.userService(oAuth2UserService))
            );
        return http.build();
    }
}

// --- API Layer ---
package com.example.auth.centric.api;

record LoginCredentials(String email, String password) {}
record TokenResponse(String accessToken) {}
record PostSummary(UUID id, String title) {}

@RestController
@RequestMapping("/api/v1/auth")
class AuthenticationResource {
    private final PasswordAuthService passwordAuthService;
    public AuthenticationResource(PasswordAuthService passwordAuthService) { this.passwordAuthService = passwordAuthService; }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginCredentials credentials) {
        try {
            String token = passwordAuthService.attemptLogin(credentials.email(), credentials.password());
            return ResponseEntity.ok(new TokenResponse(token));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }
}

@RestController
@RequestMapping("/api/v1/posts")
class PostResource {
    @GetMapping
    public List<PostSummary> getPosts() {
        return List.of(new PostSummary(UUID.randomUUID(), "Post 1"), new PostSummary(UUID.randomUUID(), "Post 2"));
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(@PathVariable UUID postId) {
        // In a real app, delete logic would be here
        return ResponseEntity.noContent().build();
    }
}