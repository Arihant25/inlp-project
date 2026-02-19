package com.example.files.v1;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// JAX-RS Resource - The entry point for API calls
@Path("/v1/files")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.MULTIPART_FORM_DATA)
public class FileOperationsResourceV1 {

    @Inject
    UserServiceV1 userService;

    @Inject
    PostServiceV1 postService;

    @Inject
    FileProcessingServiceV1 fileProcessingService;

    @POST
    @Path("/users/import/csv")
    public Response uploadUsersCsv(FileUpload fileUpload) {
        if (fileUpload == null || !"text/csv".equals(fileUpload.contentType())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"error\":\"Invalid CSV file\"}").build();
        }
        try {
            List<UserV1> parsedUsers = fileProcessingService.parseUsersFromCsv(fileUpload.uploadedFile());
            int importedCount = userService.batchImportUsers(parsedUsers);
            return Response.ok("{\"message\":\"Successfully imported " + importedCount + " users.\"}").build();
        } catch (IOException e) {
            return Response.serverError().entity("{\"error\":\"Failed to process file: " + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/posts/{postId}/image")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadPostImage(
            @PathParam("postId") UUID postId,
            @org.jboss.resteasy.reactive.RestForm("image") FileUpload imageFile) {

        if (imageFile == null || !imageFile.contentType().startsWith("image/")) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"error\":\"Image file is required.\"}").build();
        }

        PostV1 post = postService.findPostById(postId);
        if (post == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Path tempResizedFile = null;
        try {
            tempResizedFile = fileProcessingService.resizeImage(imageFile.uploadedFile(), 800, 600);
            // In a real app, you would move this file to permanent storage (e.g., S3)
            // and save the URL in the Post entity.
            System.out.println("Resized image for post " + postId + " is at: " + tempResizedFile.toAbsolutePath());
            return Response.ok("{\"message\":\"Image processed and associated with post " + postId + "\"}").build();
        } catch (IOException e) {
            return Response.serverError().entity("{\"error\":\"Could not process image: " + e.getMessage() + "\"}").build();
        } finally {
            if (tempResizedFile != null) {
                try {
                    Files.deleteIfExists(tempResizedFile);
                } catch (IOException e) {
                    System.err.println("Failed to delete temporary file: " + tempResizedFile);
                }
            }
        }
    }

    @GET
    @Path("/posts/export/csv")
    @Produces("text/csv")
    public Response downloadPostsAsCsv() {
        StreamingOutput stream = output -> {
            try (Stream<PostV1> postStream = postService.getAllPostsStream()) {
                output.write("id,user_id,title,status\n".getBytes(StandardCharsets.UTF_8));
                postStream.forEach(post -> {
                    try {
                        String line = String.format("%s,%s,\"%s\",%s\n",
                                post.id, post.userId, post.title.replace("\"", "\"\""), post.status);
                        output.write(line.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        };

        return Response.ok(stream)
                .header("Content-Disposition", "attachment; filename=\"posts_export.csv\"")
                .build();
    }
}

// --- Service Layer ---

@ApplicationScoped
class FileProcessingServiceV1 {

    /**
     * Parses a CSV file into a list of UserV1 objects.
     * Assumes CSV format: email,password_hash,role
     */
    public List<UserV1> parseUsersFromCsv(Path csvPath) throws IOException {
        List<UserV1> users = new ArrayList<>();
        List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
        // Skip header line
        for (int i = 1; i < lines.size(); i++) {
            String[] fields = lines.get(i).split(",");
            if (fields.length >= 3) {
                UserV1 user = new UserV1();
                user.id = UUID.randomUUID();
                user.email = fields[0].trim();
                user.passwordHash = fields[1].trim(); // In reality, you'd hash a plain password
                user.role = UserRoleV1.valueOf(fields[2].trim().toUpperCase());
                user.isActive = true;
                user.createdAt = Timestamp.from(Instant.now());
                users.add(user);
            }
        }
        return users;
    }

    /**
     * Resizes an image to the specified dimensions and saves it to a new temporary file.
     * @return Path to the new temporary resized image.
     */
    public Path resizeImage(Path sourcePath, int targetWidth, int targetHeight) throws IOException {
        BufferedImage originalImage = ImageIO.read(sourcePath.toFile());
        if (originalImage == null) {
            throw new IOException("Could not read image file. Unsupported format.");
        }

        BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics2D = resizedImage.createGraphics();
        graphics2D.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
        graphics2D.dispose();

        Path tempFile = Files.createTempFile("resized-", ".jpg");
        ImageIO.write(resizedImage, "jpg", tempFile.toFile());
        return tempFile;
    }
}

@ApplicationScoped
class UserServiceV1 {
    private static final Map<UUID, UserV1> MOCK_USER_DB = new ConcurrentHashMap<>();

    public int batchImportUsers(List<UserV1> users) {
        int importCount = 0;
        for (UserV1 user : users) {
            if (!MOCK_USER_DB.containsKey(user.id)) {
                MOCK_USER_DB.put(user.id, user);
                importCount++;
            }
        }
        System.out.println("Total users in DB: " + MOCK_USER_DB.size());
        return importCount;
    }
}

@ApplicationScoped
class PostServiceV1 {
    private static final Map<UUID, PostV1> MOCK_POST_DB = new ConcurrentHashMap<>();

    static {
        UUID userId = UUID.randomUUID();
        UUID postId1 = UUID.randomUUID();
        MOCK_POST_DB.put(postId1, new PostV1(postId1, userId, "First Post", "Content here", PostStatusV1.PUBLISHED));
        UUID postId2 = UUID.randomUUID();
        MOCK_POST_DB.put(postId2, new PostV1(postId2, userId, "Second Post", "More content", PostStatusV1.DRAFT));
    }

    public PostV1 findPostById(UUID id) {
        return MOCK_POST_DB.get(id);
    }

    public Stream<PostV1> getAllPostsStream() {
        return MOCK_POST_DB.values().stream();
    }
}

// --- Domain Model ---

class UserV1 {
    public UUID id;
    public String email;
    public String passwordHash;
    public UserRoleV1 role;
    public boolean isActive;
    public Timestamp createdAt;
}

enum UserRoleV1 {
    ADMIN, USER
}

class PostV1 {
    public UUID id;
    public UUID userId;
    public String title;
    public String content;
    public PostStatusV1 status;

    public PostV1(UUID id, UUID userId, String title, String content, PostStatusV1 status) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.content = content;
        this.status = status;
    }
}

enum PostStatusV1 {
    DRAFT, PUBLISHED
}