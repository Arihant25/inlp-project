package com.example.fileops.functional;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.imgscalr.Scalr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.CrudRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
    public UUID getId() { return id; }
}

// --- REPOSITORIES ---
@Repository
interface UserRepo extends CrudRepository<User, UUID> {}

@Repository
interface PostRepo extends CrudRepository<Post, UUID> {}

// --- UTILITY CLASSES ---
final class CsvParserUtil {
    private CsvParserUtil() {} // Prevent instantiation

    public static List<User> parseUsers(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
             CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withTrim())) {
            
            List<User> userList = new ArrayList<>();
            for (CSVRecord record : parser) {
                userList.add(new User(
                    record.get("email"),
                    "default_hashed_password",
                    UserRole.valueOf(record.get("role").toUpperCase())
                ));
            }
            return userList;
        }
    }
}

final class ImageProcessorUtil {
    private ImageProcessorUtil() {} // Prevent instantiation

    public static Path createThumbnail(InputStream is, String filePrefix) throws IOException {
        Path tempFile = Files.createTempFile(filePrefix, ".png");
        try {
            BufferedImage original = ImageIO.read(is);
            if (original == null) throw new IOException("Invalid image format");
            
            BufferedImage thumbnail = Scalr.resize(original, 150);
            ImageIO.write(thumbnail, "png", tempFile.toFile());
            return tempFile;
        } catch (Exception e) {
            Files.deleteIfExists(tempFile); // Cleanup on error
            throw new IOException("Failed to create thumbnail", e);
        }
    }
}

// --- SERVICE LAYER ---
@Service
class AssetManagementService {
    private static final Logger log = LoggerFactory.getLogger(AssetManagementService.class);

    @Autowired private UserRepo userRepo;
    @Autowired private PostRepo postRepo;

    public int bulkCreateUsers(MultipartFile csvFile) {
        try {
            List<User> users = CsvParserUtil.parseUsers(csvFile.getInputStream());
            Iterable<User> savedUsers = userRepo.saveAll(users);
            int count = (int) StreamSupport.stream(savedUsers.spliterator(), false).count();
            log.info("Persisted {} new users from CSV.", count);
            return count;
        } catch (IOException e) {
            log.error("Error processing user CSV upload.", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not parse CSV file.");
        }
    }

    public void updatePostImage(UUID postId, MultipartFile imageFile) {
        Post post = postRepo.findById(postId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found: " + postId));
        
        try (InputStream is = imageFile.getInputStream()) {
            Path tempImagePath = ImageProcessorUtil.createThumbnail(is, "post-" + post.getId().toString());
            log.info("Temporary thumbnail created at {}", tempImagePath);
            // In a real app, move this file to a permanent location (e.g., S3)
            post.setImagePath(tempImagePath.toAbsolutePath().toString());
            postRepo.save(post);
        } catch (IOException e) {
            log.error("Error processing image for post {}", postId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not process image file.");
        }
    }

    public StreamingResponseBody streamAllUsersAsCsv() {
        return outputStream -> {
            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
                writer.println("email,role,isActive"); // Header
                userRepo.findAll().forEach(user -> 
                    writer.printf("%s,%s,%b%n", user.email, user.role, user.isActive)
                );
                log.info("Finished streaming user data.");
            } catch (Exception e) {
                log.error("Streaming failed", e);
            }
        };
    }
}

// --- CONTROLLER LAYER ---
@RestController
@RequestMapping("/assets")
class FileUploadController {

    @Autowired
    private AssetManagementService assetSvc;

    @PostMapping("/users/upload")
    public ResponseEntity<String> handleUserUpload(@RequestParam("file") MultipartFile file) {
        int count = assetSvc.bulkCreateUsers(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(count + " users created.");
    }

    @PutMapping("/posts/{id}/image")
    public ResponseEntity<Void> handlePostImageUpload(@PathVariable("id") UUID postId, @RequestParam("image") MultipartFile image) {
        assetSvc.updatePostImage(postId, image);
        return ResponseEntity.accepted().build();
    }



    @GetMapping(value = "/users/download", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> downloadUsers() {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"users.csv\"")
            .body(assetSvc.streamAllUsersAsCsv());
    }
}