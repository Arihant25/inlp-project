package org.acme.rest.users.v4;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

// --- DOMAIN ENTITIES ---

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

// --- DATA TRANSFER OBJECTS (DTOs) ---

class UserDto {
    public UUID id;
    public String email;
    public Role role;
    public boolean isActive;
    public Timestamp createdAt;
}

class CreateUserRequest {
    @NotBlank(message = "Email cannot be blank")
    @Email(message = "Email should be valid")
    public String email;

    @NotBlank(message = "Password cannot be blank")
    @Size(min = 8, message = "Password must be at least 8 characters long")
    public String password;

    @NotNull(message = "Role cannot be null")
    public Role role;
}

class UpdateUserRequest {
    @Email(message = "Email should be valid")
    public String email; // Optional
    public Role role; // Optional
    public Boolean isActive; // Optional
}

// --- MAPPER ---

@ApplicationScoped
class UserMapper {
    public UserDto toDto(User user) {
        UserDto dto = new UserDto();
        dto.id = user.id;
        dto.email = user.email;
        dto.role = user.role;
        dto.isActive = user.is_active;
        dto.createdAt = user.created_at;
        return dto;
    }

    public User toEntity(CreateUserRequest request) {
        User user = new User();
        user.email = request.email;
        user.password_hash = "hashed_" + request.password; // Hashing should be in service
        user.role = request.role;
        user.is_active = true; // Default to active on creation
        user.created_at = Timestamp.from(Instant.now());
        return user;
    }
}

// --- REPOSITORY (Same as V1 for brevity) ---

@ApplicationScoped
class UserRepositoryV4 {
    private final Map<UUID, User> userStore = new ConcurrentHashMap<>();

    public UserRepositoryV4() {
        User u1 = new User();
        u1.id = UUID.randomUUID();
        u1.email = "user.dto@example.com";
        u1.password_hash = "hashed_pw";
        u1.role = Role.USER;
        u1.is_active = true;
        u1.created_at = Timestamp.from(Instant.now());
        userStore.put(u1.id, u1);
    }

    public User save(User user) {
        if (user.id == null) user.id = UUID.randomUUID();
        userStore.put(user.id, user);
        return user;
    }

    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(userStore.get(id));
    }

    public List<User> findByCriteria(Role role, Boolean isActive, int page, int size) {
        return userStore.values().stream()
                .filter(u -> role == null || u.role == role)
                .filter(u -> isActive == null || u.is_active.equals(isActive))
                .skip((long) page * size)
                .limit(size)
                .collect(Collectors.toList());
    }
    
    public long countByCriteria(Role role, Boolean isActive) {
        return userStore.values().stream()
                .filter(u -> role == null || u.role == role)
                .filter(u -> isActive == null || u.is_active.equals(isActive))
                .count();
    }

    public void deleteById(UUID id) {
        userStore.remove(id);
    }
}

// --- SERVICE LAYER ---

@ApplicationScoped
class UserServiceV4 {
    @Inject UserRepositoryV4 userRepo;

    public User createUser(User user) {
        // In real app: check for email uniqueness
        return userRepo.save(user);
    }

    public Optional<User> getUser(UUID id) {
        return userRepo.findById(id);
    }

    public List<User> listUsers(Role role, Boolean isActive, int page, int size) {
        return userRepo.findByCriteria(role, isActive, page, size);
    }
    
    public long countUsers(Role role, Boolean isActive) {
        return userRepo.countByCriteria(role, isActive);
    }

    public Optional<User> patchUser(UUID id, UpdateUserRequest patchRequest) {
        Optional<User> userOpt = userRepo.findById(id);
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }
        User userToUpdate = userOpt.get();
        if (patchRequest.email != null) {
            userToUpdate.email = patchRequest.email;
        }
        if (patchRequest.role != null) {
            userToUpdate.role = patchRequest.role;
        }
        if (patchRequest.isActive != null) {
            userToUpdate.is_active = patchRequest.isActive;
        }
        return Optional.of(userRepo.save(userToUpdate));
    }

    public boolean deleteUser(UUID id) {
        if (userRepo.findById(id).isPresent()) {
            userRepo.deleteById(id);
            return true;
        }
        return false;
    }
}

// --- RESOURCE LAYER ---

@Path("/v4/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResourceWithDto {

    @Inject UserServiceV4 userSvc;
    @Inject UserMapper userMapper;

    @POST
    public Response createUser(@Valid CreateUserRequest request, @Context UriInfo uriInfo) {
        User newUser = userMapper.toEntity(request);
        User createdUser = userSvc.createUser(newUser);
        UserDto responseDto = userMapper.toDto(createdUser);
        var uri = uriInfo.getAbsolutePathBuilder().path(responseDto.id.toString()).build();
        return Response.created(uri).entity(responseDto).build();
    }

    @GET
    @Path("/{id}")
    public Response getUserById(@PathParam("id") UUID id) {
        return userSvc.getUser(id)
                .map(userMapper::toDto)
                .map(dto -> Response.ok(dto).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    public Response listUsers(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("role") Role role,
            @QueryParam("isActive") Boolean isActive) {
        List<User> users = userSvc.listUsers(role, isActive, page, size);
        List<UserDto> dtos = users.stream()
                .map(userMapper::toDto)
                .collect(Collectors.toList());
        long totalCount = userSvc.countUsers(role, isActive);
        return Response.ok(dtos).header("X-Total-Count", totalCount).build();
    }

    @PATCH
    @Path("/{id}")
    public Response updateUser(@PathParam("id") UUID id, @Valid UpdateUserRequest request) {
        return userSvc.patchUser(id, request)
                .map(userMapper::toDto)
                .map(dto -> Response.ok(dto).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/{id}")
    public Response deleteUser(@PathParam("id") UUID id) {
        if (userSvc.deleteUser(id)) {
            return Response.noContent().build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
}