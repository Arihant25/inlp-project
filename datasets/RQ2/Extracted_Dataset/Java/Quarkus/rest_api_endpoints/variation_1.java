package org.acme.rest.users.v1;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// --- DOMAIN ---

enum Role {
    ADMIN, USER
}

class User {
    public UUID id;
    public String email;
    public String password_hash;
    public Role role;
    public Boolean is_active;
    public Timestamp created_at;

    public User() {}

    public User(String email, String password, Role role, Boolean isActive) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.password_hash = "hashed_" + password; // Mock hashing
        this.role = role;
        this.is_active = isActive;
        this.created_at = Timestamp.from(Instant.now());
    }
}

class Post {
    public enum Status { DRAFT, PUBLISHED }
    public UUID id;
    public UUID user_id;
    public String title;
    public String content;
    public Status status;
}


// --- REPOSITORY LAYER ---

interface UserRepository {
    User save(User user);
    List<User> findAll(int page, int size);
    List<User> findByCriteria(Role role, Boolean isActive, int page, int size);
    long count();
    long countByCriteria(Role role, Boolean isActive);
    Optional<User> findById(UUID id);
    void deleteById(UUID id);
}

@ApplicationScoped
class InMemoryUserRepository implements UserRepository {
    private final Map<UUID, User> userStore = new ConcurrentHashMap<>();

    public InMemoryUserRepository() {
        // Seed with some data
        User admin = new User("admin@example.com", "password", Role.ADMIN, true);
        User user1 = new User("user1@example.com", "password", Role.USER, true);
        User user2 = new User("user2@example.com", "password", Role.USER, false);
        userStore.put(admin.id, admin);
        userStore.put(user1.id, user1);
        userStore.put(user2.id, user2);
    }

    @Override
    public User save(User user) {
        if (user.id == null) {
            user.id = UUID.randomUUID();
            user.created_at = Timestamp.from(Instant.now());
        }
        userStore.put(user.id, user);
        return user;
    }

    @Override
    public List<User> findAll(int page, int size) {
        return userStore.values().stream()
                .skip((long) page * size)
                .limit(size)
                .collect(Collectors.toList());
    }

    @Override
    public List<User> findByCriteria(Role role, Boolean isActive, int page, int size) {
        return userStore.values().stream()
                .filter(u -> role == null || u.role == role)
                .filter(u -> isActive == null || u.is_active.equals(isActive))
                .skip((long) page * size)
                .limit(size)
                .collect(Collectors.toList());
    }

    @Override
    public long count() {
        return userStore.size();
    }

    @Override
    public long countByCriteria(Role role, Boolean isActive) {
        return userStore.values().stream()
                .filter(u -> role == null || u.role == role)
                .filter(u -> isActive == null || u.is_active.equals(isActive))
                .count();
    }

    @Override
    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(userStore.get(id));
    }

    @Override
    public void deleteById(UUID id) {
        userStore.remove(id);
    }
}

// --- SERVICE LAYER ---

@ApplicationScoped
class UserService {

    @Inject
    UserRepository userRepository;

    public User createUser(User user) {
        // In a real app: hash password, check for email uniqueness, etc.
        return userRepository.save(user);
    }

    public Optional<User> getUserById(UUID id) {
        return userRepository.findById(id);
    }

    public List<User> listUsers(Role role, Boolean isActive, int page, int size) {
        return userRepository.findByCriteria(role, isActive, page, size);
    }

    public long countUsers(Role role, Boolean isActive) {
        return userRepository.countByCriteria(role, isActive);
    }

    public Optional<User> updateUser(UUID id, User updatedUserData) {
        return userRepository.findById(id).map(existingUser -> {
            existingUser.email = updatedUserData.email;
            existingUser.role = updatedUserData.role;
            existingUser.is_active = updatedUserData.is_active;
            // Password updates should be handled separately
            userRepository.save(existingUser);
            return existingUser;
        });
    }

    public boolean deleteUser(UUID id) {
        if (userRepository.findById(id).isPresent()) {
            userRepository.deleteById(id);
            return true;
        }
        return false;
    }
}

// --- RESOURCE LAYER (CONTROLLER) ---

@Path("/v1/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource {

    @Inject
    UserService userService;

    @POST
    public Response createUser(User user, @Context UriInfo uriInfo) {
        User createdUser = userService.createUser(user);
        var uri = uriInfo.getAbsolutePathBuilder().path(createdUser.id.toString()).build();
        return Response.created(uri).entity(createdUser).build();
    }

    @GET
    @Path("/{id}")
    public Response getUserById(@PathParam("id") UUID id) {
        return userService.getUserById(id)
                .map(user -> Response.ok(user).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    public Response listUsers(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("role") Role role,
            @QueryParam("isActive") Boolean isActive) {
        List<User> users = userService.listUsers(role, isActive, page, size);
        long totalUsers = userService.countUsers(role, isActive);
        return Response.ok(users)
                .header("X-Total-Count", totalUsers)
                .build();
    }

    @PUT
    @Path("/{id}")
    public Response updateUser(@PathParam("id") UUID id, User user) {
        return userService.updateUser(id, user)
                .map(updatedUser -> Response.ok(updatedUser).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/{id}")
    public Response deleteUser(@PathParam("id") UUID id) {
        if (userService.deleteUser(id)) {
            return Response.noContent().build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }
}