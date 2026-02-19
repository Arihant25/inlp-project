package com.example.fileops.strategy;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.imgscalr.Scalr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.CrudRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

// --- DOMAIN MODEL ---
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

class User {
    private UUID id; private String email; private String passwordHash; private UserRole role; private boolean isActive; private Timestamp createdAt;
    public User(String email, String passwordHash, UserRole role) {
        this.id = UUID.randomUUID(); this.email = email; this.passwordHash = passwordHash;
        this.role = role; this.isActive = true; this.createdAt = Timestamp.from(Instant.now());
    }
}
class Post {
    private UUID id; private UUID userId; private String title; private String content; private PostStatus status; private String imagePath;
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }
    public UUID getId() { return id; }
}

// --- REPOSITORIES ---
@Repository interface UserRepository extends CrudRepository<User, UUID> {}
@Repository interface PostRepository extends CrudRepository<Post, UUID> {}

// --- STRATEGY INTERFACES & IMPLEMENTATIONS ---

// Strategy for parsing different file types into User lists
interface UserFileParser {
    List<User> parse(InputStream inputStream) throws IOException;
    String getSupportedFileType();
}

@Component("csvUserParser")
class CsvUserParser implements UserFileParser {
    @Override
    public List<User> parse(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
             CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withTrim())) {
            return StreamSupport.stream(parser.spliterator(), false)
                .map(record -> new User(
                    record.get("email"),
                    "default_hashed_password",
                    UserRole.valueOf(record.get("role").toUpperCase())
                ))
                .collect(Collectors.toList());
        }
    }
    @Override public String getSupportedFileType() { return "text/csv"; }
}

@Component("excelUserParser")
class ExcelUserParser implements UserFileParser { // Stub implementation
    @Override
    public List<User> parse(InputStream inputStream) throws IOException {
        // Production code would use a library like Apache POI
        throw new UnsupportedOperationException("Excel parsing not yet implemented.");
    }
    @Override public String getSupportedFileType() { return "application/vnd.ms-excel"; }
}

// Strategy for different image processing tasks
interface ImageProcessor {
    Path process(InputStream inputStream, String name) throws IOException;
}

@Component("thumbnailProcessor")
class ThumbnailProcessor implements ImageProcessor {
    @Override
    public Path process(InputStream inputStream, String name) throws IOException {
        Path tempFile = Files.createTempFile("thumb-" + name, ".png");
        try {
            BufferedImage img = ImageIO.read(inputStream);
            BufferedImage thumbnail = Scalr.resize(img, Scalr.Method.ULTRA_QUALITY, 250);
            ImageIO.write(thumbnail, "png", tempFile.toFile());
            return tempFile;
        } catch (Exception e) {
            Files.deleteIfExists(tempFile);
            throw new IOException("Could not create thumbnail.", e);
        }
    }
}

// --- SERVICE (CONTEXT) LAYER ---
@Service
class FileProcessingService {
    private static final Logger logger = LoggerFactory.getLogger(FileProcessingService.class);

    private final Map<String, UserFileParser> parsers;
    private final ImageProcessor thumbnailProcessor;
    private final UserRepository userRepository;
    private final PostRepository postRepository;

    @Autowired
    public FileProcessingService(Map<String, UserFileParser> parsers, ImageProcessor thumbnailProcessor, UserRepository userRepository, PostRepository postRepository) {
        this.parsers = parsers;
        this.thumbnailProcessor = thumbnailProcessor;
        this.userRepository = userRepository;
        this.postRepository = postRepository;
    }

    public int importUsers(MultipartFile file) {
        String contentType = file.getContentType();
        UserFileParser parser = parsers.values().stream()
            .filter(p -> p.getSupportedFileType().equalsIgnoreCase(contentType))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "File type not supported: " + contentType));
        
        try {
            List<User> users = parser.parse(file.getInputStream());
            userRepository.saveAll(users);
            logger.info("Imported {} users using {}", users.size(), parser.getClass().getSimpleName());
            return users.size();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to parse file.", e);
        }
    }

    public void processAndSetPostImage(UUID postId, MultipartFile image) {
        Post post = postRepository.findById(postId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            Path imagePath = thumbnailProcessor.process(image.getInputStream(), postId.toString());
            // In a real app, move to permanent storage
            post.setImagePath(imagePath.toString());
            postRepository.save(post);
            logger.info("Processed image for post {} and stored at {}", postId, imagePath);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Image processing failed.", e);
        }
    }
    
    public StreamingResponseBody exportUsers() {
        return out -> {
            try (Writer writer = new OutputStreamWriter(out)) {
                writer.write("email,role\n");
                userRepository.findAll().forEach(user -> {
                    try {
                        writer.write(String.format("%s,%s\n", user.email, user.role));
                    } catch (IOException e) {
                        logger.error("Error writing user to stream", e);
                    }
                });
            }
        };
    }
}

// --- API (CONTROLLER) LAYER ---
@RestController
@RequestMapping("/api/strategy")
public class FileOperationsController {
    private final FileProcessingService fileProcessingService;

    public FileOperationsController(FileProcessingService fileProcessingService) {
        this.fileProcessingService = fileProcessingService;
    }

    @PostMapping("/users/import")
    public ResponseEntity<String> importUsers(@RequestParam("file") MultipartFile file) {
        int count = fileProcessingService.importUsers(file);
        return ResponseEntity.ok(count + " users imported successfully.");
    }

    @PostMapping("/posts/{id}/image")
    public ResponseEntity<String> uploadImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        fileProcessingService.processAndSetPostImage(id, file);
        return ResponseEntity.ok("Image processed for post " + id);
    }

    @GetMapping(value = "/users/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> downloadUsers() {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=user_export.csv")
            .body(fileProcessingService.exportUsers());
    }
}