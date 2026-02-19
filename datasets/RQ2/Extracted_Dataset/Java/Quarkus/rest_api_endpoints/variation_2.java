package org.acme.rest.users.v2;

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// --- DOMAIN & MOCK PANACHE INFRASTRUCTURE ---

enum Role {
    ADMIN, USER
}

// This class simulates the PanacheQuery object for a fluent API
class MockPanacheQuery<T> {
    private Stream<T> stream;
    private long count;

    public MockPanacheQuery(Stream<T> stream, long count) {
        this.stream = stream;
        this.count = count;
    }

    public MockPanacheQuery<T> page(Page page) {
        this.stream = this.stream.skip((long) page.index * page.size).limit(page.size);
        return this;
    }

    public List<T> list() {
        return this.stream.collect(Collectors.toList());
    }

    public long count() {
        return this.count;
    }
}

// This class simulates the PanacheEntityBase with static methods
abstract class MockPanacheEntityBase {
    public UUID id;
    private static final Map<Class<?>, Map<UUID, Object>> MOCK_DB = new ConcurrentHashMap<>();

    protected static void initDataStore(Class<?> clazz) {
        MOCK_DB.putIfAbsent(clazz, new ConcurrentHashMap<>());
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<UUID, T> getStoreFor(Class<T> clazz) {
        return (Map<UUID, T>) MOCK_DB.get(clazz);
    }

    public void persist() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        getStoreFor(this.getClass()).put(this.id, this);
    }

    public void delete() {
        getStoreFor(this.getClass()).remove(this.id);
    }

    public static <T extends MockPanacheEntityBase> T findById(UUID id, Class<T> clazz) {
        return getStoreFor(clazz).get(id);
    }

    public static <T extends MockPanacheEntityBase> MockPanacheQuery<T> find(String query, Parameters params, Class<T> clazz) {
        Stream<T> stream = getStoreFor(clazz).values().stream();
        for (Map.Entry<String, Object> entry : params.map().entrySet()) {
            stream = stream.filter(entity -> {
                try {
                    var field = entity.getClass().getField(entry.getKey());
                    return field.get(entity).equals(entry.getValue());
                } catch (Exception e) {
                    return false;
                }
            });
        }
        // The count should be calculated before pagination is applied to the stream
        List<T> filteredList = stream.collect(Collectors.toList());
        return new MockPanacheQuery<>(filteredList.stream(), filteredList.size());
    }

    public static <T extends MockPanacheEntityBase> MockPanacheQuery<T> findAll(Class<T> clazz) {
        Collection<T> all = getStoreFor(clazz).values();
        return new MockPanacheQuery<>(all.stream(), all.size());
    }

    public static <T extends MockPanacheEntityBase> boolean deleteById(UUID id, Class<T> clazz) {
        return getStoreFor(clazz).remove(id) != null;
    }
}

class User extends MockPanacheEntityBase {
    public String email;
    public String password_hash;
    public Role role;
    public Boolean is_active;
    public Timestamp created_at;

    public User() {}

    // Static "repository" methods, Panache-style
    public static User findById(UUID id) {
        return findById(id, User.class);
    }

    public static MockPanacheQuery<User> find(String query, Parameters params) {
        return find(query, params, User.class);
    }

    public static MockPanacheQuery<User> findAll() {
        return findAll(User.class);
    }

    public static boolean deleteById(UUID id) {
        return deleteById(id, User.class);
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

// --- DATA INITIALIZER ---
@ApplicationScoped
class DataInitializer {
    public DataInitializer() {
        // This ensures the static map is ready for the User entity
        MockPanacheEntityBase.initDataStore(User.class);
        if (User.findAll().count() == 0) {
            User u1 = new User();
            u1.email = "admin.panache@example.com";
            u1.password_hash = "hashed_pw";
            u1.role = Role.ADMIN;
            u1.is_active = true;
            u1.created_at = Timestamp.from(Instant.now());
            u1.persist();

            User u2 = new User();
            u2.email = "user.panache@example.com";
            u2.password_hash = "hashed_pw";
            u2.role = Role.USER;
            u2.is_active = false;
            u2.created_at = Timestamp.from(Instant.now());
            u2.persist();
        }
    }
}

// --- RESOURCE LAYER ---

@Path("/v2/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResourcePanache {

    @POST
    public Response createUser(User user, @Context UriInfo uriInfo) {
        user.password_hash = "hashed_" + user.password_hash; // Mock hashing
        user.created_at = Timestamp.from(Instant.now());
        user.persist();
        var uri = uriInfo.getAbsolutePathBuilder().path(user.id.toString()).build();
        return Response.created(uri).entity(user).build();
    }

    @GET
    @Path("/{id}")
    public Response getUserById(@PathParam("id") UUID id) {
        User user = User.findById(id);
        return user != null ? Response.ok(user).build() : Response.status(Response.Status.NOT_FOUND).build();
    }

    @GET
    public Response listUsers(
            @QueryParam("page") @DefaultValue("0") int pageIndex,
            @QueryParam("size") @DefaultValue("10") int pageSize,
            @QueryParam("role") Role role,
            @QueryParam("isActive") Boolean isActive) {

        Parameters params = new Parameters();
        StringBuilder queryBuilder = new StringBuilder();
        if (role != null) {
            queryBuilder.append("role = :role ");
            params.and("role", role);
        }
        if (isActive != null) {
            if (queryBuilder.length() > 0) queryBuilder.append("and ");
            queryBuilder.append("is_active = :isActive");
            params.and("is_active", isActive);
        }

        MockPanacheQuery<User> query;
        if (queryBuilder.length() > 0) {
            query = User.find(queryBuilder.toString(), params);
        } else {
            query = User.findAll();
        }

        long totalCount = query.count();
        List<User> users = query.page(Page.of(pageIndex, pageSize)).list();

        return Response.ok(users).header("X-Total-Count", totalCount).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateUser(@PathParam("id") UUID id, User updatedUserData) {
        User user = User.findById(id);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        user.email = updatedUserData.email;
        user.role = updatedUserData.role;
        user.is_active = updatedUserData.is_active;
        user.persist(); // In Panache, persist() works for updates too
        return Response.ok(user).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteUser(@PathParam("id") UUID id) {
        boolean deleted = User.deleteById(id);
        if (deleted) {
            return Response.noContent().build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }
}