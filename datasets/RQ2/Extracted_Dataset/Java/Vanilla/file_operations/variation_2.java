package com.example.files.utility;

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

// --- Utility Class with Static Methods ---
final class FileOpsUtils {

    private FileOpsUtils() {} // Prevent instantiation

    public static class MultipartPart {
        public Map<String, String> headers;
        public byte[] content;
        public Path tempFile;

        public String getFieldName() {
            String disp = headers.getOrDefault("content-disposition", "");
            for (String part : disp.split(";")) {
                if (part.trim().startsWith("name=")) return part.trim().substring(5).replace("\"", "");
            }
            return null;
        }

        public String getFileName() {
            String disp = headers.getOrDefault("content-disposition", "");
            for (String part : disp.split(";")) {
                if (part.trim().startsWith("filename=")) return part.trim().substring(9).replace("\"", "");
            }
            return null;
        }
    }

    public static List<MultipartPart> parseMultipartStream(InputStream is, String boundary) throws IOException {
        List<MultipartPart> parts = new ArrayList<>();
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        
        try (BufferedInputStream bis = new BufferedInputStream(is)) {
            // This is a simplified parser. A real-world one would be more robust.
            byte[] buffer = new byte[8192];
            int bytesRead;
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            while ((bytesRead = bis.read(buffer)) != -1) {
                data.write(buffer, 0, bytesRead);
            }
            byte[] allBytes = data.toByteArray();
            int start = 0;
            
            while (start < allBytes.length) {
                int boundaryIndex = indexOf(allBytes, boundaryBytes, start);
                if (boundaryIndex == -1) break;

                // Skip first boundary
                if (boundaryIndex == 0) {
                    start = boundaryIndex + boundaryBytes.length;
                    // Skip \r\n
                    if (start < allBytes.length - 1 && allBytes[start] == '\r' && allBytes[start+1] == '\n') {
                        start += 2;
                    }
                    continue;
                }

                int contentStart = -1;
                int headersEnd = -1;
                for (int i = start; i < boundaryIndex - 3; i++) {
                    if (allBytes[i] == '\r' && allBytes[i+1] == '\n' && allBytes[i+2] == '\r' && allBytes[i+3] == '\n') {
                        headersEnd = i;
                        contentStart = i + 4;
                        break;
                    }
                }

                if (contentStart != -1) {
                    MultipartPart part = new MultipartPart();
                    byte[] headerBytes = new byte[headersEnd - start];
                    System.arraycopy(allBytes, start, headerBytes, 0, headerBytes.length);
                    part.headers = parsePartHeaders(new String(headerBytes, StandardCharsets.UTF_8));

                    int contentLength = boundaryIndex - contentStart - 2; // -2 for \r\n before boundary
                    byte[] contentBytes = new byte[contentLength];
                    System.arraycopy(allBytes, contentStart, contentBytes, 0, contentLength);
                    
                    if (part.getFileName() != null) {
                        part.tempFile = createTempFile("upload_util_", ".dat");
                        Files.write(part.tempFile, contentBytes);
                        part.content = null;
                    } else {
                        part.content = contentBytes;
                    }
                    parts.add(part);
                }
                start = boundaryIndex + boundaryBytes.length;
            }
        }
        return parts;
    }

    private static int indexOf(byte[] outer, byte[] inner, int start) {
        for(int i = start; i < outer.length - inner.length + 1; ++i) {
            boolean found = true;
            for(int j = 0; j < inner.length; ++j) {
                if (outer[i+j] != inner[j]) {
                    found = false;
                    break;
                }
            }
            if (found) return i;
        }
        return -1;
    }

    private static Map<String, String> parsePartHeaders(String headerBlock) {
        Map<String, String> headers = new HashMap<>();
        String[] lines = headerBlock.split("\r\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon != -1) {
                headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    public static List<User> parseUsersFromCsv(InputStream csvStream) throws IOException {
        List<User> users = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(csvStream))) {
            r.readLine(); // skip header
            String line;
            while ((line = r.readLine()) != null) {
                String[] fields = line.split(",");
                if (fields.length >= 2) {
                    users.add(new User(fields[0].trim(), UserRole.valueOf(fields[1].trim().toUpperCase())));
                }
            }
        }
        return users;
    }

    public static Path resizeImage(InputStream imgStream, int w, int h, String format) throws IOException {
        BufferedImage original = ImageIO.read(imgStream);
        if (original == null) throw new IOException("Invalid image format");
        
        BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, w, h, null);
        g.dispose();
        
        Path tempFile = createTempFile("resized_util_", "." + format);
        ImageIO.write(resized, format, tempFile.toFile());
        return tempFile;
    }

    public static void streamFileToOutput(Path file, OutputStream os) throws IOException {
        Files.copy(file, os);
    }

    public static Path createTempFile(String prefix, String suffix) throws IOException {
        Path temp = Files.createTempFile(prefix, suffix);
        temp.toFile().deleteOnExit();
        return temp;
    }
    
    public static void cleanupParts(List<MultipartPart> parts) {
        if (parts == null) return;
        for (MultipartPart part : parts) {
            if (part.tempFile != null) {
                try { Files.deleteIfExists(part.tempFile); } catch (IOException e) { /* ignore */ }
            }
        }
    }
}

// --- Main Application Class ---
public class FileOperationsUtility {

    public static void main(String[] args) {
        System.out.println("--- Utility Class Variation ---");

        // 1. Demonstrate Upload
        System.out.println("\n1. Handling File Upload...");
        List<FileOpsUtils.MultipartPart> parts = null;
        try {
            String boundary = "WebAppBoundary";
            String reqBody = createMockMultipartRequest(boundary);
            InputStream reqStream = new ByteArrayInputStream(reqBody.getBytes(StandardCharsets.UTF_8));

            parts = FileOpsUtils.parseMultipartStream(reqStream, boundary);
            System.out.println("Parsed " + parts.size() + " parts.");

            for (FileOpsUtils.MultipartPart part : parts) {
                System.out.println("Processing part: " + part.getFieldName());
                if (part.getFileName() != null) {
                    if (part.getFileName().endsWith(".csv")) {
                        try (InputStream is = Files.newInputStream(part.tempFile)) {
                            List<User> users = FileOpsUtils.parseUsersFromCsv(is);
                            System.out.println("  -> Parsed " + users.size() + " users from CSV.");
                            users.forEach(System.out::println);
                        }
                    } else if (part.getFileName().endsWith(".jpg")) {
                        Path resized = null;
                        try (InputStream is = Files.newInputStream(part.tempFile)) {
                            resized = FileOpsUtils.resizeImage(is, 50, 50, "jpg");
                            System.out.println("  -> Resized image to temp file: " + resized);
                        } finally {
                            if (resized != null) Files.deleteIfExists(resized);
                        }
                    }
                } else {
                    System.out.println("  -> Form field value: " + new String(part.content, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            System.err.println("Upload failed: " + e.getMessage());
        } finally {
            FileOpsUtils.cleanupParts(parts);
        }

        // 2. Demonstrate Download
        System.out.println("\n2. Handling File Download...");
        Path tempDownloadFile = null;
        try {
            tempDownloadFile = FileOpsUtils.createTempFile("dl_util_", ".txt");
            Files.write(tempDownloadFile, "Streaming content via utility method.".getBytes());
            System.out.println("Created temp file for download: " + tempDownloadFile);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            FileOpsUtils.streamFileToOutput(tempDownloadFile, baos);
            System.out.println("Streamed content: " + baos.toString());
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