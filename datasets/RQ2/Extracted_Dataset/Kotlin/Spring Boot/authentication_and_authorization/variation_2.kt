package com.example.authdemo.variation2

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
import org.springframework.data.repository.CrudRepository
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.user.OAuth2User
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

// --- Domain ---
enum class UserRole { ADMIN, USER }
enum class PublicationStatus { DRAFT, PUBLISHED }

@Entity data class AppUser(
    @Id val id: UUID = UUID.randomUUID(),
    val email: String,
    val passwordHash: String,
    @Enumerated(EnumType.STRING) val role: UserRole,
    val isActive: Boolean = true,
    val createdAt: Timestamp = Timestamp.from(Instant.now())
)

@Entity data class Post(
    @Id val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val title: String,
    val content: String,
    @Enumerated(EnumType.STRING) val status: PublicationStatus
)

// --- DTOs ---
data class LoginReq(val email: String, val password: String)
data class TokenResp(val jwt: String)
data class PostView(val title: String, val content: String)

// --- Data Layer (In-Memory) ---
@Repository
interface UserRepo : CrudRepository<AppUser, UUID> {
    fun findByEmail(email: String): AppUser?
}

@Component
class InMemoryUserStore {
    private val users = ConcurrentHashMap<String, AppUser>()
    fun save(user: AppUser): AppUser {
        users[user.email] = user
        return user
    }
    fun findByEmail(email: String): AppUser? = users[email]
}

// --- Security Services ---
@Service
class AppUserDetailsService(private val userStore: InMemoryUserStore) : UserDetailsService {
    override fun loadUserByUsername(username: String): User {
        val appUser = userStore.findByEmail(username) ?: throw UsernameNotFoundException("Cannot find user: $username")
        return User(appUser.email, appUser.passwordHash, listOf(SimpleGrantedAuthority("ROLE_${appUser.role.name}")))
    }
}

@Service
class JwtService {
    companion object {
        private val SECRET_KEY: SecretKey = Keys.hmacShaKeyFor("A-Different-Secret-Key-For-Variation-2-That-Is-Also-Secure".toByteArray())
        private const val EXPIRATION_MS: Long = 86400000 // 24 hours
    }

    fun generateToken(auth: Authentication): String {
        val now = Date()
        return Jwts.builder()
            .subject(auth.name)
            .claim("roles", auth.authorities.map { it.authority })
            .issuedAt(now)
            .expiration(Date(now.time + EXPIRATION_MS))
            .signWith(SECRET_KEY)
            .compact()
    }

    fun getAuthentication(token: String): Authentication? {
        return try {
            val claims = Jwts.parser().verifyWith(SECRET_KEY).build().parseSignedClaims(token).payload
            val roles = (claims["roles"] as List<*>).map { SimpleGrantedAuthority(it.toString()) }
            val user = User(claims.subject, "", roles)
            UsernamePasswordAuthenticationToken(user, token, roles)
        } catch (e: Exception) {
            null
        }
    }
}

@Component
class TokenFilter(private val jwtService: JwtService) : OncePerRequestFilter() {
    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        req.getHeader(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.startsWith("Bearer ") }
            ?.substring(7)
            ?.let { token -> jwtService.getAuthentication(token) }
            ?.let { auth -> SecurityContextHolder.getContext().authentication = auth }
        chain.doFilter(req, res)
    }
}

@Component
class OAuth2SuccessHandler(private val jwtService: JwtService) : AuthenticationSuccessHandler {
    override fun onAuthenticationSuccess(req: HttpServletRequest, res: HttpServletResponse, auth: Authentication) {
        val principal = auth.principal as OAuth2User
        val email = principal.attributes["email"] as String
        // In a real app, find or create a local user based on this email
        val tempAuth = UsernamePasswordAuthenticationToken(email, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        val token = jwtService.generateToken(tempAuth)
        res.addHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
        res.sendRedirect("/?token=$token") // Redirect with token for SPA
    }
}

// --- Configuration ---
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfig(
    private val tokenFilter: TokenFilter,
    private val oauth2SuccessHandler: OAuth2SuccessHandler
) {

    @Bean fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean fun authenticationManager(authConfig: AuthenticationConfiguration): AuthenticationManager = authConfig.authenticationManager

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            cors { }
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/api/auth/**", permitAll)
                authorize("/login/**", permitAll)
                authorize("/oauth2/**", permitAll)
                authorize(anyRequest, authenticated)
            }
            oauth2Login {
                authenticationSuccessHandler = oauth2SuccessHandler
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(tokenFilter)
        }
        return http.build()
    }
}

// --- API Layer ---
@RestController
@RequestMapping("/api/auth")
class AuthApi(
    private val authenticationManager: AuthenticationManager,
    private val jwtService: JwtService
) {
    @PostMapping("/login")
    fun login(@RequestBody req: LoginReq): ResponseEntity<TokenResp> {
        val auth = authenticationManager.authenticate(UsernamePasswordAuthenticationToken(req.email, req.password))
        val token = jwtService.generateToken(auth)
        return ResponseEntity.ok(TokenResp(token))
    }
}

@RestController
@RequestMapping("/api/posts")
class PostsApi {
    @GetMapping("/my-posts")
    fun getMyPosts(): ResponseEntity<List<PostView>> {
        val user = SecurityContextHolder.getContext().authentication.principal as User
        // Logic to fetch posts for user.name
        return ResponseEntity.ok(listOf(PostView("Post for ${user.username}", "My content.")))
    }

    @GetMapping("/all")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    fun getAllPosts(): ResponseEntity<List<PostView>> {
        return ResponseEntity.ok(listOf(PostView("Admin View Post", "Sensitive content.")))
    }
}

// --- Application ---
@SpringBootApplication
class AuthDemoApplicationV2 {
    @Bean
    fun seedDatabase(userStore: InMemoryUserStore, encoder: PasswordEncoder) = CommandLineRunner {
        userStore.save(AppUser(email = "user@example.com", passwordHash = encoder.encode("password123"), role = UserRole.USER))
        userStore.save(AppUser(email = "admin@example.com", passwordHash = encoder.encode("admin123"), role = UserRole.ADMIN))
    }
}

fun main(args: Array<String>) {
    runApplication<AuthDemoApplicationV2>(*args)
}