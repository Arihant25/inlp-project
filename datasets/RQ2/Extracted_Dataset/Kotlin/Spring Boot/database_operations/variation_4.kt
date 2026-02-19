<pre>
// Developer 4: The "Pragmatist" using Spring Data REST
// Style: Prefers convention over configuration. Uses Spring Data REST to expose repositories directly for standard CRUD.
//        Adds custom controllers/services only for business logic that doesn't fit the RESTful CRUD model.
// Querying: Relies on Spring Data REST's built-in query method exposure.
// Migrations: Liquibase

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
    implementation("org.springframework.boot:spring-boot-starter-data-rest") // Key dependency
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.liquibase:liquibase-core")
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
spring.datasource.url=jdbc:h2:mem:testdb4
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=password
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=validate
spring.h2.console.enabled=true
spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml
spring.data.rest.base-path=/api
*/

// --- src/main/resources/db/changelog/db.changelog-master.xml ---
/*
&lt;?xml version="1.0" encoding="UTF-8"?&gt;
&lt;databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd"&gt;
    &lt;include file="db/changelog/001-initial-schema.xml"/&gt;
&lt;/databaseChangeLog&gt;
*/

// --- src/main/resources/db/changelog/001-initial-schema.xml ---
/*
&lt;?xml version="1.0" encoding="UTF-8"?&gt;
&lt;databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd"&gt;

    &lt;changeSet id="1" author="dev4"&gt;
        &lt;createTable tableName="roles"&gt;
            &lt;column name="id" type="UUID"&gt;&lt;constraints primaryKey="true" nullable="false"/&gt;&lt;/column&gt;
            &lt;column name="name" type="VARCHAR(255)"&gt;&lt;constraints nullable="false" unique="true"/&gt;&lt;/column&gt;
        &lt;/createTable&gt;
        &lt;createTable tableName="users"&gt;
            &lt;column name="id" type="UUID"&gt;&lt;constraints primaryKey="true" nullable="false"/&gt;&lt;/column&gt;
            &lt;column name="email" type="VARCHAR(255)"&gt;&lt;constraints nullable="false" unique="true"/&gt;&lt;/column&gt;
            &lt;column name="password_hash" type="VARCHAR(255)"&gt;&lt;constraints nullable="false"/&gt;&lt;/column&gt;
            &lt;column name="is_active" type="BOOLEAN"&gt;&lt;constraints nullable="false"/&gt;&lt;/column&gt;
            &lt;column name="created_at" type="TIMESTAMP WITH TIME ZONE"&gt;&lt;constraints nullable="false"/&gt;&lt;/column&gt;
        &lt;/createTable&gt;
        &lt;createTable tableName="user_roles"&gt;
            &lt;column name="user_id" type="UUID"&gt;&lt;constraints nullable="false" primaryKey="true"/&gt;&lt;/column&gt;
            &lt;column name="role_id" type="UUID"&gt;&lt;constraints nullable="false" primaryKey="true"/&gt;&lt;/column&gt;
        &lt;/createTable&gt;
        &lt;createTable tableName="posts"&gt;
            &lt;column name="id" type="UUID"&gt;&lt;constraints primaryKey="true" nullable="false"/&gt;&lt;/column&gt;
            &lt;column name="user_id" type="UUID"&gt;&lt;constraints nullable="false"/&gt;&lt;/column&gt;
            &lt;column name="title" type="VARCHAR(255)"&gt;&lt;constraints nullable="false"/&gt;&lt;/column&gt;
            &lt;column name="content" type="TEXT"&gt;&lt;constraints nullable="false"/&gt;&lt;/column&gt;
            &lt;column name="status" type="VARCHAR(50)"&gt;&lt;constraints nullable="false"/&gt;&lt;/column&gt;
        &lt;/createTable&gt;
    &lt;/changeSet&gt;
    &lt;changeSet id="2" author="dev4"&gt;
        &lt;insert tableName="roles"&gt;&lt;column name="id" valueComputed="random_uuid()"/&gt;&lt;column name="name" value="ADMIN"/&gt;&lt;/insert&gt;
        &lt;insert tableName="roles"&gt;&lt;column name="id" valueComputed="random_uuid()"/&gt;&lt;column name="name" value="USER"/&gt;&lt;/insert&gt;
    &lt;/changeSet&gt;
&lt;/databaseChangeLog&gt;
*/

package com.example.variation4

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.Param
import org.springframework.data.rest.core.annotation.RepositoryRestResource
import org.springframework.data.rest.core.annotation.RestResource
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.*

// --- Main Application ---
@SpringBootApplication
class DataRestApp

fun main(args: Array&lt;String&gt;) {
    runApplication&lt;DataRestApp&gt;(*args)
}

// --- Domain Model ---
enum class PostStatus { DRAFT, PUBLISHED }

@Entity @Table(name = "roles")
data class Role(
    @Id @GeneratedValue(strategy = GenerationType.UUID) val id: UUID = UUID.randomUUID(),
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

// --- Repositories exposed via Spring Data REST ---
// GET /api/users, POST /api/users, GET /api/users/{id}, etc. are all created automatically.
@RepositoryRestResource(collectionResourceRel = "users", path = "users")
interface UserRestRepository : JpaRepository&lt;User, UUID&gt; {
    // This method will be exposed at /api/users/search/findByIsActive?active=true
    @RestResource(path = "findByIsActive", rel = "findByIsActive")
    fun findByIsActive(@Param("active") isActive: Boolean): List&lt;User&gt;

    // This method will be exposed at /api/users/search/findByRolesName?role=ADMIN
    fun findByRolesName(@Param("role") roleName: String): List&lt;User&gt;
}

@RepositoryRestResource(collectionResourceRel = "posts", path = "posts")
interface PostRestRepository : JpaRepository&lt;Post, UUID&gt;

// Roles are read-only, we don't want to expose POST/PUT/DELETE
@RepositoryRestResource(collectionResourceRel = "roles", path = "roles", exported = true)
interface RoleRestRepository : JpaRepository&lt;Role, UUID&gt; {
    override fun &lt;S : Role?&gt; save(entity: S): S { throw UnsupportedOperationException() }
    override fun delete(entity: Role) { throw UnsupportedOperationException() }
    fun findByNameIn(names: Collection&lt;String&gt;): Set&lt;Role&gt;
}

// --- DTOs for custom business logic ---
data class OnboardingRequest(
    val email: String,
    val password: String,
    val initialPostTitle: String,
    val initialPostContent: String
)
data class OnboardingResponse(val userId: UUID, val postId: UUID, val message: String)

// --- Custom Service for complex, transactional operations ---
@Service
class UserOnboardingService(
    private val userRepo: UserRestRepository, // We can still inject the repos
    private val roleRepo: RoleRestRepository
) {
    @Transactional
    fun onboardUserWithFirstPost(request: OnboardingRequest): OnboardingResponse {
        // Step 1: Create the User
        val userRole = roleRepo.findByNameIn(setOf("USER"))
        val newUser = User(
            email = request.email,
            passwordHash = request.password.hashCode().toString(), // Dummy hash
            roles = userRole.toMutableSet()
        )

        // Step 2: Create their first Post
        val firstPost = Post(
            title = request.initialPostTitle,
            content = request.initialPostContent,
            status = PostStatus.PUBLISHED,
            user = newUser
        )
        newUser.posts.add(firstPost)

        // Step 3: Save everything in one transaction
        val savedUser = userRepo.save(newUser)
        val savedPost = savedUser.posts.first()

        return OnboardingResponse(
            userId = savedUser.id,
            postId = savedPost.id,
            message = "User ${savedUser.email} onboarded successfully!"
        )
    }

    @Transactional
    fun onboardAndFail(request: OnboardingRequest): OnboardingResponse {
        onboardUserWithFirstPost(request)
        // If we get here, the above succeeded. Now, cause a failure.
        throw RuntimeException("Simulating system failure after onboarding. Rollback should occur.")
    }
}

// --- Custom Controller for the onboarding process ---
@RestController
class CustomUserController(private val onboardingService: UserOnboardingService) {

    @PostMapping("/v4/onboard")
    fun onboardUser(@RequestBody request: OnboardingRequest): ResponseEntity&lt;OnboardingResponse&gt; {
        val response = onboardingService.onboardUserWithFirstPost(request)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/v4/onboard-and-fail")
    fun onboardAndFail(@RequestBody request: OnboardingRequest): ResponseEntity&lt;String&gt; {
        return try {
            onboardingService.onboardAndFail(request)
            ResponseEntity.status(500).body("This should not be returned.")
        } catch (e: RuntimeException) {
            ResponseEntity.status(418).body("Transaction successfully rolled back due to: ${e.message}")
        }
    }
}
</pre>