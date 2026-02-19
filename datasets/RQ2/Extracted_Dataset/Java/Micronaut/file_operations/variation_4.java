package com.example.files.v4;

import io.micronaut.context.annotation.Factory;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.server.types.files.StreamedFile;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

// --- Domain Model ---
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

record User(UUID id, String email, String password_hash, UserRole role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}

// --- Mock Services for Handlers ---
@Singleton
class UserService {
    private final Map<UUID, User> userDb = new ConcurrentHashMap<>();
    public int saveBatch(List<User> users) {
        users.forEach(u -> userDb.put(u.id(), u));
        System.out.printf("UserService: Saved %d users.%n", users.size());
        return users.size();
    }
}

@Singleton
class ImageProcessingService {
    public Path resize(InputStream inputStream, int width) throws IOException {
        Path tempFile = Files.createTempFile("processed-", ".png");
        BufferedImage original = ImageIO.read(inputStream);
        int height = (int) (((double) original.getHeight() / original.getWidth()) * width);
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        resized.createGraphics().drawImage(original, 0, 0, width, height, null);
        ImageIO.write(resized, "png", tempFile.toFile());
        return tempFile;
    }
}

@Singleton
class ReportService {
    private final Map<UUID, Post> postDb = new ConcurrentHashMap<>();
    public ReportService() {
        UUID userId = UUID.randomUUID();
        postDb.put(UUID.randomUUID(), new Post(UUID.randomUUID(), userId, "CQRS Post 1", "Content", PostStatus.PUBLISHED));
        postDb.put(UUID.randomUUID(), new Post(UUID.randomUUID(), userId, "CQRS Post 2", "Content", PostStatus.DRAFT));
    }
    public InputStream generatePostsReport() {
        String csv = postDb.values().stream()
            .map(p -> String.join(",", p.id().toString(), p.title(), p.status().toString()))
            .collect(Collectors.joining("\n", "id,title,status\n", ""));
        return new ByteArrayInputStream(csv.getBytes());
    }
}

// --- Command/Handler Pattern ---
interface Command<R> {}
interface CommandHandler<C extends Command<R>, R> {
    R handle(C command);
    Class<C> getCommandType();
}

// --- Commands and Handlers ---
record UploadUsersCommand(CompletedFileUpload file) implements Command<Integer> {}
record ResizePostImageCommand(UUID postId, CompletedFileUpload file) implements Command<String> {}
record GeneratePostsReportQuery() implements Command<StreamedFile> {} // Using Command for queries too for simplicity

@Singleton
class UploadUsersCommandHandler implements CommandHandler<UploadUsersCommand, Integer> {
    @Inject private UserService userService;

    @Override
    public Integer handle(UploadUsersCommand command) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(command.file().getInputStream()))) {
            List<User> users = reader.lines().skip(1)
                .map(line -> line.split(","))
                .map(f -> new User(UUID.randomUUID(), f[0], "hash", UserRole.valueOf(f[1]), Boolean.parseBoolean(f[2]), Timestamp.from(Instant.now())))
                .collect(Collectors.toList());
            return userService.saveBatch(users);
        } catch (IOException e) {
            throw new RuntimeException("Failed to handle user upload", e);
        }
    }
    @Override public Class<UploadUsersCommand> getCommandType() { return UploadUsersCommand.class; }
}

@Singleton
class ResizePostImageCommandHandler implements CommandHandler<ResizePostImageCommand, String> {
    @Inject private ImageProcessingService imageService;

    @Override
    public String handle(ResizePostImageCommand command) {
        Path tempFile = null;
        try {
            tempFile = imageService.resize(command.file().getInputStream(), 500);
            // Simulate storing and returning a URL
            String url = "/uploads/posts/" + command.postId() + "/" + tempFile.getFileName();
            System.out.println("Image available at: " + url);
            return url;
        } catch (IOException e) {
            throw new RuntimeException("Image resizing failed", e);
        } finally {
            if (tempFile != null) try { Files.delete(tempFile); } catch (IOException ignored) {}
        }
    }
    @Override public Class<ResizePostImageCommand> getCommandType() { return ResizePostImageCommand.class; }
}

@Singleton
class GeneratePostsReportQueryHandler implements CommandHandler<GeneratePostsReportQuery, StreamedFile> {
    @Inject private ReportService reportService;

    @Override
    public StreamedFile handle(GeneratePostsReportQuery command) {
        return new StreamedFile(reportService.generatePostsReport(), MediaType.TEXT_CSV_TYPE)
            .attach("posts_cqrs_report.csv");
    }
    @Override public Class<GeneratePostsReportQuery> getCommandType() { return GeneratePostsReportQuery.class; }
}

@Factory
class CommandHandlerFactory {
    @Singleton
    public Map<Class<? extends Command>, CommandHandler> commandHandlers(List<CommandHandler> handlers) {
        return handlers.stream().collect(Collectors.toMap(CommandHandler::getCommandType, Function.identity()));
    }
}

// --- Controller ---
@Controller("/v4/files")
public class FileCommandController {

    private final Map<Class<? extends Command>, CommandHandler> commandHandlers;

    @Inject
    public FileCommandController(Map<Class<? extends Command>, CommandHandler> commandHandlers) {
        this.commandHandlers = commandHandlers;
    }

    @SuppressWarnings("unchecked")
    private <R, C extends Command<R>> R dispatch(C command) {
        CommandHandler<C, R> handler = (CommandHandler<C, R>) commandHandlers.get(command.getClass());
        if (handler == null) {
            throw new IllegalArgumentException("No handler found for command: " + command.getClass().getSimpleName());
        }
        return handler.handle(command);
    }

    @Post(value = "/users/import", consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<String> importUsers(CompletedFileUpload file) {
        try {
            int count = dispatch(new UploadUsersCommand(file));
            return HttpResponse.ok(count + " users imported via command handler.");
        } catch (Exception e) {
            return HttpResponse.serverError(e.getMessage());
        }
    }

    @Post(value = "/posts/{postId}/image", consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<String> uploadPostImage(UUID postId, CompletedFileUpload file) {
        try {
            String url = dispatch(new ResizePostImageCommand(postId, file));
            return HttpResponse.ok("Image processed by handler. URL: " + url);
        } catch (Exception e) {
            return HttpResponse.serverError(e.getMessage());
        }
    }

    @Get(value = "/posts/report", produces = MediaType.TEXT_CSV)
    public StreamedFile downloadPostsReport() {
        return dispatch(new GeneratePostsReportQuery());
    }
}