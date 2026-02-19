package com.example.variation4;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.validation.Validated;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

import javax.validation.Valid;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Validated
@Controller("/v4/combined")
public class CombinedController {

    // --- Enums defined within the controller class ---
    public enum Role { ADMIN, USER }
    public enum Status { DRAFT, PUBLISHED }

    // --- DTOs as public static inner classes for co-location ---

    @Introspected
    public static class UserDto {
        @NotBlank @Email public String email;
        @NotBlank public String password;
        @NotNull public Role role;
    }

    @Introspected
    @XmlRootElement(name = "post")
    @XmlAccessorType(XmlAccessType.FIELD)
    @ValidPost(message = "Published posts must have content.")
    public static class PostDto {
        @XmlElement @NotNull public UUID userId;
        @XmlElement @NotBlank public String title;
        @XmlElement public String content;
        @XmlElement @NotNull public Status status;
    }

    // --- Class-level validator and its implementation as inner classes ---

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Constraint(validatedBy = PostValidator.class)
    public @interface ValidPost {
        String message() default "Invalid post data";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static class PostValidator implements ConstraintValidator<ValidPost, PostDto> {
        @Override
        public boolean isValid(PostDto post, ConstraintValidatorContext context) {
            if (post == null) {
                return true;
            }
            // Business rule: A post that is being published cannot have empty content.
            if (post.status == Status.PUBLISHED && (post.content == null || post.content.isBlank())) {
                // You can point the violation to a specific field
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate("Content cannot be empty for published posts.")
                       .addPropertyNode("content")
                       .addConstraintViolation();
                return false;
            }
            return true;
        }
    }

    // --- Controller Endpoints ---

    @Post(uri = "/user", consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<Object> processUser(@Body @Valid UserDto userDto) {
        // Mock processing
        System.out.println("Processing user: " + userDto.email);
        var response = Map.of(
            "id", UUID.randomUUID(),
            "email", userDto.email,
            "role", userDto.role,
            "isActive", true,
            "createdAt", Timestamp.from(Instant.now())
        );
        return HttpResponse.created(response);
    }

    @Post(uri = "/post", consumes = MediaType.APPLICATION_XML, produces = MediaType.APPLICATION_XML)
    public HttpResponse<PostDto> processPost(@Body @Valid PostDto postDto) {
        // Mock processing, assuming user ID is valid
        System.out.println("Processing post titled: " + postDto.title);
        // Echo back the created post
        return HttpResponse.created(postDto);
    }
}