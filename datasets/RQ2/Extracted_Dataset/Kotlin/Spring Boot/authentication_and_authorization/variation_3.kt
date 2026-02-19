package com.example.authdemo.variation3

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.repository.Repository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.filter.OncePerRequestFilter
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

// --- Domain Entities ---
enum class AccessRole { ROLE_ADMIN, ROLE_USER }
enum class ContentStatus { PENDING_REVIEW, PUBLISHED }

@Entity
class UserPrincipal(
    @Id val id: UUID,
    val email: String,
    val passwordHash: String,
    @Enumerated(EnumType.STRING) val role: AccessRole,
    val active: Boolean,
    val createdAt: Timestamp
)

@Entity
class PostContent(
    @Id val id: UUID,
    val authorId: UUID,
    val title: String,
    val body: String,
    @Enumerated(EnumType.STRING) val status: ContentStatus
)

// --- Data Transfer Objects ---
data class AuthenticationRequest(val username: String, val secret: String)
data class AuthenticationResponse(val accessToken: String)
data class PostSummary(val title: String, val status: ContentStatus)

// --- Data Access Layer (In-Memory Mock) ---
@org.springframework.stereotype.Repository
interface UserPrincipalRepository : Repository<UserPrincipal, UUID> {
    fun findByEmail(email: String): UserPrincipal?
    fun save(userPrincipal: UserPrincipal)
}

class InMemoryUserPrincipalRepository : UserPrincipalRepository {
    private val store = ConcurrentHashMap<String, UserPrincipal>()
    override fun findByEmail(email: String): UserPrincipal? = store[email]
    override fun save(userPrincipal: UserPrincipal) { store[userPrincipal.email] = userPrincipal }
}

// --- Security: Core Services ---
@Service("domainUserDetailsServiceV3")
class DomainUserDetailsService(private val userPrincipalRepository: UserPrincipalRepository) : UserDetailsService {
    override fun loadUserByUsername(username: String): UserDetails {
        val user = userPrincipalRepository.findByEmail(username) ?: throw UsernameNotFoundException("Principal not found for email: $username")
        return org.springframework.security.core.userdetails.User(
            user.email, user.passwordHash, user.active, true, true, true,
            listOf(SimpleGrantedAuthority(user.role.name))
        )
    }
}

// --- Security: JWT Handling ---
@Service
class JwtIssuer {
    private val secretKey: SecretKey = Keys.hmacShaKeyFor("AThirdAndVeryRobustSecretKeyForVariationNumber3!".toByteArray())

    fun issueToken(authentication: Authentication): String {
        val now = Instant.now()
        return Jwts.builder()
            .subject(authentication.name)
            .claim("authorities", authentication.authorities.map { it.authority })
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(3600)))
            .signWith(secretKey)
            .compact()
    }

    fun parseToken(token: String): Claims {
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).payload
    }
}

@Component
class JwtAuthenticationFilter(private val jwtIssuer: JwtIssuer) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response)
            return
        }

        val token = authHeader.substring(7)
        try {
            val claims = jwtIssuer.parseToken(token)
            val username = claims.subject
            val authorities = (claims["authorities"] as List<*>).map { SimpleGrantedAuthority(it.toString()) }
            val auth = UsernamePasswordAuthenticationToken(username, null, authorities)
            SecurityContextHolder.getContext().authentication = auth
        } catch (e: Exception) {
            SecurityContextHolder.clearContext()
        }
        filterChain.doFilter(request, response)
    }
}

// --- Security: OAuth2 Handling ---
@Component
class CustomOAuth2SuccessHandler(private val jwtIssuer: JwtIssuer) : AuthenticationSuccessHandler {
    override fun onAuthenticationSuccess(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) {
        if (authentication is OAuth2AuthenticationToken) {
            // In a real app, provision a local user account here.
            // For this example, we'll create a JWT directly from the OAuth2 principal.
            val token = jwtIssuer.issueToken(authentication)
            response.status = HttpStatus.OK.value()
            response.contentType = "application/json"
            response.writer.write("{\"accessToken\": \"$token\"}")
        }
    }
}

// --- Security: Configuration ---
@Configuration
@EnableWebSecurity
class WebSecurityConfiguration(
    private val jwtFilter: JwtAuthenticationFilter,
    private val oauth2Handler: CustomOAuth2SuccessHandler
) {
    @Bean
    fun passwordEncoderV3(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun authenticationManagerV3(config: AuthenticationConfiguration): AuthenticationManager = config.authenticationManager

    @Bean
    fun securityFilterChainV3(http: HttpSecurity): SecurityFilterChain {
        http.cors {}.csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter::class.java)
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/api/v1/auth/**", "/login/**", "/oauth2/**").permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2Login { it.successHandler(oauth2Handler) }
        return http.build()
    }
}

@Configuration
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
class MethodSecurityConfig

// --- Application Services ---
@Service
class PostManagementService {
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    fun reviewAllPosts(): List<PostSummary> {
        // Admin-only logic
        return listOf(PostSummary("Admin Review Post", ContentStatus.PENDING_REVIEW))
    }

    @PreAuthorize("isAuthenticated()")
    fun findMyPosts(): List<PostSummary> {
        val principal = SecurityContextHolder.getContext().authentication.name
        // Logic to get posts for 'principal'
        return listOf(PostSummary("My Post for $principal", ContentStatus.PUBLISHED))
    }
}

// --- Web Layer ---
@RestController
@RequestMapping("/api/v1/auth")
class AuthenticationResource(
    private val authenticationManager: AuthenticationManager,
    private val jwtIssuer: JwtIssuer
) {
    @PostMapping("/token")
    fun generateToken(@RequestBody request: AuthenticationRequest): ResponseEntity<AuthenticationResponse> {
        val auth = authenticationManager.authenticate(UsernamePasswordAuthenticationToken(request.username, request.secret))
        val token = jwtIssuer.issueToken(auth)
        return ResponseEntity.ok(AuthenticationResponse(token))
    }
}

@RestController
@RequestMapping("/api/v1/posts")
class PostResource(private val postService: PostManagementService) {
    @GetMapping("/my-posts")
    fun getMyPosts(): ResponseEntity<List<PostSummary>> {
        return ResponseEntity.ok(postService.findMyPosts())
    }

    @GetMapping("/review")
    fun reviewPosts(): ResponseEntity<List<PostSummary>> {
        return ResponseEntity.ok(postService.reviewAllPosts())
    }
}

// --- Main Application ---
@SpringBootApplication
class AuthDemoApplicationV3 {
    @Bean
    fun userPrincipalRepositoryV3(): UserPrincipalRepository = InMemoryUserPrincipalRepository()

    @Bean
    fun dataInitializer(repo: UserPrincipalRepository, encoder: PasswordEncoder) = CommandLineRunner {
        repo.save(UserPrincipal(UUID.randomUUID(), "user@example.com", encoder.encode("pass"), AccessRole.ROLE_USER, true, Timestamp.from(Instant.now())))
        repo.save(UserPrincipal(UUID.randomUUID(), "admin@example.com", encoder.encode("adminpass"), AccessRole.ROLE_ADMIN, true, Timestamp.from(Instant.now())))
    }
}

fun main(args: Array<String>) {
    runApplication<AuthDemoApplicationV3>(*args)
}