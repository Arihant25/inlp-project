package com.example.variation2;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Parameters;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

// --- Entities (Plain JPA) ---

@Entity(name = "RoleEntity")
@Table(name = "roles")
class RoleEntity {
    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    public UUID id;

    @Column(unique = true, nullable = false)
    public String name;
}

@Entity(name = "UserEntity")
@Table(name = "users")
class UserEntity {
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
    public List<PostEntity> posts;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    public Set<RoleEntity> roles;
}

@Entity(name = "PostEntity")
@Table(name = "posts")
class PostEntity {
    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    public UserEntity user;

    @Column(nullable = false)
    public String title;

    @Lob
    public String content;

    @Enumerated(EnumType.STRING)
    public PostStatus status;
}

// --- Repositories (Repository Pattern) ---

@ApplicationScoped
class UserRepository implements PanacheRepository<UserEntity> {
    public List<UserEntity> findActiveUsersByRole(String roleName) {
        return list("is_active = true and roles.name = ?1", roleName);
    }
}

@ApplicationScoped
class RoleRepository implements PanacheRepository<RoleEntity> {
    public RoleEntity findByName(String name) {
        return find("name", name).firstResult();
    }
}

@ApplicationScoped
class PostRepository implements PanacheRepository<PostEntity> {}

// --- Service Layer ---

@ApplicationScoped
class UserService {

    @Inject
    UserRepository userRepository;
    @Inject
    RoleRepository roleRepository;
    @Inject
    PostRepository postRepository;

    @Transactional
    public UserEntity registerNewUser(UserEntity user) {
        RoleEntity defaultRole = roleRepository.findByName("USER");
        if (defaultRole == null) {
            // In a real app, this might be seeded by migrations
            throw new IllegalStateException("Default USER role not found.");
        }
        user.roles = Set.of(defaultRole);
        userRepository.persist(user);
        return user;
    }

    @Transactional
    public PostEntity addPostToUser(UUID userId, PostEntity post) {
        UserEntity user = userRepository.findById(userId);
        if (user == null) {
            return null;
        }
        post.user = user;
        post.status = PostStatus.DRAFT;
        postRepository.persist(post);
        return post;
    }

    @Transactional
    public void performRiskyOperation() {
        UserEntity u = new UserEntity();
        u.email = "transient@example.com";
        u.password_hash = "somehash";
        u.is_active = false;
        userRepository.persist(u);

        // Simulate a critical failure after the first operation
        throw new RuntimeException("Simulating DB connection loss");
    }
}

// --- JAX-RS Resource ---

@Path("/v2/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource {

    @Inject
    UserService userService;
    @Inject
    UserRepository userRepository;

    @POST
    public Response createUser(UserEntity user) {
        if (userRepository.find("email", user.email).count() > 0) {
            return Response.status(Response.Status.CONFLICT).build();
        }
        UserEntity createdUser = userService.registerNewUser(user);
        return Response.status(Response.Status.CREATED).entity(createdUser).build();
    }

    @GET
    @Path("/{id}")
    public Response getUser(@PathParam("id") UUID id) {
        return userRepository.findByIdOptional(id)
                .map(user -> Response.ok(user).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteUser(@PathParam("id") UUID id) {
        boolean deleted = userRepository.deleteById(id);
        return deleted ? Response.noContent().build() : Response.status(Response.Status.NOT_FOUND).build();
    }

    @POST
    @Path("/{userId}/posts")
    public Response createPost(@PathParam("userId") UUID userId, PostEntity post) {
        PostEntity createdPost = userService.addPostToUser(userId, post);
        if (createdPost == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("User not found").build();
        }
        return Response.status(Response.Status.CREATED).entity(createdPost).build();
    }

    @GET
    @Path("/search")
    public Response searchUsers(@QueryParam("isActive") Boolean isActive, @QueryParam("emailContains") String emailFragment) {
        // Dynamic query building example
        String query = "1=1";
        Parameters params = Parameters.with("", ""); // Dummy start
        if (isActive != null) {
            query += " and is_active = :isActive";
            params = params.and("isActive", isActive);
        }
        if (emailFragment != null && !emailFragment.isBlank()) {
            query += " and email like :email";
            params = params.and("email", "%" + emailFragment + "%");
        }
        List<UserEntity> users = userRepository.list(query, params);
        return Response.ok(users).build();
    }
    
    @GET
    @Path("/rollback-test")
    public Response testRollback() {
        try {
            userService.performRiskyOperation();
            return Response.ok("This should not be returned").build();
        } catch (RuntimeException e) {
            long count = userRepository.find("email", "transient@example.com").count();
            String message = "Operation failed. User 'transient@example.com' count: " + count + " (should be 0)";
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(message).build();
        }
    }
}

/*
-- Database Migration using Flyway --
-- File: src/main/resources/db/migration/V1__create_tables.sql

CREATE TABLE roles (
    id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
);
ALTER TABLE roles ADD CONSTRAINT UK_role_name UNIQUE (name);

CREATE TABLE users (
    id UUID NOT NULL,
    created_at TIMESTAMP,
    email VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
);
ALTER TABLE users ADD CONSTRAINT UK_user_email UNIQUE (email);

CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (role_id) REFERENCES roles(id),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE posts (
    id UUID NOT NULL,
    content TEXT,
    status VARCHAR(255),
    title VARCHAR(255) NOT NULL,
    user_id UUID NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Seed data
INSERT INTO roles (id, name) VALUES ('00000000-0000-0000-0000-000000000001', 'ADMIN');
INSERT INTO roles (id, name) VALUES ('00000000-0000-0000-0000-000000000002', 'USER');

*/