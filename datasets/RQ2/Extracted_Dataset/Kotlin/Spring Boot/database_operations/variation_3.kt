<pre>
// Developer 3: The "CQRS-lite" Proponent
// Style: Separates write operations (Commands) from read operations (Queries) into different services and controllers.
// Querying: Uses a mix of derived queries and explicit @Query annotations in repositories for optimized reads.
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
spring.datasource.url=jdbc:h2:mem:testdb3
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
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
    role_id BIGINT NOT NULL,
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
INSERT INTO roles (name) VALUES ('ADMIN'), ('USER');
*/

package com.example.variation3

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.*

// --- Main Application ---
@SpringBootApplication
class CqrsApp

fun main(args: Array&lt;String&gt;) {
    runApplication&lt;CqrsApp&gt;(*args)
}

// --- Domain Model ---
enum class PostStatus { DRAFT, PUBLISHED }

@Entity @Table(name = "roles")
data class Role(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    @Column(nullable = false, unique = true) val name: String
)

@Entity @Table(name = "users")
data class User(
    @Id @GeneratedValue(strategy = GenerationType.UUID) val id: UUID = UUID.randomUUID(),
    var email: String,
    @Column(name = "password_hash") var passwordHash: String,
    @Column(name = "is_active") var isActive: Boolean = true,
    @CreationTimestamp @Column(name = "created_at", updatable = false) val createdAt: Instant = Instant.now(),
    @OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true) val posts: MutableList&lt;Post&gt; = mutableListOf(),
    @ManyToMany(fetch = FetchType.EAGER) @JoinTable(name = "user_roles", joinColumns = [JoinColumn(name = "user_id")], inverseJoinColumns = [JoinColumn(name = "role_id")])
    var roles: MutableSet&lt;Role&gt; = mutableSetOf()
)

@Entity @Table(name = "posts")
data class Post(
    @Id @GeneratedValue(strategy = GenerationType.UUID) val id: UUID = UUID.randomUUID(),
    var title: String,
    @Lob var content: String,
    @Enumerated(EnumType.STRING) var status: PostStatus = PostStatus.DRAFT,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id") var user: User
)

// --- Persistence Layer ---
interface UserRepo : JpaRepository&lt;User, UUID&gt; {
    fun existsByEmail(email: String): Boolean
}
interface RoleRepo : JpaRepository&lt;Role, Long&gt; {
    fun findByNameIn(names: Collection&lt;String&gt;): Set&lt;Role&gt;
}
interface UserQueryRepo : JpaRepository&lt;User, UUID&gt; {
    @Query("SELECT new com.example.variation3.application.queries.UserSummaryView(u.id, u.email, u.isActive) FROM User u WHERE u.id = :id")
    fun findSummaryById(@Param("id") id: UUID): Optional&lt;UserSummaryView&gt;

    @Query("SELECT new com.example.variation3.application.queries.UserSummaryView(u.id, u.email, u.isActive) FROM User u WHERE (:isActive IS NULL OR u.isActive = :isActive)")
    fun findByCriteria(@Param("isActive") isActive: Boolean?): List&lt;UserSummaryView&gt;
}

// --- Application Layer: Commands ---
object UserCommands {
    data class CreateUserCmd(val email: String, val password: String, val roleNames: Set&lt;String&gt;)
    data class AddPostCmd(val userId: UUID, val title: String, val content: String)
    data class DeactivateUserCmd(val userId: UUID)
}

@Service
@Transactional // Command services are almost always transactional
class UserCommandService(private val userRepo: UserRepo, private val roleRepo: RoleRepo) {
    fun handle(command: UserCommands.CreateUserCmd): UUID {
        if (userRepo.existsByEmail(command.email)) {
            throw RuntimeException("Email already in use")
        }
        val roles = roleRepo.findByNameIn(command.roleNames)
        val user = User(
            email = command.email,
            passwordHash = command.password.reversed(), // Dummy hashing
            roles = roles.toMutableSet()
        )
        return userRepo.save(user).id
    }

    fun handle(command: UserCommands.AddPostCmd) {
        val user = userRepo.findById(command.userId).orElseThrow()
        user.posts.add(Post(title = command.title, content = command.content, user = user))
        userRepo.save(user)
    }

    fun handle(command: UserCommands.DeactivateUserCmd) {
        val user = userRepo.findById(command.userId).orElseThrow()
        user.isActive = false
        user.posts.forEach { it.status = PostStatus.DRAFT }
        userRepo.save(user)
    }

    fun handleAndRollback(command: UserCommands.CreateUserCmd): UUID {
        val userId = handle(command)
        handle(UserCommands.AddPostCmd(userId, "My First Post", "This should not be saved."))
        throw IllegalStateException("Intentional failure to trigger rollback.")
    }
}

// --- Application Layer: Queries ---
object UserQueries {
    data class FindUserById(val id: UUID)
    data class FindUsersByFilter(val isActive: Boolean?)
}

data class UserSummaryView(val id: UUID, val email: String, val active: Boolean)
data class UserDetailView(val id: UUID, val email: String, val active: Boolean, val roles: Set&lt;String&gt;, val posts: List&lt;PostSummaryView&gt;)
data class PostSummaryView(val id: UUID, val title: String, val status: PostStatus)

@Service
@Transactional(readOnly = true) // Query services are read-only
class UserQueryService(private val userRepo: UserRepo, private val userQueryRepo: UserQueryRepo) {
    fun handle(query: UserQueries.FindUserById): UserDetailView? {
        return userRepo.findById(query.id).map { user -&gt;
            UserDetailView(
                id = user.id,
                email = user.email,
                active = user.isActive,
                roles = user.roles.map { it.name }.toSet(),
                posts = user.posts.map { PostSummaryView(it.id, it.title, it.status) }
            )
        }.orElse(null)
    }

    fun handle(query: UserQueries.FindUsersByFilter): List&lt;UserSummaryView&gt; {
        return userQueryRepo.findByCriteria(query.isActive)
    }
}

// --- Presentation Layer ---
@RestController
@RequestMapping("/v3/users")
class UserCommandController(private val commandService: UserCommandService) {
    @PostMapping
    fun createUser(@RequestBody cmd: UserCommands.CreateUserCmd): ResponseEntity&lt;Map&lt;String, UUID&gt;&gt; {
        val id = commandService.handle(cmd)
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("id" to id))
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deactivateUser(@PathVariable id: UUID) {
        commandService.handle(UserCommands.DeactivateUserCmd(id))
    }

    @PostMapping("/{id}/posts")
    @ResponseStatus(HttpStatus.CREATED)
    fun addPost(@PathVariable id: UUID, @RequestBody post: Map&lt;String, String&gt;) {
        commandService.handle(UserCommands.AddPostCmd(id, post.getValue("title"), post.getValue("content")))
    }

    @PostMapping("/rollback-test")
    fun testRollback(@RequestBody cmd: UserCommands.CreateUserCmd): ResponseEntity&lt;String&gt; {
        return try {
            commandService.handleAndRollback(cmd)
            ResponseEntity.ok("Error: Rollback did not occur.")
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.I_AM_A_TEAPOT).body("Success: Transaction rolled back as expected.")
        }
    }
}

@RestController
@RequestMapping("/v3/users")
class UserQueryController(private val queryService: UserQueryService) {
    @GetMapping("/{id}")
    fun findById(@PathVariable id: UUID): ResponseEntity&lt;UserDetailView&gt; {
        return queryService.handle(UserQueries.FindUserById(id))
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    @GetMapping
    fun findByFilter(@RequestParam(required = false) isActive: Boolean?): List&lt;UserSummaryView&gt; {
        return queryService.handle(UserQueries.FindUsersByFilter(isActive))
    }
}
</pre>