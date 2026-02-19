package com.example.files.oop;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.stream.Collectors;

// --- Domain Model ---
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

class User {
    UUID id;
    String email;
    String passwordHash;
    UserRole role;
    boolean isActive;
    Timestamp createdAt;

    public User(String email, UserRole role) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = "mock_hash_" + email.hashCode();
        this.role = role;
        this.isActive = true;
        this.createdAt = Timestamp.from(Instant.now());
    }

    @Override
    public String toString() {
        return "User{id=" + id + ", email='" + email + "', role=" + role + "}";
    }
}

class Post {
    UUID id;
    UUID userId;
    String title;
    String content;
    PostStatus status;

    public Post(UUID userId, String title, String content) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.title = title;
        this.content = content;
        this.status = PostStatus.DRAFT;
    }
}

// --- Service Layer: Separation of Concerns ---

/**
 * Represents a single part of a multipart/form-data request.
 */
class ParsedPart {
    private final Map<String, String> headers;
    private final Path tempFilePath;
    private final String stringContent;
    private final boolean isFile;

    public ParsedPart(Map<String, String> headers, Path tempFilePath) {
        this.headers = headers;
        this.tempFilePath = tempFilePath;
        this.stringContent = null;
        this.isFile = true;
    }

    public ParsedPart(Map<String, String> headers, String stringContent) {
        this.headers = headers;
        this.tempFilePath = null;
        this.stringContent = stringContent;
        this.isFile = false;
    }

    public boolean isFile() {
        return isFile;
    }

    public String getFieldName() {
        String disposition = headers.get("content-disposition");
        if (disposition != null) {
            for (String part : disposition.split(";")) {
                if (part.trim().startsWith("name=")) {
                    return part.trim().substring(5).replace("\"", "");
                }
            }
        }
        return null;
    }

    public String getFileName() {
        if (!isFile) return null;
        String disposition = headers.get("content-disposition");
        if (disposition != null) {
            for (String part : disposition.split(";")) {
                if (part.trim().startsWith("filename=")) {
                    return part.trim().substring(9).replace("\"", "");
                }
            }
        }
        return null;
    }

    public InputStream getInputStream() throws IOException {
        if (!isFile) {
            return new ByteArrayInputStream(stringContent.getBytes(StandardCharsets.UTF_8));
        }
        return Files.newInputStream(tempFilePath);
    }

    public void cleanup() throws IOException {
        if (isFile && tempFilePath != null) {
            Files.deleteIfExists(tempFilePath);
        }
    }
}

/**
 * Manually parses a multipart/form-data input stream.
 */
class MultipartRequestParser {
    private final InputStream inputStream;
    private final String boundary;

    public MultipartRequestParser(InputStream inputStream, String boundary) {
        this.inputStream = new BufferedInputStream(inputStream);
        this.boundary = "--" + boundary;
    }

    public List<ParsedPart> parse() throws IOException {
        List<ParsedPart> parts = new ArrayList<>();
        byte[] boundaryBytes = (this.boundary).getBytes(StandardCharsets.UTF_8);
        
        try {
            // Skip preamble
            readLine(inputStream);

            boolean lastPart = false;
            while (!lastPart) {
                Map<String, String> headers = parseHeaders();
                if (headers.isEmpty()) break;

                String disposition = headers.get("content-disposition");
                boolean isFile = disposition != null && disposition.contains("filename=");

                ParsedPart part;
                if (isFile) {
                    Path tempFile = TempFileManager.createTempFile("upload", ".part");
                    lastPart = readPartContentToFile(tempFile, boundaryBytes);
                    part = new ParsedPart(headers, tempFile);
                } else {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    lastPart = readPartContentToMemory(baos, boundaryBytes);
                    part = new ParsedPart(headers, baos.toString(StandardCharsets.UTF_8.name()));
                }
                parts.add(part);
            }
        } catch (IOException e) {
            // Cleanup any created parts
            for (ParsedPart part : parts) {
                try {
                    part.cleanup();
                } catch (IOException cleanupEx) {
                    // Log cleanup exception
                }
            }
            throw e;
        }
        return parts;
    }

    private Map<String, String> parseHeaders() throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(inputStream)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon != -1) {
                String headerName = line.substring(0, colon).trim().toLowerCase();
                String headerValue = line.substring(colon + 1).trim();
                headers.put(headerName, headerValue);
            }
        }
        return headers;
    }

    private boolean readPartContentToFile(Path tempFile, byte[] boundaryBytes) throws IOException {
        try (OutputStream os = Files.newOutputStream(tempFile)) {
            return writeUntilBoundary(os, boundaryBytes);
        }
    }

    private boolean readPartContentToMemory(ByteArrayOutputStream baos, byte[] boundaryBytes) throws IOException {
        return writeUntilBoundary(baos, boundaryBytes);
    }

    private boolean writeUntilBoundary(OutputStream os, byte[] boundaryBytes) throws IOException {
        // This is a simplified boundary search for demonstration.
        // A robust implementation would use a more advanced algorithm like KMP.
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        int prevByte = -1;
        int currentByte;
        while ((currentByte = inputStream.read()) != -1) {
            if (prevByte == '\r' && currentByte == '\n') {
                byte[] lineBytes = lineBuffer.toByteArray();
                if (startsWith(lineBytes, boundaryBytes)) {
                    os.write(lineBytes, 0, lineBytes.length - boundaryBytes.length - 2); // Exclude boundary and trailing \r\n
                    return new String(lineBytes).endsWith("--\r\n");
                } else {
                    os.write(lineBuffer.toByteArray());
                    os.write('\r');
                    os.write('\n');
                }
                lineBuffer.reset();
            } else if (prevByte != -1) {
                lineBuffer.write(prevByte);
            }
            prevByte = currentByte;
        }
        if (prevByte != -1) lineBuffer.write(prevByte);
        os.write(lineBuffer.toByteArray());
        return true;
    }

    private boolean startsWith(byte[] array, byte[] prefix) {
        if (array.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) return false;
        }
        return true;
    }
    
    private String readLine(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = is.read()) != -1) {
            if (b == '\n') {
                break;
            }
            baos.write(b);
        }
        if (baos.size() == 0 && b == -1) return null;
        // Trim trailing \r
        byte[] bytes = baos.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') {
            len--;
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }
}

/**
 * Handles CSV data processing.
 */
class CsvUserParser {
    public List<User> parseUsers(InputStream csvInputStream) throws IOException {
        List<User> users = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csvInputStream, StandardCharsets.UTF_8))) {
            // Skip header line
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(",");
                if (fields.length >= 2) {
                    String email = fields[0].trim();
                    UserRole role = UserRole.valueOf(fields[1].trim().toUpperCase());
                    users.add(new User(email, role));
                }
            }
        }
        return users;
    }
}

/**
 * Handles image processing tasks.
 */
class ImageProcessor {
    public BufferedImage resizeImage(InputStream imageStream, int width, int height) throws IOException {
        BufferedImage originalImage = ImageIO.read(imageStream);
        if (originalImage == null) {
            throw new IOException("Could not read image from stream.");
        }
        Image resultingImage = originalImage.getScaledInstance(width, height, Image.SCALE_SMOOTH);
        BufferedImage outputImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = outputImage.createGraphics();
        g2d.drawImage(resultingImage, 0, 0, null);
        g2d.dispose();
        return outputImage;
    }
}

/**
 * Manages streaming file downloads.
 */
class FileDownloadService {
    public void streamFile(Path filePath, OutputStream outputStream) throws IOException {
        try (InputStream inputStream = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }
    }
}

/**
 * Utility for managing temporary files.
 */
class TempFileManager {
    public static Path createTempFile(String prefix, String suffix) throws IOException {
        Path tempFile = Files.createTempFile(prefix, suffix);
        tempFile.toFile().deleteOnExit(); // Basic cleanup strategy
        return tempFile;
    }
}

// --- Main Application Class ---
public class FileOperationsOop {

    public static void main(String[] args) {
        System.out.println("--- OOP Variation ---");
        FileOperationsOop app = new FileOperationsOop();
        app.runDemonstration();
    }

    public void runDemonstration() {
        // 1. Simulate a file upload request
        System.out.println("\n1. Handling File Upload...");
        handleUpload();

        // 2. Simulate a file download request
        System.out.println("\n2. Handling File Download...");
        handleDownload();
    }

    private void handleUpload() {
        String boundary = "WebAppBoundary";
        String requestBody = createMockMultipartRequest(boundary);
        InputStream requestStream = new ByteArrayInputStream(requestBody.getBytes(StandardCharsets.UTF_8));

        List<ParsedPart> parts = null;
        try {
            MultipartRequestParser parser = new MultipartRequestParser(requestStream, boundary);
            parts = parser.parse();

            System.out.println("Successfully parsed " + parts.size() + " parts.");

            for (ParsedPart part : parts) {
                System.out.println("Processing part: " + part.getFieldName());
                if (part.isFile()) {
                    processUploadedFile(part);
                } else {
                    try (InputStream is = part.getInputStream()) {
                        String value = new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining("\n"));
                        System.out.println("  - Form field '" + part.getFieldName() + "' with value: '" + value + "'");
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error during upload processing: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (parts != null) {
                parts.forEach(p -> {
                    try { p.cleanup(); } catch (IOException e) { /* log */ }
                });
            }
        }
    }

    private void processUploadedFile(ParsedPart filePart) throws IOException {
        System.out.println("  - File detected. Name: '" + filePart.getFileName() + "'");
        String fileName = filePart.getFileName();
        if (fileName.endsWith(".csv")) {
            System.out.println("    -> Parsing as CSV...");
            CsvUserParser csvParser = new CsvUserParser();
            try (InputStream is = filePart.getInputStream()) {
                List<User> users = csvParser.parseUsers(is);
                System.out.println("    -> Successfully parsed " + users.size() + " users from CSV.");
                users.forEach(u -> System.out.println("      - " + u));
            }
        } else if (fileName.endsWith(".jpg")) {
            System.out.println("    -> Processing as Image...");
            ImageProcessor imageProcessor = new ImageProcessor();
            Path resizedImagePath = null;
            try (InputStream is = filePart.getInputStream()) {
                BufferedImage resizedImage = imageProcessor.resizeImage(is, 100, 100);
                resizedImagePath = TempFileManager.createTempFile("resized-", ".jpg");
                ImageIO.write(resizedImage, "jpg", resizedImagePath.toFile());
                System.out.println("    -> Image resized and saved to temporary file: " + resizedImagePath);
            } finally {
                if (resizedImagePath != null) {
                    Files.deleteIfExists(resizedImagePath);
                }
            }
        }
    }

    private void handleDownload() {
        Path tempFileToDownload = null;
        try {
            // Create a dummy file to download
            tempFileToDownload = TempFileManager.createTempFile("download-", ".txt");
            Files.write(tempFileToDownload, "This is the content of the downloaded file.".getBytes(StandardCharsets.UTF_8));
            System.out.println("Created temporary file to download: " + tempFileToDownload);

            // Simulate streaming to a client (here, a ByteArrayOutputStream)
            ByteArrayOutputStream responseStream = new ByteArrayOutputStream();
            FileDownloadService downloadService = new FileDownloadService();
            downloadService.streamFile(tempFileToDownload, responseStream);

            System.out.println("File streamed successfully. Content received by 'client':");
            System.out.println(responseStream.toString(StandardCharsets.UTF_8.name()));

        } catch (IOException e) {
            System.err.println("Error during download processing: " + e.getMessage());
        } finally {
            if (tempFileToDownload != null) {
                try {
                    Files.deleteIfExists(tempFileToDownload);
                } catch (IOException e) { /* log */ }
            }
        }
    }

    private String createMockMultipartRequest(String boundary) {
        // NOTE: The line endings (\r\n) are critical for multipart parsing.
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
                "some-mock-binary-jpeg-data\r\n" +
                "--" + boundary + "--\r\n";
    }
}