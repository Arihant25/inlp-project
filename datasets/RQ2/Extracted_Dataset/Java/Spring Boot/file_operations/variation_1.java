package com.example.fileops.classic;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
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

// --- DOMAIN SCHEMA ---

enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

class User {
    private UUID id;
    private String email;
    private String passwordHash;
    private UserRole role;
    private boolean isActive;
    private Timestamp createdAt;
    // Constructors, Getters, Setters
    public User(String email, String passwordHash, UserRole role) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.isActive = true;
        this.createdAt = Timestamp.from(Instant.now());
    }
    public String getEmail() { return email; }
    public UserRole getRole() { return role; }
    public boolean isActive() { return isActive; }
}

class Post {
    private UUID id;
    private UUID userId;
    private String title;
    private String content;
    private PostStatus status;
    private String imageUrl; // Field to store the path to the processed image
    // Getters, Setters
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public UUID getId() { return id; }
}

// --- REPOSITORIES (Mocks for DI) ---

@Repository
interface UserRepository extends CrudRepository<User, UUID> {}

@Repository
interface PostRepository extends CrudRepository<Post, UUID> {}


// --- SERVICE LAYER ---

@Service
class ImageProcessingService {
    private static final Logger logger = LoggerFactory.getLogger(ImageProcessingService.class);
    private static final int THUMBNAIL_WIDTH = 200;

    public Path resizeAndStore(MultipartFile imageFile, String targetFileName) throws IOException {
        Path tempFile = null;
        try {
            // 1. Read image from multipart file
            BufferedImage originalImage = ImageIO.read(imageFile.getInputStream());
            if (originalImage == null) {
                throw new IOException("Could not read image file.");
            }

            // 2. Resize the image
            BufferedImage resizedImage = Scalr.resize(originalImage, Scalr.Method.QUALITY, Scalr.Mode.AUTOMATIC, THUMBNAIL_WIDTH, Scalr.Mode.AUTOMATIC.getExpectedHeight(THUMBNAIL_WIDTH), Scalr.OP_ANTIALIAS);

            // 3. Create a temporary file to write the resized image to
            tempFile = Files.createTempFile("thumbnail-", ".png");
            ImageIO.write(resizedImage, "png", tempFile.toFile());

            // In a real app, you would move this tempFile to permanent storage (e.g., S3, local filestore)
            // For this example, we'll just log the path and return it.
            logger.info("Resized image stored temporarily at: {}", tempFile.toAbsolutePath());
            return tempFile;

        } catch (IOException e) {
            logger.error("Error processing image", e);
            // 4. Clean up the temporary file on failure
            if (tempFile != null) {
                Files.deleteIfExists(tempFile);
            }
            throw e;
        }
    }
}

@Service
class FileOperationService {
    private static final Logger logger = LoggerFactory.getLogger(FileOperationService.class);

    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final ImageProcessingService imageProcessingService;

    @Autowired
    public FileOperationService(UserRepository userRepository, PostRepository postRepository, ImageProcessingService imageProcessingService) {
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.imageProcessingService = imageProcessingService;
    }

    public List<User> importUsersFromCsv(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV file is empty.");
        }
        List<User> users = new ArrayList<>();
        try (Reader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreHeaderCase().withTrim());
            for (CSVRecord csvRecord : csvParser) {
                User user = new User(
                    csvRecord.get("email"),
                    "default_hashed_password", // Password should be handled securely
                    UserRole.valueOf(csvRecord.get("role").toUpperCase())
                );
                users.add(user);
            }
            logger.info("Parsed {} users from CSV.", users.size());
            return StreamSupport.stream(userRepository.saveAll(users).spliterator(), false).collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Failed to parse CSV file", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to parse CSV file.", e);
        }
    }

    public void attachImageToPost(UUID postId, MultipartFile imageFile) {
        Post post = postRepository.findById(postId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

        try {
            Path imagePath = imageProcessingService.resizeAndStore(imageFile, post.getId().toString());
            // In a real app, move imagePath to permanent storage and save the URL/path.
            post.setImageUrl(imagePath.toString());
            postRepository.save(post);
            logger.info("Attached resized image {} to post {}", imagePath, postId);
            // The temporary file from imageProcessingService should be cleaned up after moving to permanent storage.
            // For this example, we assume it's handled by a separate cleanup job or the OS.
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not process image.", e);
        }
    }

    public StreamingResponseBody generateUserReport() {
        return outputStream -> {
            try (Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
                 CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("ID", "Email", "Role", "IsActive"))) {
                
                Iterable<User> users = userRepository.findAll(); // In real app, use pagination
                for (User user : users) {
                    csvPrinter.printRecord(user.getEmail(), user.getRole(), user.isActive());
                }
                logger.info("Streaming user report CSV.");
            } catch (IOException e) {
                logger.error("Error while streaming CSV report", e);
            }
        };
    }
}

// --- CONTROLLER LAYER ---

@RestController
@RequestMapping("/api/v1/files")
public class FileOperationsController {

    private final FileOperationService fileOperationService;

    @Autowired
    public FileOperationsController(FileOperationService fileOperationService) {
        this.fileOperationService = fileOperationService;
    }

    @PostMapping("/users/import-csv")
    public ResponseEntity<String> uploadUsersCsv(@RequestParam("file") MultipartFile file) {
        List<User> importedUsers = fileOperationService.importUsersFromCsv(file);
        return ResponseEntity.ok("Successfully imported " + importedUsers.size() + " users.");
    }

    @PostMapping("/posts/{postId}/image")
    public ResponseEntity<String> uploadPostImage(@PathVariable UUID postId, @RequestParam("image") MultipartFile image) {
        fileOperationService.attachImageToPost(postId, image);
        return ResponseEntity.ok("Image successfully uploaded and attached to post " + postId);
    }

    @GetMapping(value = "/users/export-csv", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> downloadUserReport() {
        StreamingResponseBody stream = fileOperationService.generateUserReport();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"user_report.csv\"")
            .body(stream);
    }
}