package com.example.files.v3;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.server.types.files.StreamedFile;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// --- Domain Model ---
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

record User(UUID id, String email, String password_hash, UserRole role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}

// --- Mock Repositories (Injected directly into Controller) ---
@Singleton
class UserRepository {
    private final Map<UUID, User> users = new ConcurrentHashMap<>();
    public void batchInsert(List<User> userList) {
        userList.forEach(u -> users.put(u.id(), u));
        System.out.printf("[Repo] Inserted %d users.%n", userList.size());
    }
}

@Singleton
class PostRepository {
    private final Map<UUID, Post> posts = new ConcurrentHashMap<>();
    public PostRepository() {
        UUID userId = UUID.randomUUID();
        posts.put(UUID.randomUUID(), new Post(UUID.randomUUID(), userId, "Title A", "Content A", PostStatus.PUBLISHED));
        posts.put(UUID.randomUUID(), new Post(UUID.randomUUID(), userId, "Title B", "Content B", PostStatus.PUBLISHED));
    }
    public List<Post> listAll() {
        return List.copyOf(posts.values());
    }
}

// --- All-in-One Controller Approach ---
@Controller("/v3/files")
public class FileUploadController {

    @Inject
    private UserRepository userRepo;

    @Inject
    private PostRepository postRepo;

    @Post(value = "/users", consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<Map<String, Object>> uploadUsers(CompletedFileUpload data) {
        if (data.getFilename() == null || data.getFilename().isEmpty()) {
            return HttpResponse.badRequest(Map.of("error", "File name is missing."));
        }

        try (InputStream inputStream = data.getInputStream()) {
            List<User> parsedUsers = this.parseUsersFromCsv(inputStream);
            userRepo.batchInsert(parsedUsers);
            return HttpResponse.created(Map.of(
                "message", "Users created successfully",
                "count", parsedUsers.size()
            ));
        } catch (Exception e) {
            return HttpResponse.serverError(Map.of("error", "Could not process CSV file: " + e.getMessage()));
        }
    }

    @Post(value = "/posts/{postId}/visual", consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<String> postImage(UUID postId, CompletedFileUpload imageFile) {
        Path tempResizedFile = null;
        try (InputStream is = imageFile.getInputStream()) {
            tempResizedFile = this.resizeImage(is, 1024, 768);
            // In a real app, this would be uploaded to S3 or a CDN
            String storagePath = "/cdn/posts/" + postId + "/" + tempResizedFile.getFileName().toString();
            System.out.println("Image for post " + postId + " stored at " + storagePath);
            return HttpResponse.ok("Image uploaded to " + storagePath);
        } catch (IOException e) {
            return HttpResponse.serverError("Image processing failed: " + e.getMessage());
        } finally {
            if (tempResizedFile != null) {
                try {
                    Files.delete(tempResizedFile);
                } catch (IOException e) {
                    System.err.println("Failed to delete temp file: " + tempResizedFile);
                }
            }
        }
    }

    @Get(value = "/report", produces = MediaType.TEXT_CSV)
    public StreamedFile getReport() {
        InputStream reportStream = this.generatePostsCsv();
        return new StreamedFile(reportStream, MediaType.TEXT_CSV_TYPE).attach("posts_report_v3.csv");
    }

    // --- Private Helper Methods within the Controller ---

    private List<User> parseUsersFromCsv(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            return reader.lines()
                .skip(1) // Skip header row
                .map(line -> line.split(","))
                .filter(parts -> parts.length >= 3)
                .map(parts -> new User(
                    UUID.randomUUID(),
                    parts[0].trim(),
                    "default_pass",
                    UserRole.valueOf(parts[1].trim().toUpperCase()),
                    Boolean.parseBoolean(parts[2].trim()),
                    Timestamp.from(Instant.now())
                ))
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path resizeImage(InputStream source, int width, int height) throws IOException {
        BufferedImage original = ImageIO.read(source);
        Image scaled = original.getScaledInstance(width, height, Image.SCALE_SMOOTH);
        BufferedImage outputImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        outputImage.getGraphics().drawImage(scaled, 0, 0, null);

        Path tempFile = Files.createTempFile("thumb-", ".jpg");
        ImageIO.write(outputImage, "jpg", tempFile.toFile());
        return tempFile;
    }

    private InputStream generatePostsCsv() {
        List<Post> posts = postRepo.listAll();
        String header = "ID,USER_ID,TITLE,STATUS\n";
        
        String content = posts.stream()
            .map(p -> String.join(",", p.id().toString(), p.user_id().toString(), p.title(), p.status().name()))
            .collect(Collectors.joining("\n"));

        return new ByteArrayInputStream((header + content).getBytes());
    }
}