package com.example.authdemo.variation1

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.persistence.*
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.filter.OncePerRequestFilter
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

// --- Domain Model ---
enum class Role { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

@Entity
@Table(name = "users")
data class User(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(unique = true, nullable = false) val email: String,
    @Column(nullable = false) val passwordHash: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val role: Role,
    @Column(nullable = false) val isActive: Boolean = true,
    @Column(nullable = false) val createdAt: Timestamp = Timestamp.from(Instant.now())
)

@Entity
@Table(name = "posts")
data class Post(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val userId: UUID,
    @Column(nullable = false) val title: String,
    @Lob @Column(nullable = false) val content: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val status: PostStatus
)

// --- DTOs ---
data class LoginRequest(val email: String, val password: String)
data class AuthResponse(val token: String)
data class PostDto(val id: UUID, val title: String, val content: String)

// --- In-Memory Repository (for self-contained example) ---
@Repository
interface UserRepository : JpaRepository<User, UUID> {
    fun findByEmail(email: String): User?
}

@Repository
interface PostRepository : JpaRepository<Post, UUID> {
    fun findByUserId(userId: UUID): List<Post>
}

@Component
class InMemoryUserData {
    companion object {
        val users = ConcurrentHashMap<UUID, User>()
        val posts = ConcurrentHashMap<UUID, Post>()
    }
}

@Repository
class InMemoryUserRepository : UserRepository {
    override fun findByEmail(email: String): User? = InMemoryUserData.users.values.find { it.email == email }
    override fun findById(id: UUID): Optional<User> = Optional.ofNullable(InMemoryUserData.users[id])
    override fun save(entity: User): User {
        InMemoryUserData.users[entity.id] = entity
        return entity
    }
    // Mock other JpaRepository methods as needed
    override fun findAll(): List<User> = InMemoryUserData.users.values.toList()
    override fun delete(entity: User) { InMemoryUserData.users.remove(entity.id) }
    override fun <S : User?> saveAll(entities: MutableIterable<S>): MutableList<S> { throw NotImplementedError() }
    override fun flush() {}
    override fun getOne(id: UUID): User { throw NotImplementedError() }
    override fun getById(id: UUID): User = findById(id).orElseThrow()
    override fun deleteById(id: UUID) { InMemoryUserData.users.remove(id) }
    override fun deleteAll() { InMemoryUserData.users.clear() }
    override fun count(): Long = InMemoryUserData.users.size.toLong()
    override fun existsById(id: UUID): Boolean = InMemoryUserData.users.containsKey(id)
    override fun findAllById(ids: MutableIterable<UUID>): MutableList<User> { throw NotImplementedError() }
    override fun deleteAllById(ids: MutableIterable<UUID>) { throw NotImplementedError() }
    override fun deleteAll(entities: MutableIterable<in User>) { throw NotImplementedError() }
}

// --- Security Components ---
@Service
class UserDetailsServiceImpl(private val userRepository: UserRepository) : UserDetailsService {
    override fun loadUserByUsername(username: String): UserDetails {
        val user = userRepository.findByEmail(username) ?: throw UsernameNotFoundException("User not found with email: $username")
        return org.springframework.security.core.userdetails.User(
            user.email,
            user.passwordHash,
            user.isActive,
            true,
            true,
            true,
            listOf(SimpleGrantedAuthority("ROLE_${user.role.name}"))
        )
    }
}

@Component
class JwtTokenProvider {
    private val secretKey: SecretKey = Keys.hmacShaKeyFor("YourSuperSecretKeyForVariation1ThatIsLongEnough".toByteArray())
    private val validityInMilliseconds: Long = 3600000 // 1h

    fun createToken(authentication: Authentication): String {
        val username = authentication.name
        val authorities = authentication.authorities.map { it.authority }
        val now = Date()
        val validity = Date(now.time + validityInMilliseconds)

        return Jwts.builder()
            .subject(username)
            .claim("auth", authorities)
            .issuedAt(now)
            .expiration(validity)
            .signWith(secretKey)
            .compact()
    }

    fun getAuthentication(token: String): Authentication? {
        return try {
            val claims: Claims = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).payload
            val authorities = (claims["auth"] as List<*>).map { SimpleGrantedAuthority(it.toString()) }
            val principal = org.springframework.security.core.userdetails.User(claims.subject, "", authorities)
            UsernamePasswordAuthenticationToken(principal, token, authorities)
        } catch (e: Exception) {
            null // Invalid token
        }
    }

    fun validateToken(token: String): Boolean {
        return try {
            Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token)
            true
        } catch (e: Exception) {
            false
        }
    }
}

@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val userDetailsService: UserDetailsService
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val token = resolveToken(request)
        if (token != null && jwtTokenProvider.validateToken(token)) {
            val auth = jwtTokenProvider.getAuthentication(token)
            if (auth != null) {
                org.springframework.security.core.context.SecurityContextHolder.getContext().authentication = auth
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun resolveToken(req: HttpServletRequest): String? {
        val bearerToken = req.getHeader("Authorization")
        return if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken.substring(7)
        } else {
            null
        }
    }
}

@Component
class OAuth2LoginSuccessHandler(private val jwtTokenProvider: JwtTokenProvider) : AuthenticationSuccessHandler {
    override fun onAuthenticationSuccess(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) {
        // For OAuth2, we create a JWT for our API.
        // In a real app, you'd link the OAuth2 user to a local user account.
        val oAuth2User = authentication.principal as DefaultOAuth2User
        val email = oAuth2User.attributes["email"] as String

        // Create a mock authentication object to generate a token
        val mockAuth = UsernamePasswordAuthenticationToken(email, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        val token = jwtTokenProvider.createToken(mockAuth)
        
        response.addHeader("Authorization", "Bearer $token")
        response.writer.write("{\"token\": \"$token\"}")
        response.status = HttpServletResponse.SC_OK
    }
}


// --- Configuration ---
@Configuration
@EnableWebSecurity
class SecurityConfiguration(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val oAuth2LoginSuccessHandler: OAuth2LoginSuccessHandler
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun authenticationManager(authenticationConfiguration: AuthenticationConfiguration): AuthenticationManager {
        return authenticationConfiguration.authenticationManager
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/auth/**", "/oauth2/**", "/login/**").permitAll()
                    .requestMatchers("/api/posts/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated()
            }
            .oauth2Login { oauth2 ->
                oauth2.successHandler(oAuth2LoginSuccessHandler)
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }
}

// --- Service Layer ---
@Service
class AuthenticationService(
    private val authenticationManager: AuthenticationManager,
    private val jwtTokenProvider: JwtTokenProvider,
    private val userRepository: UserRepository
) {
    fun login(loginRequest: LoginRequest): String {
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken(loginRequest.email, loginRequest.password)
        )
        return jwtTokenProvider.createToken(authentication)
    }
}

// --- Controller Layer ---
@RestController
@RequestMapping("/api/auth")
class AuthenticationController(private val authenticationService: AuthenticationService) {

    @PostMapping("/login")
    fun login(@RequestBody loginRequest: LoginRequest): ResponseEntity<AuthResponse> {
        val token = authenticationService.login(loginRequest)
        return ResponseEntity.ok(AuthResponse(token))
    }
}

@RestController
@RequestMapping("/api/posts")
class PostController {
    @GetMapping
    fun getUserPosts(): ResponseEntity<List<PostDto>> {
        // In a real app, get user from SecurityContext and fetch their posts
        val posts = listOf(PostDto(UUID.randomUUID(), "My First Post", "Content for user."))
        return ResponseEntity.ok(posts)
    }

    @GetMapping("/admin/all")
    fun getAllPosts(): ResponseEntity<List<PostDto>> {
        // Admin-only endpoint
        val allPosts = listOf(
            PostDto(UUID.randomUUID(), "Admin Post 1", "Content..."),
            PostDto(UUID.randomUUID(), "Admin Post 2", "Content...")
        )
        return ResponseEntity.ok(allPosts)
    }
}

// --- Main Application ---
@SpringBootApplication
class AuthDemoApplicationV1 {
    @Bean
    fun init(userRepository: UserRepository, passwordEncoder: PasswordEncoder) = CommandLineRunner {
        userRepository.save(
            User(
                email = "user@example.com",
                passwordHash = passwordEncoder.encode("password"),
                role = Role.USER
            )
        )
        userRepository.save(
            User(
                email = "admin@example.com",
                passwordHash = passwordEncoder.encode("adminpass"),
                role = Role.ADMIN
            )
        )
    }
}

fun main(args: Array<String>) {
    runApplication<AuthDemoApplicationV1>(*args)
}