package com.example.files.v2;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.multipart.StreamingFileUpload;
import io.micronaut.http.server.types.files.StreamedFile;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// --- Domain Model ---
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

record User(UUID id, String email, String password_hash, UserRole role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}

// --- Mock Repositories ---
@Singleton
class MockUserRepository {
    private final Map<UUID, User> userStore = new ConcurrentHashMap<>();
    public Single<User> save(User user) {
        return Single.fromCallable(() -> {
            userStore.put(user.id(), user);
            System.out.println("Reactive save for user: " + user.email());
            return user;
        });
    }
}

@Singleton
class MockPostRepository {
    private final Map<UUID, Post> postStore = new ConcurrentHashMap<>();
    public MockPostRepository() {
        UUID userId = UUID.randomUUID();
        UUID p1Id = UUID.randomUUID();
        UUID p2Id = UUID.randomUUID();
        postStore.put(p1Id, new Post(p1Id, userId, "Reactive Post 1", "Content 1", PostStatus.PUBLISHED));
        postStore.put(p2Id, new Post(p2Id, userId, "Reactive Post 2", "Content 2", PostStatus.DRAFT));
    }
    public Flowable<Post> findAll() {
        return Flowable.fromIterable(postStore.values());
    }
}

// --- Core Logic: Functional & Reactive Approach ---
@Singleton
class ReactiveFileService {

    private final MockUserRepository userRepo;
    private final MockPostRepository postRepo;

    @Inject
    public ReactiveFileService(MockUserRepository userRepo, MockPostRepository postRepo) {
        this.userRepo = userRepo;
        this.postRepo = postRepo;
    }

    public Single<Integer> handleCsvImport(Publisher<byte[]> dataStream) {
        return Flowable.fromPublisher(dataStream)
            .map(String::new)
            .flatMap(chunk -> Flowable.fromArray(chunk.split("\\r?\\n")))
            .skip(1) // Skip header
            .filter(line -> !line.isBlank())
            .map(line -> line.split(","))
            .filter(fields -> fields.length == 3)
            .map(fields -> new User(
                UUID.randomUUID(),
                fields[0].trim(),
                "reactive_hashed_password",
                UserRole.valueOf(fields[1].trim().toUpperCase()),
                Boolean.parseBoolean(fields[2].trim()),
                Timestamp.from(Instant.now())
            ))
            .flatMap(user -> userRepo.save(user).toFlowable())
            .count()
            .map(Long::intValue);
    }

    public Single<String> transformImage(Publisher<byte[]> dataStream) {
        return Single.fromCallable(() -> {
            Path tempInput = Files.createTempFile("upload-", ".tmp");
            Path tempOutput = Files.createTempFile("resized-", ".jpg");

            try (OutputStream os = Files.newOutputStream(tempInput, StandardOpenOption.WRITE)) {
                Flowable.fromPublisher(dataStream).blockingForEach(os::write);

                BufferedImage original = ImageIO.read(tempInput.toFile());
                if (original == null) throw new IOException("Cannot read image format");

                int targetWidth = 600;
                int newHeight = (int) ((double) original.getHeight() / original.getWidth() * targetWidth);
                BufferedImage resized = new BufferedImage(targetWidth, newHeight, BufferedImage.TYPE_INT_RGB);
                resized.createGraphics().drawImage(original, 0, 0, targetWidth, newHeight, null);

                ImageIO.write(resized, "jpg", tempOutput.toFile());
                
                System.out.println("Image resized to: " + tempOutput.toAbsolutePath());
                return "/processed-images/" + tempOutput.getFileName().toString();
            } finally {
                Files.deleteIfExists(tempInput);
                // In a real app, the output file would be moved to permanent storage, then deleted.
                // For this example, we assume it's handled elsewhere.
            }
        });
    }

    public Single<InputStream> streamPostsAsCsv() {
        return Single.fromCallable(() -> {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (PrintWriter writer = new PrintWriter(baos)) {
                writer.println("id,user_id,title,status");
                postRepo.findAll()
                    .blockingForEach(post -> writer.printf("%s,%s,\"%s\",%s%n",
                        post.id(), post.user_id(), post.title(), post.status()));
            }
            return new ByteArrayInputStream(baos.toByteArray());
        });
    }
}

// --- Controller ---
@Controller("/v2/files")
public class ReactiveFileController {

    private final ReactiveFileService fileService;

    public ReactiveFileController(ReactiveFileService fileService) {
        this.fileService = fileService;
    }

    @Post(value = "/users/import", consumes = MediaType.MULTIPART_FORM_DATA)
    public Single<HttpResponse<String>> importUsers(StreamingFileUpload file) {
        return fileService.handleCsvImport(file)
            .map(count -> HttpResponse.ok(count + " users imported reactively."))
            .onErrorReturn(err -> HttpResponse.serverError("Import failed: " + err.getMessage()));
    }

    @Post(value = "/images/process", consumes = MediaType.MULTIPART_FORM_DATA)
    public Single<HttpResponse<String>> processImage(StreamingFileUpload file) {
        return fileService.transformImage(file)
            .map(path -> HttpResponse.ok("Image processed to: " + path))
            .onErrorReturn(err -> HttpResponse.serverError("Image processing failed: " + err.getMessage()));
    }

    @Get(value = "/posts/export", produces = MediaType.TEXT_CSV)
    public Single<StreamedFile> exportPosts() {
        return fileService.streamPostsAsCsv()
            .map(inputStream -> new StreamedFile(inputStream, MediaType.TEXT_CSV_TYPE)
                .attach("reactive_posts_export.csv"));
    }
}