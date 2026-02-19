<pre>
// Developer 2: The "Functional &amp; Concise" Developer
// Style: Leverages Kotlin's features like extension functions and data classes for a more concise, functional style.
// Querying: Uses QueryDSL for a fluent, type-safe query building experience.
// Migrations: Liquibase

// --- build.gradle.kts ---
/*
plugins {
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.4"
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.spring") version "1.9.23"
    kotlin("plugin.jpa") version "1.9.23"
    // For QueryDSL
    id("com.ewerk.gradle.plugins.querydsl") version "1.0.10"
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
    implementation("org.liquibase:liquibase-core")
    // QueryDSL
    implementation("com.querydsl:querydsl-jpa:5.1.0:jakarta")
    kapt("com.querydsl:querydsl-apt:5.1.0:jakarta")
    kapt("jakarta.persistence:jakarta.persistence-api:3.1.0")

    runtimeOnly("com.h2database:h2")
}

// QueryDSL configuration
querydsl {
    jpa = true
    library = "com.querydsl:querydsl-jpa:5.1.0:jakarta"
    querydslSourcesDir = file("build/generated/source/kapt/main")
}
sourceSets {
    main {
        java {
            srcDirs("build/generated/source/kapt/main")
        }
    }
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
spring.datasource.url=jdbc:h2:mem:testdb2
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=password
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=validate
spring.h2.console.enabled=true
spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml
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

    &lt;changeSet id="1" author="dev2"&gt;
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
        &lt;addForeignKeyConstraint baseTableName="user_roles" baseColumnNames="user_id" constraintName="fk_userroles_user" referencedTableName="users" referencedColumnNames="id" onDelete="CASCADE"/&gt;
        &lt;addForeignKeyConstraint baseTableName="user_roles" baseColumnNames="role_id" constraintName="fk_userroles_role" referencedTableName="roles" referencedColumnNames="id" onDelete="CASCADE"/&gt;
        &lt;createTable tableName="posts"&gt;
            &lt;column name="id" type="UUID"&gt;&lt;constraints primaryKey="true" nullable="false"/&gt;&lt;/column&gt;
            &lt;column name="user_id" type="UUID"&gt;&lt;constraints nullable="false"/&gt;&lt;/column&gt;
            &lt;column name="title" type="VARCHAR(255)"&gt;&lt;constraints nullable="false"/&gt;&lt;/column&gt;
            &lt;column name="content" type="TEXT"&gt;&lt;constraints nullable="false"/&gt;&lt;/column&gt;
            &lt;column name="status" type="VARCHAR(50)"&gt;&lt;constraints nullable="false"/&gt;&lt;/column&gt;
        &lt;/createTable&gt;
        &lt;addForeignKeyConstraint baseTableName="posts" baseColumnNames="user_id" constraintName="fk_posts_user" referencedTableName="users" referencedColumnNames="id" onDelete="CASCADE"/&gt;
    &lt;/changeSet&gt;
    &lt;changeSet id="2" author="dev2"&gt;
        &lt;insert tableName="roles"&gt;&lt;column name="id" valueComputed="random_uuid()"/&gt;&lt;column name="name" value="ADMIN"/&gt;&lt;/insert&gt;
        &lt;insert tableName="roles"&gt;&lt;column name="id" valueComputed="random_uuid()"/&gt;&lt;column name="name" value="USER"/&gt;&lt;/insert&gt;
    &lt;/changeSet&gt;
&lt;/databaseChangeLog&gt;
*/

package com.example.variation2

import com.querydsl.core.BooleanBuilder
import com.querydsl.core.types.Predicate
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.*

// --- Main Application ---
@SpringBootApplication
class FunctionalApp

fun main(args: Array&lt;String&gt;) {
    runApplication&lt;FunctionalApp&gt;(*args)
}

// --- Domain Model (in a single file for conciseness) ---
object Domain {
    enum class PostStatus { DRAFT, PUBLISHED }

    @Entity @Table(name = "roles")
    data class Role(
        @Id @GeneratedValue(strategy = GenerationType.UUID) val id: UUID = UUID.randomUUID(),
        @Column(nullable = false, unique = true) val name: String
    )

    @Entity @Table(name = "users")
    data class User(
        @Id @GeneratedValue(strategy = GenerationType.UUID) val id: UUID = UUID.randomUUID(),
        @Column(nullable = false, unique = true) var email: String,
        @Column(name = "password_hash", nullable = false) var passwordHash: String,
        @Column(name = "is_active", nullable = false) var isActive: Boolean = true,
        @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant = Instant.now(),
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
}

// --- Data Contracts & Mappers (using extension functions) ---
data class UserView(val id: UUID, val email: String, val active: Boolean, val roles: Set&lt;String&gt;)
data class UserCreate(val email: String, val pass: String, val roles: Set&lt;String&gt;)

fun Domain.User.toView(): UserView = UserView(
    id = this.id,
    email = this.email,
    active = this.isActive,
    roles = this.roles.map { it.name }.toSet()
)

// --- Data Access Layer ---
interface UserRepo : JpaRepository&lt;Domain.User, UUID&gt;, QuerydslPredicateExecutor&lt;Domain.User&gt;
interface RoleRepo : JpaRepository&lt;Domain.Role, UUID&gt; {
    fun findByNameIn(names: Collection&lt;String&gt;): Set&lt;Domain.Role&gt;
}

// --- Logic Layer ---
@Service
@Transactional
class UserLogic(private val userRepo: UserRepo, private val roleRepo: RoleRepo) {

    fun findById(id: UUID): UserView? = userRepo.findById(id).map { it.toView() }.orElse(null)

    fun search(active: Boolean?, role: String?): List&lt;UserView&gt; {
        val qUser = com.example.variation2.Domain.QUser.user // Generated by QueryDSL
        val predicate = BooleanBuilder().apply {
            active?.let { and(qUser.isActive.eq(it)) }
            role?.let { and(qUser.roles.any().name.equalsIgnoreCase(it)) }
        }
        return userRepo.findAll(predicate).map { it.toView() }
    }

    fun create(req: UserCreate): Result&lt;UserView&gt; {
        return runCatching {
            val existing = userRepo.exists(com.example.variation2.Domain.QUser.user.email.eq(req.email))
            if (existing) throw DuplicateUserException(req.email)

            val roles = roleRepo.findByNameIn(req.roles)
            if (roles.size != req.roles.size) throw InvalidRoleException()

            Domain.User(
                email = req.email,
                passwordHash = Base64.getEncoder().encodeToString(req.pass.toByteArray()), // Simple encoding
                roles = roles.toMutableSet()
            ).let(userRepo::save).toView()
        }
    }

    fun addPost(userId: UUID, title: String, content: String): Result&lt;Unit&gt; {
        return runCatching {
            val user = userRepo.findById(userId).orElseThrow { UserNotFoundException(userId) }
            val post = Domain.Post(title = title, content = content, user = user)
            user.posts.add(post)
            userRepo.save(user)
            Unit
        }
    }

    // Transaction rollback is implicit on any thrown exception
    fun createWithPostAndFail(req: UserCreate): Result&lt;UserView&gt; {
        val userResult = create(req)
        userResult.onSuccess {
            addPost(it.id, "First Post", "This should be rolled back")
            throw IllegalStateException("Simulating a failure after post creation")
        }
        return userResult
    }
}

// --- API Layer & Exception Handling ---
class DuplicateUserException(email: String) : RuntimeException("User '$email' already exists.")
class InvalidRoleException : RuntimeException("Invalid role specified.")
class UserNotFoundException(id: UUID) : RuntimeException("User with id '$id' not found.")

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(DuplicateUserException::class)
    fun handleConflict(ex: DuplicateUserException) = ResponseEntity(ex.message, HttpStatus.CONFLICT)

    @ExceptionHandler(value = [InvalidRoleException::class, IllegalArgumentException::class])
    fun handleBadRequest(ex: RuntimeException) = ResponseEntity(ex.message, HttpStatus.BAD_REQUEST)

    @ExceptionHandler(UserNotFoundException::class)
    fun handleNotFound(ex: UserNotFoundException) = ResponseEntity(ex.message, HttpStatus.NOT_FOUND)
}

@RestController
@RequestMapping("/v2/users")
class UserApi(private val userLogic: UserLogic) {

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID) = userLogic.findById(id)
        ?.let { ResponseEntity.ok(it) }
        ?: ResponseEntity.notFound().build()

    @GetMapping
    fun findUsers(@RequestParam active: Boolean?, @RequestParam role: String?) =
        ResponseEntity.ok(userLogic.search(active, role))

    @PostMapping
    fun registerUser(@RequestBody req: UserCreate) =
        userLogic.create(req).fold(
            onSuccess = { ResponseEntity.status(HttpStatus.CREATED).body(it) },
            onFailure = { throw it } // Let the RestControllerAdvice handle it
        )

    @PostMapping("/fail-tx")
    fun registerAndFail(@RequestBody req: UserCreate) =
        userLogic.createWithPostAndFail(req).fold(
            onSuccess = { ResponseEntity.ok(it) },
            onFailure = { ResponseEntity.internalServerError().body(it.message) }
        )
}
</pre>