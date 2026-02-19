<pre>
// Developer 1: The "Classic" Layered Architect
// Style: Traditional, by-the-book Spring Boot architecture with clear separation of concerns (Controller, Service, Repository, DTO).
// Querying: Uses JPA Specification for dynamic, type-safe queries.
// Migrations: Flyway

// --- build.gradle.kts ---
/*
plugins {
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.4"
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.spring") version "1.9.23"
    kotlin("plugin.jpa") version "1.9.23"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("com.h2database:h2")
}

tasks.withType&lt;org.jetbrains.kotlin.gradle.tasks.KotlinCompile&gt; {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "17"
    }
}
*/

// --- src/main/resources/application.properties ---
/*
spring.datasource.url=jdbc:h2:mem:testdb
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=password
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=validate
spring.h2.console.enabled=true
*/

// --- src/main/resources/db/migration/V1__Initial_Schema.sql ---
/*
CREATE TABLE roles (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
);

CREATE TABLE posts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Mock Data
INSERT INTO roles (id, name) VALUES (gen_random_uuid(), 'ADMIN'), (gen_random_uuid(), 'USER');
*/

package com.example.variation1;

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.*

// --- Main Application ---
@SpringBootApplication
class ClassicApp

fun main(args: Array&lt;String&gt;) {
    runApplication&lt;ClassicApp&gt;(*args)
}

// --- Domain Model ---
enum class PostStatus { DRAFT, PUBLISHED }

@Entity
@Table(name = "roles")
data class Role(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, unique = true)
    val name: String
)

@Entity
@Table(name = "users")
data class User(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, unique = true)
    var email: String,

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    val posts: MutableList&lt;Post&gt; = mutableListOf(),

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_roles",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "role_id")]
    )
    var roles: MutableSet&lt;Role&gt; = mutableSetOf()
)

@Entity
@Table(name = "posts")
data class Post(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    var title: String,

    @Lob
    @Column(nullable = false)
    var content: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PostStatus = PostStatus.DRAFT,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User
)

// --- DTOs (Data Transfer Objects) ---
data class UserDto(
    val id: UUID,
    val email: String,
    val isActive: Boolean,
    val roles: Set&lt;String&gt;,
    val postCount: Int
)

data class CreateUserRequest(
    val email: String,
    val password: String,
    val roleNames: Set&lt;String&gt;
)

data class PostDto(
    val id: UUID,
    val title: String,
    val status: PostStatus
)

// --- Repositories ---
interface UserRepository : JpaRepository&lt;User, UUID&gt;, JpaSpecificationExecutor&lt;User&gt;
interface PostRepository : JpaRepository&lt;Post, UUID&gt;
interface RoleRepository : JpaRepository&lt;Role, UUID&gt; {
    fun findByNameIn(names: Collection&lt;String&gt;): Set&lt;Role&gt;
}

// --- Specifications for Filtering ---
object UserSpecification {
    fun isActive(isActive: Boolean): Specification&lt;User&gt; {
        return Specification { root, _, criteriaBuilder -&gt;
            criteriaBuilder.equal(root.get&lt;Boolean&gt;("isActive"), isActive)
        }
    }

    fun hasRole(roleName: String): Specification&lt;User&gt; {
        return Specification { root, query, criteriaBuilder -&gt;
            val roleJoin = root.join&lt;User, Role&gt;("roles")
            criteriaBuilder.equal(roleJoin.get&lt;String&gt;("name"), roleName)
        }
    }
}

// --- Service Layer ---
@Service
class StandardUserService(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository
) {
    @Transactional(readOnly = true)
    fun findUserById(id: UUID): UserDto? {
        return userRepository.findById(id).map { it.toDto() }.orElse(null)
    }

    @Transactional(readOnly = true)
    fun findUsers(isActive: Boolean?, roleName: String?): List&lt;UserDto&gt; {
        val spec = Specification.where&lt;User&gt;(null)
            .and(isActive?.let { UserSpecification.isActive(it) })
            .and(roleName?.let { UserSpecification.hasRole(it) })
        return userRepository.findAll(spec).map { it.toDto() }
    }

    @Transactional
    fun createUser(request: CreateUserRequest): UserDto {
        if (userRepository.exists(UserSpecification.isActive(true).and { root, _, cb -> cb.equal(root.get&lt;String&gt;("email"), request.email) })) {
            throw IllegalStateException("User with email ${request.email} already exists.")
        }
        val roles = roleRepository.findByNameIn(request.roleNames)
        if (roles.size != request.roleNames.size) {
            throw IllegalArgumentException("One or more roles not found.")
        }
        val newUser = User(
            email = request.email,
            passwordHash = "hashed_${request.password}", // In real app, use BCrypt
            roles = roles.toMutableSet()
        )
        return userRepository.save(newUser).toDto()
    }

    @Transactional
    fun deactivateUser(id: UUID): UserDto {
        val user = userRepository.findById(id).orElseThrow { NoSuchElementException("User not found") }
        user.isActive = false
        // Example of a complex transaction: deactivating a user also drafts all their posts.
        user.posts.forEach { it.status = PostStatus.DRAFT }
        return userRepository.save(user).toDto()
    }

    // This method demonstrates a transaction rollback.
    @Transactional
    fun createUserAndFail(request: CreateUserRequest): UserDto {
        val user = createUser(request)
        // Some other logic here...
        if (true) { // Simulate a failure condition
            throw RuntimeException("Simulated failure! Transaction will be rolled back.")
        }
        return user // This line is never reached
    }

    private fun User.toDto(): UserDto = UserDto(
        id = this.id,
        email = this.email,
        isActive = this.isActive,
        roles = this.roles.map { it.name }.toSet(),
        postCount = this.posts.size
    )
}

// --- Controller Layer ---
@RestController
@RequestMapping("/v1/users")
class UserController(private val userService: StandardUserService) {

    @GetMapping("/{id}")
    fun getUserById(@PathVariable id: UUID): ResponseEntity&lt;UserDto&gt; {
        return userService.findUserById(id)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    @GetMapping
    fun searchUsers(
        @RequestParam(required = false) isActive: Boolean?,
        @RequestParam(required = false) role: String?
    ): ResponseEntity&lt;List&lt;UserDto&gt;&gt; {
        return ResponseEntity.ok(userService.findUsers(isActive, role))
    }

    @PostMapping
    fun createUser(@RequestBody request: CreateUserRequest): ResponseEntity&lt;UserDto&gt; {
        return try {
            val user = userService.createUser(request)
            ResponseEntity.status(HttpStatus.CREATED).body(user)
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
    }

    @DeleteMapping("/{id}")
    fun deactivateUser(@PathVariable id: UUID): ResponseEntity&lt;UserDto&gt; {
        return try {
            ResponseEntity.ok(userService.deactivateUser(id))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping("/fail")
    fun createUserAndRollback(@RequestBody request: CreateUserRequest): ResponseEntity&lt;String&gt; {
        return try {
            userService.createUserAndFail(request)
            ResponseEntity.ok("Should not happen")
        } catch (e: RuntimeException) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.message)
        }
    }
}
</pre>