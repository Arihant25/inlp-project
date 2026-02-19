package org.acme.rest.users.v3;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
}

class Post {
    public enum Status { DRAFT, PUBLISHED }
    public UUID id;
    public UUID user_id;
    public String title;
    public String content;
    public Status status;
}

// --- REACTIVE DATA STORE (SIMULATED) ---

@ApplicationScoped
class InMemoryReactiveDataStore {
    private final Map<UUID, User> userStore = new ConcurrentHashMap<>();

    public InMemoryReactiveDataStore() {
        // Seed data
        User admin = new User();
        admin.id = UUID.randomUUID();
        admin.email = "admin.reactive@example.com";
        admin.password_hash = "hashed_pw";
        admin.role = Role.ADMIN;
        admin.is_active = true;
        admin.created_at = Timestamp.from(Instant.now());
        userStore.put(admin.id, admin);

        User user = new User();
        user.id = UUID.randomUUID();
        user.email = "user.reactive@example.com";
        user.password_hash = "hashed_pw";
        user.role = Role.USER;
        user.is_active = true;
        user.created_at = Timestamp.from(Instant.now());
        userStore.put(user.id, user);
    }

    private <T> Uni<T> simulateLatency(T item) {
        return Uni.createFrom().item(item).onItem().delayIt().by(Duration.ofMillis(10));
    }

    public Uni<User> findById(UUID id) {
        return simulateLatency(userStore.get(id));
    }

    public Multi<User> findAll() {
        return Multi.createFrom().iterable(userStore.values())
                .onItem().call(this::simulateLatency);
    }

    public Uni<User> save(User user) {
        if (user.id == null) {
            user.id = UUID.randomUUID();
            user.created_at = Timestamp.from(Instant.now());
        }
        userStore.put(user.id, user);
        return simulateLatency(user);
    }

    public Uni<Boolean> deleteById(UUID id) {
        boolean removed = userStore.remove(id) != null;
        return simulateLatency(removed);
    }
}

// --- REACTIVE SERVICE LAYER ---

@ApplicationScoped
class ReactiveUserService {

    @Inject
    InMemoryReactiveDataStore dataStore;

    public Uni<User> findUserById(UUID id) {
        return dataStore.findById(id);
    }

    public Multi<User> findUsers(Role role, Boolean isActive) {
        return dataStore.findAll()
                .filter(u -> role == null || u.role == role)
                .filter(u -> isActive == null || u.is_active.equals(isActive));
    }

    public Uni<User> createUser(User user) {
        user.password_hash = "hashed_" + user.password_hash;
        return dataStore.save(user);
    }

    public Uni<User> updateUser(UUID id, User updates) {
        return dataStore.findById(id)
                .onItem().ifNotNull().transformToUni(existingUser -> {
                    existingUser.email = updates.email;
                    existingUser.role = updates.role;
                    existingUser.is_active = updates.is_active;
                    return dataStore.save(existingUser);
                });
    }

    public Uni<Boolean> deleteUser(UUID id) {
        return dataStore.deleteById(id);
    }
}

// --- REACTIVE RESOURCE LAYER ---

@Path("/v3/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReactiveUserResource {

    @Inject
    ReactiveUserService userService;

    @GET
    @Path("/{id}")
    public Uni<Response> getUserById(@PathParam("id") UUID id) {
        return userService.findUserById(id)
                .onItem().ifNotNull().transform(user -> Response.ok(user).build())
                .onItem().ifNull().continueWith(Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    public Uni<Response> listUsers(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("role") Role role,
            @QueryParam("isActive") Boolean isActive) {
        
        Uni<List<User>> usersPage = userService.findUsers(role, isActive)
                .skip((long) page * size)
                .select().first(size)
                .collect().asList();

        Uni<Long> totalCount = userService.findUsers(role, isActive).count();

        return Uni.combine().all().unis(usersPage, totalCount).asTuple()
                .onItem().transform(tuple -> Response.ok(tuple.getItem1())
                        .header("X-Total-Count", tuple.getItem2())
                        .build());
    }

    @POST
    public Uni<Response> createUser(User user, @Context UriInfo uriInfo) {
        return userService.createUser(user)
                .onItem().transform(createdUser -> {
                    var uri = uriInfo.getAbsolutePathBuilder().path(createdUser.id.toString()).build();
                    return Response.created(uri).entity(createdUser).build();
                });
    }

    @PUT
    @Path("/{id}")
    public Uni<Response> updateUser(@PathParam("id") UUID id, User user) {
        return userService.updateUser(id, user)
                .onItem().ifNotNull().transform(updatedUser -> Response.ok(updatedUser).build())
                .onItem().ifNull().continueWith(Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/{id}")
    public Uni<Response> deleteUser(@PathParam("id") UUID id) {
        return userService.deleteUser(id)
                .onItem().transform(deleted -> deleted
                        ? Response.noContent().build()
                        : Response.status(Response.Status.NOT_FOUND).build());
    }
}