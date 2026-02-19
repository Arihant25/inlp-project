package com.example.files.v3;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// All-in-one JAX-RS Resource
@Path("/v3/files")
public class PragmaticFileResourceV3 {

    // Mock database directly in the resource
    private static final Map<UUID, UserV3> USER_STORE = new ConcurrentHashMap<>();
    private static final Map<UUID, PostV3> POST_STORE = new ConcurrentHashMap<>();

    static {
        UUID userId = UUID.randomUUID();
        PostV3 p1 = new PostV3(UUID.randomUUID(), userId, "A Simple Post", "Content.", PostStatusV3.PUBLISHED);
        PostV3 p2 = new PostV3(UUID.randomUUID(), userId, "Another Draft", "...", PostStatusV3.DRAFT);
        POST_STORE.put(p1.id, p1);
        POST_STORE.put(p2.id, p2);
    }

    @POST
    @Path("/users/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleUserUpload(FileUpload upload) {
        if (upload == null) {
            return Response.status(400, "No file uploaded.").build();
        }

        try {
            List<UserV3> users = this.parseUsersFromCsv(upload.uploadedFile());
            users.forEach(u -> USER_STORE.put(u.id, u));
            String responseMessage = "{\"message\":\"Processed " + users.size() + " users.\"}";
            return Response.ok(responseMessage).build();
        } catch (IOException e) {
            return Response.serverError().entity("{\"error\":\"File processing failed: " + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/posts/{postId}/thumbnail")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleImageUpload(@PathParam("postId") UUID postId, FileUpload imageUpload) {
        if (!POST_STORE.containsKey(postId)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (imageUpload == null || !imageUpload.contentType().startsWith("image")) {
            return Response.status(400, "Invalid image file.").build();
        }

        Path tempThumb = null;
        try {
            tempThumb = this.createThumbnail(imageUpload.uploadedFile());
            // In a real app, move tempThumb to a permanent location
            System.out.printf("Thumbnail for post %s created at %s%n", postId, tempThumb.toAbsolutePath());
            return Response.ok("{\"thumbnail_path\":\"" + tempThumb.getFileName() + "\"}").build();
        } catch (Exception e) {
            return Response.serverError().entity("{\"error\":\"Could not create thumbnail: " + e.getMessage() + "\"}").build();
        } finally {
            if (tempThumb != null) {
                try {
                    Files.delete(tempThumb);
                } catch (IOException e) {
                    // Log this error
                }
            }
        }
    }

    @GET
    @Path("/posts/report")
    @Produces("text/csv")
    public Response downloadPostReport() {
        StreamingOutput stream = output -> {
            try (Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                writer.write("ID;TITLE;STATUS\n");
                for (PostV3 post : POST_STORE.values()) {
                    writer.write(String.format("%s;\"%s\";%s\n", post.id, post.title, post.status));
                }
            }
        };

        return Response.ok(stream)
                .header("Content-Disposition", "attachment; filename=posts.csv")
                .build();
    }

    // --- Private helper methods for logic ---

    private List<UserV3> parseUsersFromCsv(Path filePath) throws IOException {
        var userList = new ArrayList<UserV3>();
        var lines = Files.readAllLines(filePath);
        // Skip header
        for (int i = 1; i < lines.size(); i++) {
            String[] data = lines.get(i).split(",");
            if (data.length < 2) continue;
            var user = new UserV3(
                    UUID.randomUUID(),
                    data[0],
                    "default_hash",
                    UserRoleV3.USER,
                    true,
                    Timestamp.from(Instant.now())
            );
            userList.add(user);
        }
        return userList;
    }

    private Path createThumbnail(Path originalImagePath) throws IOException {
        BufferedImage img = ImageIO.read(originalImagePath.toFile());
        if (img == null) throw new IOException("Unsupported image type");

        int thumbWidth = 150;
        int thumbHeight = 150;
        var thumb = new BufferedImage(thumbWidth, thumbHeight, BufferedImage.TYPE_INT_ARGB);

        var g2d = thumb.createGraphics();
        g2d.drawImage(img, 0, 0, thumbWidth, thumbHeight, null);
        g2d.dispose();

        Path tempFile = Files.createTempFile("thumb-", ".png");
        ImageIO.write(thumb, "png", tempFile.toFile());
        return tempFile;
    }
}

// --- Domain Model (defined in the same file for simplicity) ---

record UserV3(UUID id, String email, String passwordHash, UserRoleV3 role, boolean isActive, Timestamp createdAt) {}
enum UserRoleV3 { ADMIN, USER }

record PostV3(UUID id, UUID userId, String title, String content, PostStatusV3 status) {}
enum PostStatusV3 { DRAFT, PUBLISHED }