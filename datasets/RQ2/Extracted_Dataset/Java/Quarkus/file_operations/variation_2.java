package com.example.files.v2;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// Reactive JAX-RS Resource
@Path("/v2/files")
public class ReactiveFileResourceV2 {

    private final DataRepositoryV2 repo;

    public ReactiveFileResourceV2(DataRepositoryV2 repo) {
        this.repo = repo;
    }

    @POST
    @Path("/users/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> importUsers(FileUpload dataFile) {
        if (dataFile == null) {
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST).build());
        }

        return Uni.createFrom().item(dataFile.uploadedFile())
                .onItem().transformToUni(this::parseCsvToUsers)
                .onItem().transformToUni(repo::saveAllUsers)
                .onItem().transform(count -> Response.ok("{\"imported_count\":" + count + "}").build())
                .onFailure().recoverWithItem(err ->
                        Response.serverError().entity("{\"error\":\"" + err.getMessage() + "\"}").build()
                );
    }

    @POST
    @Path("/posts/{id}/image")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> processPostImage(@PathParam("id") UUID id, @RestForm("image") FileUpload image) {
        return repo.findPost(id)
                .onItem().ifNull().failWith(NotFoundException::new)
                .onItem().transformToUni(post -> processAndStoreImage(image.uploadedFile(), post))
                .onItem().transform(path -> Response.ok("{\"image_path\":\"" + path + "\"}").build())
                .onFailure(NotFoundException.class).recoverWithItem(Response.status(Response.Status.NOT_FOUND).build())
                .onFailure().recoverWithItem(err -> Response.serverError().entity("{\"error\":\"" + err.getMessage() + "\"}").build());
    }

    @GET
    @Path("/posts/export")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Multi<byte[]> exportPosts() {
        Multi<String> header = Multi.createFrom().item("id,user_id,title,status\n");
        Multi<String> rows = repo.streamAllPosts()
                .map(p -> String.format("%s,%s,\"%s\",%s\n", p.id, p.userId, p.title, p.status));

        return Multi.createBy().concatenating().streams(header, rows)
                .map(s -> s.getBytes(StandardCharsets.UTF_8));
    }

    // --- Private Helper Methods ---

    private Uni<List<UserV2>> parseCsvToUsers(Path path) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                List<UserV2> users = Files.lines(path).skip(1)
                        .map(line -> line.split(","))
                        .filter(parts -> parts.length >= 2)
                        .map(parts -> new UserV2(
                                UUID.randomUUID(),
                                parts[0].trim(),
                                "hashed_pw_placeholder",
                                UserRoleV2.USER,
                                true,
                                Timestamp.from(Instant.now())
                        ))
                        .collect(Collectors.toList());
                emitter.complete(users);
            } catch (IOException e) {
                emitter.fail(e);
            }
        });
    }

    private Uni<String> processAndStoreImage(Path source, PostV2 post) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                BufferedImage original = ImageIO.read(source.toFile());
                BufferedImage resized = new BufferedImage(100, 100, original.getType());
                resized.getGraphics().drawImage(original, 0, 0, 100, 100, null);

                // Simulate storing and getting a path/URL
                String finalPath = "/images/posts/" + post.id + ".jpg";
                System.out.println("Storing resized image at: " + finalPath);
                emitter.complete(finalPath);
            } catch (Exception e) {
                emitter.fail(e);
            }
        });
    }
}

// --- Mock Reactive Repository ---

@ApplicationScoped
class DataRepositoryV2 {
    private static final Map<UUID, UserV2> USERS = new ConcurrentHashMap<>();
    private static final Map<UUID, PostV2> POSTS = new ConcurrentHashMap<>();

    static {
        UUID uid = UUID.randomUUID();
        PostV2 p1 = new PostV2(UUID.randomUUID(), uid, "Reactive Post 1", "...", PostStatusV2.PUBLISHED);
        PostV2 p2 = new PostV2(UUID.randomUUID(), uid, "Reactive Post 2", "...", PostStatusV2.DRAFT);
        POSTS.put(p1.id, p1);
        POSTS.put(p2.id, p2);
    }

    public Uni<Integer> saveAllUsers(List<UserV2> users) {
        return Uni.createFrom().item(users)
                .onItem().invoke(list -> list.forEach(u -> USERS.putIfAbsent(u.id, u)))
                .map(List::size)
                .onItem().delayIt().by(Duration.ofMillis(50)); // Simulate DB latency
    }

    public Uni<PostV2> findPost(UUID id) {
        return Uni.createFrom().item(POSTS.get(id));
    }

    public Multi<PostV2> streamAllPosts() {
        return Multi.createFrom().iterable(POSTS.values());
    }
}

// --- Domain Model ---

record UserV2(UUID id, String email, String passwordHash, UserRoleV2 role, boolean isActive, Timestamp createdAt) {}
enum UserRoleV2 { ADMIN, USER }

record PostV2(UUID id, UUID userId, String title, String content, PostStatusV2 status) {}
enum PostStatusV2 { DRAFT, PUBLISHED }