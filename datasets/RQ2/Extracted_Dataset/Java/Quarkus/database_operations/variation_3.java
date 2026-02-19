package com.example.variation3;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Sort;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// --- Enums ---
enum Status { DRAFT, PUBLISHED }

// --- DTO (Data Transfer Object) ---
class UserDto {
    public UUID id;
    public String email;
    public boolean isActive;
    public Set<String> roles;

    public static UserDto fromEntity(User user) {
        UserDto dto = new UserDto();
        dto.id = user.id;
        dto.email = user.email;
        dto.isActive = user.is_active;
        dto.roles = user.roles.stream().map(role -> role.name).collect(Collectors.toSet());
        return dto;
    }
}

// --- Entities (Active Record) ---
@Entity @Table(name = "roles")
class Role extends PanacheEntityBase {
    @Id @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    public UUID id;
    @Column(unique = true, nullable = false) public String name;
}

@Entity @Table(name = "users")
class User extends PanacheEntityBase {
    @Id @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    public UUID id;
    @Column(unique = true, nullable = false) public String email;
    @Column(nullable = false) public String password_hash;
    public boolean is_active;
    @CreationTimestamp @Column(updatable = false) public Timestamp created_at;
    @OneToMany(mappedBy = "author", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<Post> posts;
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    public Set<Role> roles;
}

@Entity @Table(name = "posts")
class Post extends PanacheEntityBase {
    @Id @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    public UUID id;
    @ManyToOne(optional = false) @JoinColumn(name = "user_id")
    public User author;
    @Column(nullable = false) public String title;
    @Lob public String content;
    @Enumerated(EnumType.STRING) public Status status;
}

// --- Service Layer (Functional Style) ---
@ApplicationScoped
class UserManagementService {

    @Transactional
    public Optional<User> registerUser(String email, String password) {
        if (User.count("email", email) > 0) {
            return Optional.empty();
        }
        Role userRole = Role.<Role>find("name", "USER").firstResultOptional()
                .orElseThrow(() -> new IllegalStateException("USER role not found in DB."));

        User newUser = new User();
        newUser.email = email;
        newUser.password_hash = password; // Hashing should be done here in a real app
        newUser.is_active = true;
        newUser.roles = Set.of(userRole);
        newUser.persist();
        return Optional.of(newUser);
    }

    @Transactional
    public Optional<Post> createPost(UUID authorId, String title, String content) {
        return User.<User>findByIdOptional(authorId).map(user -> {
            Post post = new Post();
            post.author = user;
            post.title = title;
            post.content = content;
            post.status = Status.DRAFT;
            post.persist();
            return post;
        });
    }

    @Transactional
    public void activateUsersAndFail(List<UUID> userIds) {
        userIds.stream()
            .map(id -> User.<User>findByIdOptional(id))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .forEach(user -> user.is_active = true);
        
        // Now, simulate a business rule violation that forces a rollback
        throw new IllegalStateException("Forced rollback after activating users!");
    }
}

// --- JAX-RS Endpoint ---
@Path("/v3/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserEndpoint {

    @Inject
    UserManagementService service;

    @GET
    public List<UserDto> getAllUsers() {
        return User.<User>streamAll(Sort.by("created_at"))
                .map(UserDto::fromEntity)
                .collect(Collectors.toList());
    }

    @POST
    public Response createUser(User request) {
        return service.registerUser(request.email, request.password_hash)
                .map(user -> Response.status(Response.Status.CREATED).entity(UserDto.fromEntity(user)).build())
                .orElse(Response.status(Response.Status.CONFLICT).entity("Email already exists.").build());
    }

    @GET
    @Path("/{id}")
    public Response findById(@PathParam("id") UUID id) {
        return User.<User>findByIdOptional(id)
                .map(UserDto::fromEntity)
                .map(dto -> Response.ok(dto).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteUser(@PathParam("id") UUID id) {
        boolean deleted = User.deleteById(id);
        if (deleted) {
            return Response.noContent().build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @POST
    @Path("/{userId}/posts")
    public Response createPost(@PathParam("userId") UUID userId, Post post) {
        return service.createPost(userId, post.title, post.content)
                .map(p -> Response.status(Response.Status.CREATED).entity(p).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).entity("User not found.").build());
    }
    
    @GET
    @Path("/posts/published")
    public List<Post> getPublishedPosts() {
        // Fluent query example
        return Post.find("status", Status.PUBLISHED).list();
    }
}

/*
-- Database Migration using Flyway --
-- File: src/main/resources/db/migration/V001__initial_setup.sql

CREATE TABLE roles (
    id UUID PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    is_active BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_roles (
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID REFERENCES roles(id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE posts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    content TEXT,
    status VARCHAR(20) NOT NULL
);

-- Seed essential roles
INSERT INTO roles (id, name) VALUES ('f47ac10b-58cc-4372-a567-0e02b2c3d479', 'USER');
INSERT INTO roles (id, name) VALUES ('743d828f-772c-4e4b-9a2d-3fd7bfa5a5e6', 'ADMIN');

*/