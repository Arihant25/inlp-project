package com.example.authdemo.variation4

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.annotation.Id
import org.springframework.data.repository.CrudRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter
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

// --- Data Model ---
enum class Role { ADMIN, USER }
enum class Status { DRAFT, PUBLISHED }

data class UserEntity(
    @Id val id: UUID = UUID.randomUUID(),
    val email: String,
    val passHash: String,
    val role: Role,
    val active: Boolean = true,
    val created: Timestamp = Timestamp.from(Instant.now())
)

data class PostEntity(
    @Id val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val title: String,
    val content: String,
    val status: Status
)

// --- In-Memory DB ---
@Repository
interface UserRepo : CrudRepository<UserEntity, UUID> {
    fun findByEmail(email: String): UserEntity?
}

@Component
class Db(private val encoder: PasswordEncoder) {
    val users = ConcurrentHashMap<String, UserEntity>()
    
    @PostConstruct
    fun init() {
        val user = UserEntity(email = "user@example.com", passHash = encoder.encode("password"), role = Role.USER)
        val admin = UserEntity(email = "admin@example.com", passHash = encoder.encode("admin"), role = Role.ADMIN)
        users[user.email] = user
        users[admin.email] = admin
    }
}

// --- Auth Utilities ---
object JwtHelper {
    private val key: SecretKey = Keys.hmacShaKeyFor("ThisIsAVerySimpleAndPragmaticSecretKeyForV4".toByteArray())
    private const val PREFIX = "Bearer "

    fun generate(auth: Authentication): String {
        val now = Date()
        return Jwts.builder()
            .subject(auth.name)
            .claim("roles", auth.authorities.joinToString(",") { it.authority })
            .issuedAt(now)
            .expiration(Date(now.time + 3_600_000))
            .signWith(key)
            .compact()
    }

    fun validateAndGetAuth(req: HttpServletRequest): Authentication? {
        val header = req.getHeader("Authorization") ?: return null
        if (!header.startsWith(PREFIX)) return null
        
        return try {
            val token = header.replace(PREFIX, "")
            val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
            val roles = claims["roles"].toString().split(",").map { SimpleGrantedAuthority(it) }
            UsernamePasswordAuthenticationToken(claims.subject, null, roles)
        } catch (e: Exception) {
            null
        }
    }
}

// --- Security Config ---
@Configuration
class SecurityConf {
    @Bean fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean fun authManager(config: AuthenticationConfiguration): AuthenticationManager = config.authenticationManager

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        val jwtAuthFilter = object : OncePerRequestFilter() {
            override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
                SecurityContextHolder.getContext().authentication = JwtHelper.validateAndGetAuth(req)
                chain.doFilter(req, res)
            }
        }
        
        val oauthSuccessHandler = AuthenticationSuccessHandler { _, res, auth ->
            val principal = auth.principal as DefaultOAuth2User
            val email = principal.attributes["email"] as String
            val mockAuth = UsernamePasswordAuthenticationToken(email, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
            val token = JwtHelper.generate(mockAuth)
            res.writer.write("{\"token\":\"$token\"}")
            res.contentType = "application/json"
        }

        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(jwtAuthFilter, BasicAuthenticationFilter::class.java)
            .authorizeHttpRequests {
                it.requestMatchers("/api/login", "/login/**", "/oauth2/**").permitAll()
                  .requestMatchers("/api/admin/**").hasRole("ADMIN")
                  .anyRequest().authenticated()
            }
            .oauth2Login { it.successHandler(oauthSuccessHandler) }
        
        return http.build()
    }
}

@Service
class AppUserDetailsService(private val db: Db) : UserDetailsService {
    override fun loadUserByUsername(username: String): User {
        val user = db.users[username] ?: throw UsernameNotFoundException("Not found")
        return User(user.email, user.passHash, listOf(SimpleGrantedAuthority("ROLE_${user.role.name}")))
    }
}

// --- API Controllers ---
@RestController
@RequestMapping("/api")
class AuthController(private val authManager: AuthenticationManager) {
    data class Login(val email: String, val pass: String)
    data class Token(val token: String)

    @PostMapping("/login")
    fun login(@RequestBody creds: Login): ResponseEntity<Token> {
        val auth = authManager.authenticate(UsernamePasswordAuthenticationToken(creds.email, creds.pass))
        return ResponseEntity.ok(Token(JwtHelper.generate(auth)))
    }
}

@RestController
@RequestMapping("/api")
class PostController {
    @GetMapping("/posts")
    fun getPosts(): List<String> {
        val principal = SecurityContextHolder.getContext().authentication
        return listOf("Post 1 for ${principal.name}", "Post 2 for ${principal.name}")
    }

    @GetMapping("/admin/posts")
    fun getAdminPosts(): List<String> {
        return listOf("Admin Post A", "Admin Post B")
    }
}

// --- Application Entrypoint ---
@SpringBootApplication
class AuthDemoApplicationV4

fun main(args: Array<String>) {
    runApplication<AuthDemoApplicationV4>(*args)
}