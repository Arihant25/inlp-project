package com.example.fileops.async;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.imgscalr.Scalr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.repository.CrudRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/*
    DEPENDENCY NOTE:
    This code requires the following dependencies:
    - org.apache.commons:commons-csv:1.9.0
    - org.imgscalr:imgscalr-lib:4.2
    - spring-boot-starter-web
    - spring-boot-starter-data-jpa (for repository annotations)
*/

// --- ASYNC CONFIGURATION ---
@Configuration
@EnableAsync
class AsyncConfiguration {
    @Bean(name = "fileProcessingExecutor")
    public Executor fileProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("FileProc-");
        executor.initialize();
        return executor;
    }
}

// --- DOMAIN ---
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

class User {
    UUID id; String email; String passwordHash; UserRole role; boolean isActive; Timestamp createdAt;
    public User(String email, String passwordHash, UserRole role) {
        this.id = UUID.randomUUID(); this.email = email; this.passwordHash = passwordHash;
        this.role = role; this.isActive = true; this.createdAt = Timestamp.from(Instant.now());
    }
}
class Post {
    UUID id; UUID userId; String title; String content; PostStatus status; String imagePath;
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }
}

// --- REPOSITORIES ---
@Repository interface UserRepository extends CrudRepository<User, UUID> {}
@Repository interface PostRepository extends CrudRepository<Post, UUID> {}

// --- EVENTS ---
class FileUploadedEvent {
    private final Path filePath;
    private final String originalFilename;
    private final String contentType;
    private final UUID relatedEntityId; // e.g., postId
    public FileUploadedEvent(Path filePath, String originalFilename, String contentType, UUID relatedEntityId) {
        this.filePath = filePath; this.originalFilename = originalFilename; this.contentType = contentType; this.relatedEntityId = relatedEntityId;
    }
    public Path getFilePath() { return filePath; }
    public String getContentType() { return contentType; }
    public UUID getRelatedEntityId() { return relatedEntityId; }
}

// --- SERVICES & PROCESSORS ---
@Service
class FileStorageService {
    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private final Path rootLocation = Path.of(System.getProperty("java.io.tmpdir"), "app-uploads");

    public FileStorageService() {
        try {
            Files.createDirectories(rootLocation);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize storage", e);
        }
    }

    public Path store(MultipartFile file) {
        try {
            Path destinationFile = this.rootLocation.resolve(UUID.randomUUID() + "-" + file.getOriginalFilename());
            Files.copy(file.getInputStream(), destinationFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("File stored temporarily at {}", destinationFile);
            return destinationFile;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store file", e);
        }
    }
}

@Component
class UserCsvProcessor {
    private static final Logger log = LoggerFactory.getLogger(UserCsvProcessor.class);
    @Autowired private UserRepository userRepository;

    public void process(Path csvPath) {
        log.info("Starting CSV processing for {}", csvPath);
        try (Reader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
             CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withTrim())) {
            
            List<User> users = StreamSupport.stream(parser.spliterator(), false)
                .map(rec -> new User(rec.get("email"), "default-hash", UserRole.valueOf(rec.get("role"))))
                .collect(Collectors.toList());
            
            userRepository.saveAll(users);
            log.info("Successfully processed and saved {} users from {}", users.size(), csvPath);
        } catch (Exception e) {
            log.error("Failed to process user CSV {}", csvPath, e);
        } finally {
            try { Files.deleteIfExists(csvPath); } catch (IOException e) { log.warn("Could not delete temp file {}", csvPath); }
        }
    }
}

@Component
class ImageThumbnailProcessor {
    private static final Logger log = LoggerFactory.getLogger(ImageThumbnailProcessor.class);
    @Autowired private PostRepository postRepository;

    public void process(Path imagePath, UUID postId) {
        log.info("Processing image {} for post {}", imagePath, postId);
        Post post = postRepository.findById(postId).orElse(null);
        if (post == null) {
            log.warn("Post {} not found for image processing. Aborting.", postId);
            return;
        }
        try {
            BufferedImage original = ImageIO.read(imagePath.toFile());
            BufferedImage thumbnail = Scalr.resize(original, 300);
            // In a real app, upload to S3/CDN and get a URL
            String finalPath = imagePath.getParent().resolve("thumb-" + imagePath.getFileName()).toString();
            ImageIO.write(thumbnail, "png", new File(finalPath));
            
            post.setImagePath(finalPath);
            postRepository.save(post);
            log.info("Thumbnail created for post {} at {}", postId, finalPath);
        } catch (Exception e) {
            log.error("Failed to process image for post {}", postId, e);
        } finally {
            try { Files.deleteIfExists(imagePath); } catch (IOException e) { log.warn("Could not delete temp image file {}", imagePath); }
        }
    }
}

// --- EVENT LISTENER ---
@Component
class FileProcessingListener {
    @Autowired private UserCsvProcessor userCsvProcessor;
    @Autowired private ImageThumbnailProcessor imageThumbnailProcessor;

    @Async("fileProcessingExecutor")
    @EventListener
    public void onFileUploaded(FileUploadedEvent event) {
        // Simulate processing time
        try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        if ("text/csv".equals(event.getContentType())) {
            userCsvProcessor.process(event.getFilePath());
        } else if (event.getContentType() != null && event.getContentType().startsWith("image/")) {
            imageThumbnailProcessor.process(event.getFilePath(), event.getRelatedEntityId());
        }
    }
}

// --- CONTROLLER ---
@RestController
@RequestMapping("/async/files")
class AsyncFileController {
    @Autowired private FileStorageService storageService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private UserRepository userRepository;

    @PostMapping("/users/import")
    public ResponseEntity<String> uploadUserCsv(@RequestParam("file") MultipartFile file) {
        Path storedPath = storageService.store(file);
        eventPublisher.publishEvent(new FileUploadedEvent(storedPath, file.getOriginalFilename(), file.getContentType(), null));
        return ResponseEntity.accepted().body("File upload accepted. User import will be processed in the background.");
    }

    @PostMapping("/posts/{id}/image")
    public ResponseEntity<String> uploadPostImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        Path storedPath = storageService.store(file);
        eventPublisher.publishEvent(new FileUploadedEvent(storedPath, file.getOriginalFilename(), file.getContentType(), id));
        return ResponseEntity.accepted().body("Image upload accepted. It will be processed and attached to the post shortly.");
    }
    
    @GetMapping(value = "/users/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> downloadUsers() {
        StreamingResponseBody stream = outputStream -> {
            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream))) {
                writer.println("email,role");
                userRepository.findAll().forEach(u -> writer.printf("%s,%s%n", u.email, u.role));
            }
        };
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"user_export.csv\"")
            .body(stream);
    }
}