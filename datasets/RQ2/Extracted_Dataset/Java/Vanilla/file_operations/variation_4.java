package com.example.files.modern;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// --- Domain Model (shared across variations) ---
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

class User {
    final UUID id; final String email; final String passwordHash; final UserRole role; final boolean isActive; final Timestamp createdAt;
    public User(String email, UserRole role) {
        this.id = UUID.randomUUID(); this.email = email; this.passwordHash = "hash_" + email.hashCode();
        this.role = role; this.isActive = true; this.createdAt = Timestamp.from(Instant.now());
    }
    @Override public String toString() { return "User[email=" + email + ", role=" + role + "]"; }
}

class Post {
    final UUID id; final UUID userId; final String title; final String content; final PostStatus status;
    public Post(UUID userId, String title, String content) {
        this.id = UUID.randomUUID(); this.userId = userId; this.title = title;
        this.content = content; this.status = PostStatus.DRAFT;
    }
}

// --- Modern Java Service with Functional Influences ---

/**
 * Represents a part of a multipart request, managing its own temporary resources.
 */
interface MultipartPart extends AutoCloseable {
    Map<String, String> getHeaders();
    InputStream getInputStream() throws IOException;
    
    default Optional<String> getHeaderValue(String headerName, String key) {
        var header = getHeaders().get(headerName.toLowerCase());
        if (header == null) return Optional.empty();
        
        return Arrays.stream(header.split(";"))
            .map(String::trim)
            .filter(part -> part.startsWith(key + "="))
            .map(part -> part.substring(key.length() + 1).replace("\"", ""))
            .findFirst();
    }

    default Optional<String> getFieldName() {
        return getHeaderValue("content-disposition", "name");
    }

    default Optional<String> getFileName() {
        return getHeaderValue("content-disposition", "filename");
    }

    @Override
    void close() throws IOException;
}

/**
 * A service for handling file-related operations using modern Java features.
 */
class FileHandlerService {

    // --- 1. File Upload Handling ---
    public List<MultipartPart> parseRequest(InputStream requestStream, String boundary) throws IOException {
        var parts = new ArrayList<MultipartPart>();
        var boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        var reader = new BufferedInputStream(requestStream);

        // Skip preamble until the first boundary
        readUntilBoundary(reader, null, boundaryBytes);

        while (true) {
            var headers = parseHeaders(reader);
            if (headers.isEmpty()) break;

            var part = createPart(reader, headers, boundaryBytes);
            parts.add(part);
            
            // A bit of a hack to check for the final boundary marker
            if (part.toString().contains("FinalPart")) {
                break;
            }
        }
        return parts;
    }

    private Map<String, String> parseHeaders(InputStream is) throws IOException {
        var headers = new java.util.HashMap<String, String>();
        var lineReader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        
        // This is tricky without consuming the stream. We'll read byte-by-byte.
        var lineStream = new ByteArrayOutputStream();
        int b;
        while((b = is.read()) != -1) {
            if (b == '\n') {
                var line = lineStream.toString(StandardCharsets.UTF_8.name()).trim();
                lineStream.reset();
                if (line.isEmpty()) break; // End of headers
                
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    var name = line.substring(0, colonIndex).trim().toLowerCase();
                    var value = line.substring(colonIndex + 1).trim();
                    headers.put(name, value);
                }
            } else {
                lineStream.write(b);
            }
        }
        return headers;
    }

    private MultipartPart createPart(BufferedInputStream is, Map<String, String> headers, byte[] boundary) throws IOException {
        var tempFile = Files.createTempFile("modern-upload-", ".part");
        boolean isFinal;
        try (var os = Files.newOutputStream(tempFile)) {
            isFinal = readUntilBoundary(is, os, boundary);
        }
        
        final boolean finalPart = isFinal;
        return new MultipartPart() {
            @Override public Map<String, String> getHeaders() { return headers; }
            @Override public InputStream getInputStream() throws IOException { return Files.newInputStream(tempFile); }
            @Override public void close() throws IOException { Files.deleteIfExists(tempFile); }
            @Override public String toString() { return finalPart ? "FinalPart" : "Part"; }
        };
    }

    private boolean readUntilBoundary(BufferedInputStream is, OutputStream os, byte[] boundary) throws IOException {
        // A more robust KMP-style search would be ideal here. This is a simplified version.
        int[] buffer = new int[boundary.length];
        Arrays.fill(buffer, -1);
        int bufferPos = 0;
        
        int b;
        while ((b = is.read()) != -1) {
            if (os != null) {
                os.write(b);
            }
            
            System.arraycopy(buffer, 1, buffer, 0, buffer.length - 1);
            buffer[buffer.length - 1] = b;

            boolean match = true;
            for (int i = 0; i < boundary.length; i++) {
                if (buffer[i] != boundary[i]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                if (os != null) {
                    // We've written the boundary to the file, need to truncate it.
                    // This is complex; for this demo, we accept the small extra data.
                }
                // Check for final boundary: --boundary--
                is.mark(2);
                boolean isFinal = is.read() == '-' && is.read() == '-';
                is.reset();
                return isFinal;
            }
        }
        return true;
    }

    // --- 2. CSV Parsing ---
    public Stream<User> parseUsersFromCsv(InputStream csvStream) {
        var reader = new BufferedReader(new InputStreamReader(csvStream, StandardCharsets.UTF_8));
        return reader.lines()
            .skip(1) // Skip header
            .map(line -> line.split(","))
            .filter(fields -> fields.length >= 2)
            .map(fields -> new User(fields[0].trim(), UserRole.valueOf(fields[1].trim().toUpperCase())));
    }

    // --- 3. Image Processing ---
    public void resizeImage(InputStream source, OutputStream destination, int width, int height, String format) throws IOException {
        var originalImage = ImageIO.read(source);
        if (originalImage == null) throw new IOException("Unsupported image format");

        var resizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = resizedImage.createGraphics();
        g.drawImage(originalImage, 0, 0, width, height, null);
        g.dispose();
        
        ImageIO.write(resizedImage, format, destination);
    }

    // --- 4. File Download ---
    public void streamFile(Path sourcePath, OutputStream destination) throws IOException {
        try (var is = Files.newInputStream(sourcePath)) {
            is.transferTo(destination);
        }
    }
}

public class ModernFileOperations {
    public static void main(String[] args) {
        System.out.println("--- Modern Java Variation ---");
        var service = new FileHandlerService();

        // 1. Demonstrate Upload
        System.out.println("\n1. Handling File Upload...");
        var boundary = "WebAppBoundaryModern";
        var requestBody = createMockMultipartRequest(boundary);
        var requestStream = new ByteArrayInputStream(requestBody.getBytes(StandardCharsets.UTF_8));

        try (var partsStream = service.parseRequest(requestStream, boundary).stream()) {
            partsStream.forEach(part -> {
                try (part) {
                    System.out.println("Processing part: " + part.getFieldName().orElse("N/A"));
                    part.getFileName().ifPresent(fileName -> {
                        try {
                            if (fileName.endsWith(".csv")) {
                                System.out.println("  -> Parsing CSV...");
                                List<User> users = service.parseUsersFromCsv(part.getInputStream()).collect(Collectors.toList());
                                System.out.println("     Parsed " + users.size() + " users.");
                                users.forEach(u -> System.out.println("       - " + u));
                            } else if (fileName.endsWith(".jpg")) {
                                System.out.println("  -> Resizing image...");
                                var resizedOut = new ByteArrayOutputStream();
                                service.resizeImage(part.getInputStream(), 80, 80, "jpg", resizedOut);
                                System.out.println("     Resized image to " + resizedOut.size() + " bytes.");
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            System.err.println("Upload failed: " + e.getMessage());
        }

        // 2. Demonstrate Download
        System.out.println("\n2. Handling File Download...");
        Path tempDownloadFile = null;
        try {
            tempDownloadFile = Files.createTempFile("modern-dl-", ".txt");
            Files.writeString(tempDownloadFile, "Content to be streamed using modern I/O.");
            System.out.println("  -> Created temp file: " + tempDownloadFile);
            
            var downloadedContent = new ByteArrayOutputStream();
            service.streamFile(tempDownloadFile, downloadedContent);
            System.out.println("  -> Streamed content: " + downloadedContent.toString(StandardCharsets.UTF_8.name()));
        } catch (IOException e) {
            System.err.println("Download failed: " + e.getMessage());
        } finally {
            if (tempDownloadFile != null) {
                try { Files.deleteIfExists(tempDownloadFile); } catch (IOException e) { /* ignore */ }
            }
        }
    }

    private static String createMockMultipartRequest(String boundary) {
        return "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"post_id\"\r\n" +
                "\r\n" +
                "a8f5b1e2-c3d4-e5f6-a7b8-c9d0e1f2a3b4\r\n" +
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"user_data\"; filename=\"users.csv\"\r\n" +
                "Content-Type: text/csv\r\n" +
                "\r\n" +
                "email,role\n" +
                "admin@example.com,ADMIN\n" +
                "user1@example.com,USER\n" +
                "\r\n" +
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"profile_pic\"; filename=\"avatar.jpg\"\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "\r\n" +
                "mock-jpeg-data\r\n" +
                "--" + boundary + "--\r\n";
    }
}