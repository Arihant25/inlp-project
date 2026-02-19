package com.example.variation1;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Parameters;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// --- Enums ---

enum PostStatus {
    DRAFT, PUBLISHED
}

// --- Entities (Active Record Pattern) ---

@Entity
@Table(name = "roles")
class Role extends PanacheEntityBase {
    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    public UUID id;

    @Column(unique = true, nullable = false)
    public String name;
}

@Entity
@Table(name = "users")
class User extends PanacheEntityBase {
    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    public UUID id;

    @Column(unique = true, nullable = false)
    public String email;

    @Column(nullable = false)
    public String password_hash;

    public boolean is_active;

    @CreationTimestamp
    @Column(updatable = false)
    public Timestamp created_at;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    public List<Post> posts;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    public Set<Role> roles;

    public static User findByEmail(String email) {
        return find("email", email).firstResult();
    }
}

@Entity
@Table(name = "posts")
class Post extends PanacheEntityBase {
    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    public User user;

    @Column(nullable = false)
    public String title;

    @Lob
    public String content;

    @Enumerated(EnumType.STRING)
    public PostStatus status;
}

// --- JAX-RS Resource ---

@Path("/v1/users")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserPostResource {

    @POST
    @Transactional
    public Response createUser(User newUser) {
        // Basic validation
        if (newUser.email == null || User.findByEmail(newUser.email) != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Email is invalid or already exists.").build();
        }
        // Mock role assignment
        Role userRole = Role.find("name", "USER").firstResult();
        if (userRole == null) {
            userRole = new Role();
            userRole.name = "USER";
            userRole.persist();
        }
        newUser.roles = Set.of(userRole);
        newUser.persist();
        return Response.status(Response.Status.CREATED).entity(newUser).build();
    }

    @GET
    @Path("/{id}")
    public Response getUserById(@PathParam("id") UUID id) {
        User user = User.findById(id);
        return user != null ? Response.ok(user).build() : Response.status(Response.Status.NOT_FOUND).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response updateUser(@PathParam("id") UUID id, User updatedUser) {
        User user = User.findById(id);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        user.is_active = updatedUser.is_active;
        user.persist();
        return Response.ok(user).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteUser(@PathParam("id") UUID id) {
        boolean deleted = User.deleteById(id);
        return deleted ? Response.noContent().build() : Response.status(Response.Status.NOT_FOUND).build();
    }

    @POST
    @Path("/{userId}/posts")
    @Transactional
    public Response createPostForUser(@PathParam("userId") UUID userId, Post newPost) {
        User user = User.findById(userId);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("User not found.").build();
        }
        newPost.user = user;
        newPost.status = PostStatus.DRAFT;
        newPost.persist();
        return Response.status(Response.Status.CREATED).entity(newPost).build();
    }

    @GET
    @Path("/search")
    public Response findUsers(@QueryParam("active") boolean active, @QueryParam("role") String roleName) {
        // Example of query building with filters
        List<User> users = User.list("is_active = :active and roles.name = :roleName",
                Parameters.with("active", active).and("roleName", roleName));
        return Response.ok(users).build();
    }

    @POST
    @Path("/transaction-test")
    @Transactional
    public Response transactionRollbackTest() {
        // This method demonstrates transaction rollback.
        // A user is created, but then an exception is thrown.
        // The user creation should be rolled back.
        try {
            User testUser = new User();
            testUser.email = "rollback@example.com";
            testUser.password_hash = "test";
            testUser.is_active = true;
            testUser.persist();

            // This will cause the transaction to fail and roll back.
            if (true) {
                throw new RuntimeException("Simulating a failure!");
            }
            // This part is never reached
            return Response.ok().build();
        } catch (Exception e) {
            // The transaction manager will catch this and roll back.
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Transaction failed and was rolled back.").build();
        }
    }
}

/*
-- Database Migration using Flyway --
-- File: src/main/resources/db/migration/V1.0.0__init_schema.sql

CREATE TABLE roles (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL
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
    content TEXT,
    status VARCHAR(50) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Initial Data
INSERT INTO roles(id, name) VALUES ('a1b2c3d4-e5f6-7890-1234-567890abcdef', 'ADMIN');
INSERT INTO roles(id, name) VALUES ('b2c3d4e5-f6a7-8901-2345-67890abcdef1', 'USER');

*/