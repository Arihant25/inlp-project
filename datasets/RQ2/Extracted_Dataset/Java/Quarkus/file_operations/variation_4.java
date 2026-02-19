package com.example.files.v4;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.resteasy.reactive.MultipartForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
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
import java.util.stream.Stream;

// --- DTOs and Form Beans ---

class UserImportForm {
    @NotNull
    public FileUpload userCsvFile;
}

class PostImageForm {
    @NotNull
    public FileUpload imageFile;
}

// --- Custom Exceptions ---

class FileProcessingException extends RuntimeException {
    public FileProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}

class EntityNotFoundException extends RuntimeException {
    public EntityNotFoundException(String message) {
        super(message);
    }
}

// --- JAX-RS Resource (Controller Layer) ---

@Path("/v4/files")
public class EnterpriseFileResourceV4 {

    private final FileOperationFacadeV4 fileFacade;

    @Inject
    public EnterpriseFileResourceV4(FileOperationFacadeV4 fileFacade) {
        this.fileFacade = fileFacade;
    }

    @POST
    @Path("/users/import-batch")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response importUsers(@MultipartForm UserImportForm form) {
        try {
            int count = fileFacade.importUsersFromCsv(form.userCsvFile);
            return Response.ok(Map.of("importedUserCount", count)).build();
        } catch (FileProcessingException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", "An unexpected error occurred.")).build();
        }
    }

    @POST
    @Path("/posts/{postId}/image")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadPostImage(@PathParam("postId") UUID postId, @MultipartForm PostImageForm form) {
        try {
            String storedImagePath = fileFacade.processAndAssignImageToPost(postId, form.imageFile);
            return Response.ok(Map.of("imagePath", storedImagePath)).build();
        } catch (EntityNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        } catch (FileProcessingException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/posts/export")
    @Produces("text/csv")
    public Response exportPosts() {
        StreamingOutput stream = fileFacade.getPostsAsCsvStream();
        return Response.ok(stream)
                .header("Content-Disposition", "attachment; filename=\"posts_v4.csv\"")
                .build();
    }
}

// --- Facade Layer ---

@ApplicationScoped
class FileOperationFacadeV4 {

    private final UserServiceV4 userService;
    private final PostServiceV4 postService;
    private final FileProcessorV4 fileProcessor;

    @Inject
    public FileOperationFacadeV4(UserServiceV4 userService, PostServiceV4 postService, FileProcessorV4 fileProcessor) {
        this.userService = userService;
        this.postService = postService;
        this.fileProcessor = fileProcessor;
    }

    public int importUsersFromCsv(FileUpload fileUpload) {
        if (fileUpload == null || !"text/csv".equals(fileUpload.contentType())) {
            throw new FileProcessingException("Invalid or missing CSV file.", null);
        }
        try {
            List<UserV4> users = fileProcessor.parseUsersFromCsv(fileUpload.uploadedFile());
            return userService.saveAll(users);
        } catch (IOException e) {
            throw new FileProcessingException("Failed to read or parse CSV file.", e);
        }
    }



    public String processAndAssignImageToPost(UUID postId, FileUpload imageUpload) {
        if (imageUpload == null || !imageUpload.contentType().startsWith("image/")) {
            throw new FileProcessingException("Invalid or missing image file.", null);
        }
        PostV4 post = postService.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("Post with ID " + postId + " not found."));

        Path tempFile = null;
        try {
            tempFile = fileProcessor.resizeImage(imageUpload.uploadedFile(), 1200, 800);
            // Simulate moving to permanent storage
            String permanentPath = "/storage/images/" + post.id + "/" + tempFile.getFileName().toString();
            System.out.println("Moving " + tempFile + " to " + permanentPath);
            // post.setImageUrl(permanentPath);
            // postService.save(post);
            return permanentPath;
        } catch (IOException e) {
            throw new FileProcessingException("Image processing failed.", e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    System.err.println("Could not delete temp file: " + tempFile);
                }
            }
        }
    }

    public StreamingOutput getPostsAsCsvStream() {
        return output -> {
            try (Stream<PostV4> posts = postService.findAll()) {
                output.write("id,title,status\n".getBytes(StandardCharsets.UTF_8));
                posts.forEach(post -> {
                    try {
                        String line = String.format("%s,\"%s\",%s\n", post.id, post.title, post.status);
                        output.write(line.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        };
    }
}

// --- Service and Repository Layer ---

interface FileProcessorV4 {
    List<UserV4> parseUsersFromCsv(Path path) throws IOException;
    Path resizeImage(Path source, int width, int height) throws IOException;
}

@ApplicationScoped
class DefaultFileProcessorV4 implements FileProcessorV4 {
    @Override
    public List<UserV4> parseUsersFromCsv(Path path) throws IOException {
        List<UserV4> userList = new ArrayList<>();
        // Using try-with-resources for the stream
        try (Stream<String> lines = Files.lines(path).skip(1)) {
            lines.forEach(line -> {
                String[] fields = line.split(",");
                if (fields.length >= 2) {
                    userList.add(new UserV4(UUID.randomUUID(), fields[0], fields[1], UserRoleV4.USER, true, Timestamp.from(Instant.now())));
                }
            });
        }
        return userList;
    }

    @Override
    public Path resizeImage(Path source, int width, int height) throws IOException {
        BufferedImage sourceImage = ImageIO.read(source.toFile());
        BufferedImage outputImage = new BufferedImage(width, height, sourceImage.getType());
        outputImage.getGraphics().drawImage(sourceImage, 0, 0, width, height, null);
        Path tempDest = Files.createTempFile("processed-img-", ".jpg");
        ImageIO.write(outputImage, "jpg", tempDest.toFile());
        return tempDest;
    }
}

@ApplicationScoped
class UserServiceV4 {
    private static final Map<UUID, UserV4> DB = new ConcurrentHashMap<>();
    public int saveAll(List<UserV4> users) {
        users.forEach(u -> DB.put(u.id, u));
        return users.size();
    }
}

@ApplicationScoped
class PostServiceV4 {
    private static final Map<UUID, PostV4> DB = new ConcurrentHashMap<>();
    static {
        UUID uid = UUID.randomUUID();
        PostV4 p = new PostV4(UUID.randomUUID(), uid, "Enterprise Post", "Content", PostStatusV4.PUBLISHED);
        DB.put(p.id, p);
    }
    public java.util.Optional<PostV4> findById(UUID id) {
        return java.util.Optional.ofNullable(DB.get(id));
    }
    public Stream<PostV4> findAll() {
        return DB.values().stream();
    }
}

// --- Domain Model ---

record UserV4(UUID id, String email, String passwordHash, UserRoleV4 role, boolean isActive, Timestamp createdAt) {}
enum UserRoleV4 { ADMIN, USER }

record PostV4(UUID id, UUID userId, String title, String content, PostStatusV4 status) {}
enum PostStatusV4 { DRAFT, PUBLISHED }