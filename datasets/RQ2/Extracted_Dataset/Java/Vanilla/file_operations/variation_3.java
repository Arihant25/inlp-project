package com.example.files.allinone;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
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

// --- Domain Model (shared across variations) ---
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

class User {
    UUID id; String email; String passwordHash; UserRole role; boolean isActive; Timestamp createdAt;
    public User(String email, UserRole role) {
        this.id = UUID.randomUUID(); this.email = email; this.passwordHash = "h" + email.hashCode();
        this.role = role; this.isActive = true; this.createdAt = Timestamp.from(Instant.now());
    }
    @Override public String toString() { return "User{email='" + email + "', role=" + role + "}"; }
}

class Post {
    UUID id; UUID userId; String title; String content; PostStatus status;
    public Post(UUID userId, String title, String content) {
        this.id = UUID.randomUUID(); this.userId = userId; this.title = title;
        this.content = content; this.status = PostStatus.DRAFT;
    }
}

// --- All-in-One Handler Class ---
public class FileUploadHandler {

    private final List<Path> tempFiles = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("--- All-in-One Handler Variation ---");
        FileUploadHandler handler = new FileUploadHandler();
        try {
            // 1. Simulate Upload
            System.out.println("\n1. Processing Upload Request...");
            String boundary = "MyBoundary123";
            String requestBody = handler.createMockMultipartRequest(boundary);
            InputStream requestStream = new ByteArrayInputStream(requestBody.getBytes(StandardCharsets.UTF_8));
            handler.processRequest(requestStream, boundary);

            // 2. Simulate Download
            System.out.println("\n2. Processing Download Request...");
            ByteArrayOutputStream responseStream = new ByteArrayOutputStream();
            handler.processDownload(responseStream);
            System.out.println("Download response content: " + responseStream.toString());

        } catch (Exception e) {
            System.err.println("An error occurred: " + e.getMessage());
            e.printStackTrace();
        } finally {
            handler.cleanupTempFiles();
        }
    }

    /**
     * Main method to process an incoming multipart request.
     */
    public void processRequest(InputStream requestStream, String boundary) throws IOException {
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        BufferedInputStream in = new BufferedInputStream(requestStream);

        // A simple, stateful, line-by-line parser.
        // In production, a more robust byte-search algorithm would be better.
        String line;
        while ((line = readLine(in)) != null && !line.equals("--" + boundary)) {
            // Preamble, ignore
        }

        boolean lastPart = false;
        while (!lastPart) {
            // Parse headers for the current part
            String contentDisposition = null;
            String contentType = null;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-disposition:")) {
                    contentDisposition = line;
                } else if (line.toLowerCase().startsWith("content-type:")) {
                    contentType = line;
                }
            }

            if (contentDisposition == null) break;

            // Extract field name and filename
            String fieldName = extractHeaderValue(contentDisposition, "name");
            String fileName = extractHeaderValue(contentDisposition, "filename");

            System.out.println("Found part: name='" + fieldName + "', filename='" + (fileName != null ? fileName : "N/A") + "'");

            // Read content until the next boundary
            ByteArrayOutputStream contentStream = new ByteArrayOutputStream();
            lastPart = readUntilBoundary(in, contentStream, boundaryBytes);
            
            // Process the content
            if (fileName != null) {
                // It's a file
                processFilePart(fileName, new ByteArrayInputStream(contentStream.toByteArray()));
            } else {
                // It's a form field
                System.out.println("  -> Form field value: " + contentStream.toString());
            }
        }
    }

    private void processFilePart(String fileName, InputStream contentStream) throws IOException {
        if (fileName.endsWith(".csv")) {
            System.out.println("  -> Parsing CSV file...");
            List<User> users = parseCsv(contentStream);
            System.out.println("  -> Parsed " + users.size() + " users.");
            users.forEach(System.out::println);
        } else if (fileName.endsWith(".jpg") || fileName.endsWith(".png")) {
            System.out.println("  -> Resizing image file...");
            Path resizedFile = resizeImage(contentStream, 120, 120, "png");
            System.out.println("  -> Image resized and saved to: " + resizedFile);
        } else {
            System.out.println("  -> Storing generic file...");
            Path tempFile = createTempFile("generic-", ".dat");
            try (OutputStream os = Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = contentStream.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
            }
            System.out.println("  -> Saved to: " + tempFile);
        }
    }

    private List<User> parseCsv(InputStream csvStream) throws IOException {
        List<User> users = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csvStream))) {
            reader.readLine(); // Skip header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    users.add(new User(parts[0].trim(), UserRole.valueOf(parts[1].trim().toUpperCase())));
                }
            }
        }
        return users;
    }

    private Path resizeImage(InputStream imageStream, int width, int height, String format) throws IOException {
        BufferedImage originalImage = ImageIO.read(imageStream);
        if (originalImage == null) throw new IOException("Cannot read image");

        Image scaledImage = originalImage.getScaledInstance(width, height, Image.SCALE_SMOOTH);
        BufferedImage outputImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        
        Graphics2D g2d = outputImage.createGraphics();
        g2d.drawImage(scaledImage, 0, 0, null);
        g2d.dispose();

        Path tempFile = createTempFile("resized-", "." + format);
        ImageIO.write(outputImage, format, tempFile.toFile());
        return tempFile;
    }

    public void processDownload(OutputStream responseStream) throws IOException {
        Path tempFile = null;
        try {
            tempFile = createTempFile("download-", ".txt");
            try (BufferedWriter writer = Files.newBufferedWriter(tempFile)) {
                writer.write("This is a sample file for download streaming.");
                writer.newLine();
            }
            System.out.println("  -> Streaming file: " + tempFile);
            Files.copy(tempFile, responseStream);
        } finally {
            // In a real app, the temp file might live longer, but here we clean it up.
            if (tempFile != null) {
                Files.deleteIfExists(tempFile);
                tempFiles.remove(tempFile);
            }
        }
    }

    // --- Helper Methods for Parsing and File Management ---

    private String readLine(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = is.read()) != -1) {
            if (b == '\n') break;
            buffer.write(b);
        }
        if (buffer.size() == 0 && b == -1) return null;
        byte[] bytes = buffer.toByteArray();
        return new String(bytes, 0, bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length);
    }

    private boolean readUntilBoundary(InputStream in, OutputStream out, byte[] boundary) throws IOException {
        // Simplified implementation
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        int prev = -1, curr;
        while ((curr = in.read()) != -1) {
            if (prev == '\r' && curr == '\n') {
                byte[] line = lineBuffer.toByteArray();
                if (new String(line).startsWith(new String(boundary))) {
                    return new String(line).endsWith("--");
                }
                out.write(line);
                out.write('\r');
                out.write('\n');
                lineBuffer.reset();
            } else if (prev != -1) {
                lineBuffer.write(prev);
            }
            prev = curr;
        }
        if (prev != -1) lineBuffer.write(prev);
        out.write(lineBuffer.toByteArray());
        return true;
    }

    private String extractHeaderValue(String header, String key) {
        if (header == null) return null;
        String keyPattern = key + "=\"";
        int start = header.indexOf(keyPattern);
        if (start == -1) return null;
        start += keyPattern.length();
        int end = header.indexOf("\"", start);
        if (end == -1) return null;
        return header.substring(start, end);
    }

    private Path createTempFile(String prefix, String suffix) throws IOException {
        Path tempFile = Files.createTempFile(prefix, suffix);
        this.tempFiles.add(tempFile);
        return tempFile;
    }

    private void cleanupTempFiles() {
        System.out.println("\nCleaning up " + tempFiles.size() + " temporary files...");
        for (Path path : tempFiles) {
            try {
                Files.deleteIfExists(path);
                System.out.println("  - Deleted: " + path);
            } catch (IOException e) {
                System.err.println("  - Failed to delete: " + path);
            }
        }
        tempFiles.clear();
    }

    private String createMockMultipartRequest(String boundary) {
        return "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"post_title\"\r\n" +
                "\r\n" +
                "My Awesome Post\r\n" +
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"user_list\"; filename=\"users.csv\"\r\n" +
                "Content-Type: text/csv\r\n" +
                "\r\n" +
                "email,role\n" +
                "test@example.com,USER\n" +
                "root@example.com,ADMIN\n" +
                "\r\n" +
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"header_image\"; filename=\"header.png\"\r\n" +
                "Content-Type: image/png\r\n" +
                "\r\n" +
                "fake-png-binary-data\r\n" +
                "--" + boundary + "--\r\n";
    }
}