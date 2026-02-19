package com.example.files.v1;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.server.types.files.StreamedFile;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// --- Domain Model ---
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

record User(UUID id, String email, String password_hash, UserRole role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}

// --- Mock Services ---
@Singleton
class MockUserService {
    private final Map<UUID, User> users = new ConcurrentHashMap<>();
    public void saveAll(List<User> userList) {
        userList.forEach(user -> users.put(user.id(), user));
        System.out.printf("Saved %d users. Total users: %d%n", userList.size(), users.size());
    }
}

@Singleton
class MockPostService {
    private final Map<UUID, Post> posts = new ConcurrentHashMap<>();
    public MockPostService() {
        UUID userId = UUID.randomUUID();
        posts.put(UUID.randomUUID(), new Post(UUID.randomUUID(), userId, "First Post", "Content 1", PostStatus.PUBLISHED));
        posts.put(UUID.randomUUID(), new Post(UUID.randomUUID(), userId, "Second Post", "Content 2", PostStatus.DRAFT));
    }
    public Optional<Post> findById(UUID postId) {
        return posts.values().stream().filter(p -> p.id().equals(postId)).findFirst();
    }
    public List<Post> findAll() {
        return new ArrayList<>(posts.values());
    }
}

@Singleton
class MockStorageService {
    public String saveImage(UUID postId, Path imageFile) {
        System.out.printf("Storing image '%s' for post %s%n", imageFile.getFileName(), postId);
        return "/images/" + postId + "/" + imageFile.getFileName();
    }
}


// --- Core Logic: Service-Oriented Approach ---

@Singleton
class FileOperationsService {

    private final MockUserService userService;
    private final MockPostService postService;
    private final MockStorageService storageService;

    @Inject
    public FileOperationsService(MockUserService userService, MockPostService postService, MockStorageService storageService) {
        this.userService = userService;
        this.postService = postService;
        this.storageService = storageService;
    }

    public int processUserCsvUpload(InputStream inputStream) throws IOException {
        List<User> usersToCreate = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            reader.readLine(); // Skip header
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(",");
                if (fields.length == 3) {
                    User user = new User(
                        UUID.randomUUID(),
                        fields[0].trim(),
                        "hashed_password_placeholder", // In real app, hash the password
                        UserRole.valueOf(fields[1].trim().toUpperCase()),
                        Boolean.parseBoolean(fields[2].trim()),
                        Timestamp.from(Instant.now())
                    );
                    usersToCreate.add(user);
                }
            }
        }
        if (!usersToCreate.isEmpty()) {
            userService.saveAll(usersToCreate);
        }
        return usersToCreate.size();
    }

    public String resizeAndStorePostImage(UUID postId, CompletedFileUpload fileUpload) throws IOException {
        if (postService.findById(postId).isEmpty()) {
            throw new IllegalArgumentException("Post not found");
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("resized-", ".jpg");
            BufferedImage originalImage = ImageIO.read(fileUpload.getInputStream());
            
            int targetWidth = 800;
            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();
            int targetHeight = (int) ((double) originalHeight / originalWidth * targetWidth);

            BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resizedImage.createGraphics();
            g.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
            g.dispose();

            ImageIO.write(resizedImage, "jpg", tempFile.toFile());

            return storageService.saveImage(postId, tempFile);
        } finally {
            if (tempFile != null) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    public InputStream generatePostsCsvReport() {
        List<Post> posts = postService.findAll();
        StringWriter stringWriter = new StringWriter();
        
        // Header
        stringWriter.append("id,user_id,title,status\n");

        // Data
        for (Post post : posts) {
            stringWriter.append(String.join(",",
                post.id().toString(),
                post.user_id().toString(),
                "\"" + post.title().replace("\"", "\"\"") + "\"",
                post.status().name()
            )).append("\n");
        }
        
        return new ByteArrayInputStream(stringWriter.toString().getBytes());
    }
}


// --- Controller ---

@Controller("/v1/files")
public class FileOperationsController {

    private final FileOperationsService fileOperationsService;

    @Inject
    public FileOperationsController(FileOperationsService fileOperationsService) {
        this.fileOperationsService = fileOperationsService;
    }

    @Post(value = "/users/upload", consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<String> uploadUsersCsv(CompletedFileUpload file) {
        if (file.getFilename() == null || !file.getFilename().toLowerCase().endsWith(".csv")) {
            return HttpResponse.badRequest("Only CSV files are allowed.");
        }
        try {
            int usersCreated = fileOperationsService.processUserCsvUpload(file.getInputStream());
            return HttpResponse.ok(usersCreated + " users created successfully.");
        } catch (IOException e) {
            return HttpResponse.serverError("Failed to process file: " + e.getMessage());
        }
    }

    @Post(value = "/posts/{postId}/image", consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<String> uploadPostImage(UUID postId, CompletedFileUpload file) {
        try {
            String imageUrl = fileOperationsService.resizeAndStorePostImage(postId, file);
            return HttpResponse.ok("Image processed and stored at: " + imageUrl);
        } catch (IllegalArgumentException e) {
            return HttpResponse.notFound(e.getMessage());
        } catch (IOException e) {
            return HttpResponse.serverError("Failed to process image: " + e.getMessage());
        }
    }

    @Get(value = "/posts/report", produces = MediaType.TEXT_CSV)
    public StreamedFile downloadPostsReport() {
        InputStream csvStream = fileOperationsService.generatePostsCsvReport();
        return new StreamedFile(csvStream, MediaType.TEXT_CSV_TYPE).attach("posts_report.csv");
    }
}