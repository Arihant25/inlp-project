package com.example.variation4;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// --- Enums ---
enum PostStatus { DRAFT, PUBLISHED }

// --- Data Models (Plain JPA) ---
@Entity
@Table(name = "roles")
class RoleModel {
    @Id @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    public UUID id;
    @Column(unique = true, nullable = false) public String name;
}

@Entity
@Table(name = "users")
class UserModel {
    @Id @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    public UUID id;
    @Column(unique = true, nullable = false) public String email;
    @Column(name = "password_hash", nullable = false) public String passwordHash;
    @Column(name = "is_active") public boolean isActive;
    @CreationTimestamp @Column(name = "created_at", updatable = false) public Timestamp createdAt;
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<PostModel> posts;
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    public Set<RoleModel> roles;
}

@Entity
@Table(name = "posts")
class PostModel {
    @Id @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    public UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    public UserModel user;
    @Column(nullable = false) public String title;
    @Lob public String content;
    @Enumerated(EnumType.STRING) public PostStatus status;
}

// --- Repositories (Verbose Style) ---
@ApplicationScoped
class UserRepo implements PanacheRepositoryBase<UserModel, UUID> {
    
    /**
     * Finds users based on a dynamic set of criteria.
     * @param emailPattern A pattern for the email (e.g., "%@example.com"). Can be null.
     * @param isActive The active status of the user. Can be null.
     * @return A list of matching user models.
     */
    public List<UserModel> findWithFilters(String emailPattern, Boolean isActive) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder queryBuilder = new StringBuilder("FROM UserModel u WHERE 1=1 ");

        if (emailPattern != null && !emailPattern.isBlank()) {
            queryBuilder.append("AND u.email LIKE :emailPattern ");
            params.put("emailPattern", emailPattern);
        }
        if (isActive != null) {
            queryBuilder.append("AND u.isActive = :isActive ");
            params.put("isActive", isActive);
        }
        
        TypedQuery<UserModel> query = getEntityManager().createQuery(queryBuilder.toString(), UserModel.class);
        params.forEach(query::setParameter);
        
        return query.getResultList();
    }
}

@ApplicationScoped
class RoleRepo implements PanacheRepositoryBase<RoleModel, UUID> {}

@ApplicationScoped
class PostRepo implements PanacheRepositoryBase<PostModel, UUID> {}

// --- Facade/Service Layer ---
@ApplicationScoped
class UserFacade {
    @Inject
    private UserRepo _userRepo;
    @Inject
    private RoleRepo _roleRepo;
    @Inject
    private PostRepo _postRepo;

    @Transactional
    public UserModel createNewUser(String email, String password) throws Exception {
        // Check for existing user
        if (_userRepo.count("email", email) > 0) {
            throw new WebApplicationException("User with this email already exists", Response.Status.CONFLICT);
        }
        
        // Find default role
        RoleModel defaultRole = _roleRepo.find("name", "USER").firstResult();
        if (defaultRole == null) {
            throw new IllegalStateException("System misconfiguration: Default 'USER' role not found.");
        }

        // Create and persist the new user
        UserModel userEntity = new UserModel();
        userEntity.email = email;
        userEntity.passwordHash = password; // In production, hash this
        userEntity.isActive = true;
        userEntity.roles = Set.of(defaultRole);
        
        _userRepo.persist(userEntity);
        return userEntity;
    }

    @Transactional
    public void deleteUserAndPosts(UUID userId) {
        _userRepo.deleteById(userId);
    }

    @Transactional
    public void executeRiskyMultiStepProcess() {
        // Step 1: Create a temporary user
        UserModel tempUser = new UserModel();
        tempUser.email = "temp-user-for-rollback@test.com";
        tempUser.passwordHash = "123";
        tempUser.isActive = false;
        _userRepo.persist(tempUser);

        // Step 2: Simulate a failure. This could be a failed network call,
        // a business logic validation error, etc.
        System.out.println("User " + tempUser.email + " created, but now an error will occur.");
        throw new RuntimeException("A critical error occurred, initiating rollback.");
    }
}

// --- Controller (JAX-RS Resource) ---
@Path("/v4/user-controller")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserController {

    @Inject
    private UserFacade _userFacade;
    @Inject
    private UserRepo _userRepo;

    @POST
    @Path("/create")
    public Response handleCreateUser(UserModel request) {
        try {
            UserModel newUser = _userFacade.createNewUser(request.email, request.passwordHash);
            return Response.status(Response.Status.CREATED).entity(newUser).build();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("/get/{id}")
    public Response handleGetUserById(@PathParam("id") UUID id) {
        UserModel user = _userRepo.findById(id);
        return user != null ? Response.ok(user).build() : Response.status(Response.Status.NOT_FOUND).build();
    }

    @DELETE
    @Path("/delete/{id}")
    public Response handleDeleteUser(@PathParam("id") UUID id) {
        try {
            _userFacade.deleteUserAndPosts(id);
            return Response.noContent().build();
        } catch (Exception e) {
            // This can happen if the user doesn't exist, etc.
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @GET
    @Path("/query")
    public Response handleUserQuery(@QueryParam("emailLike") String emailLike, @QueryParam("active") Boolean active) {
        List<UserModel> results = _userRepo.findWithFilters(emailLike, active);
        return Response.ok(results).build();
    }

    @GET
    @Path("/test-rollback")
    public Response testTransactionRollback() {
        try {
            _userFacade.executeRiskyMultiStepProcess();
            return Response.ok("Process completed (this should not happen)").build();
        } catch (RuntimeException e) {
            long userCount = _userRepo.count("email", "temp-user-for-rollback@test.com");
            String body = "Process failed as expected. User was rolled back. Count of temp user: " + userCount;
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(body).build();
        }
    }
}

/*
-- Database Migration using Flyway --
-- File: src/main/resources/db/migration/V1_0__baseline.sql

-- This script sets up the initial database schema.

-- Role table for many-to-many relationship with users
CREATE TABLE roles (
    id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uq_roles_name UNIQUE (name)
);

-- User table
CREATE TABLE users (
    id UUID NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
);

-- Join table for users and roles
CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_on_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_on_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE
);

-- Post table for one-to-many relationship with users
CREATE TABLE posts (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT,
    status VARCHAR(255),
    CONSTRAINT pk_posts PRIMARY KEY (id),
    CONSTRAINT fk_posts_on_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Add initial data for roles
INSERT INTO roles (id, name) VALUES ('c4d3d9c8-82a1-4b4a-a5c8-5d3d3e8c8d3d', 'ADMIN');
INSERT INTO roles (id, name) VALUES ('a1b2c3d4-e5f6-a7b8-c9d0-e1f2a3b4c5d6', 'USER');

*/