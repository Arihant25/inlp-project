import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Variation 3: Service-Oriented Approach
// - Business logic is encapsulated in service classes (e.g., UserService).
// - Validation is a private concern of the service, throwing a custom exception on failure.
// - Services expose methods like `createUser` that handle the whole lifecycle from raw data to a domain object.
// - Serialization is also handled by the service.

public class ServiceOrientedDemo {

    // --- Domain Models (simple data carriers) ---
    public enum UserRole { ADMIN, USER }
    public enum PostStatus { DRAFT, PUBLISHED }

    public static class User {
        UUID id; String email; String password_hash; UserRole role; boolean is_active; Timestamp created_at;
        @Override public String toString() { return "User[id=" + id + ", email=" + email + "]"; }
    }
    public static class Post {
        UUID id; UUID user_id; String title; String content; PostStatus status;
        @Override public String toString() { return "Post[id=" + id + ", title=" + title + "]"; }
    }

    // --- Custom Exception for Validation ---
    public static class ValidationException extends Exception {
        private final List<String> errorMessages;
        public ValidationException(List<String> errorMessages) {
            super("Validation failed: " + String.join(", ", errorMessages));
            this.errorMessages = errorMessages;
        }
        public List<String> getErrorMessages() { return errorMessages; }
    }

    // --- Service Layer ---
    public static class UserService {
        private static final Pattern EMAIL_REGEX = Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,6}$", Pattern.CASE_INSENSITIVE);
        private static final Pattern PHONE_REGEX = Pattern.compile("^\\+?[0-9. ()-]{7,25}$");

        public User createUser(Map<String, String> input) throws ValidationException {
            validate(input);

            User user = new User();
            user.id = UUID.randomUUID();
            user.email = input.get("email");
            user.password_hash = "hashed:" + input.get("password"); // Example hashing
            user.role = UserRole.valueOf(input.getOrDefault("role", "USER").toUpperCase());
            user.is_active = false; // Default to inactive, requires confirmation
            user.created_at = Timestamp.from(Instant.now());
            
            return user;
        }

        private void validate(Map<String, String> input) throws ValidationException {
            List<String> errors = new ArrayList<>();
            
            // Required field checks
            if (input.get("email") == null || input.get("email").isBlank()) {
                errors.add("Email is a required field.");
            } else if (!EMAIL_REGEX.matcher(input.get("email")).matches()) {
                errors.add("Invalid email format for '" + input.get("email") + "'.");
            }

            if (input.get("password") == null || input.get("password").length() < 8) {
                errors.add("Password must be at least 8 characters long.");
            }

            // Custom validator for a non-model field
            if (input.containsKey("phone") && !PHONE_REGEX.matcher(input.get("phone")).matches()) {
                errors.add("The provided phone number is not valid.");
            }

            // Type coercion check
            if (input.containsKey("role")) {
                try {
                    UserRole.valueOf(input.get("role").toUpperCase());
                } catch (IllegalArgumentException e) {
                    errors.add("Role '" + input.get("role") + "' does not exist.");
                }
            }

            if (!errors.isEmpty()) {
                throw new ValidationException(errors);
            }
        }

        public String userToJson(User user) {
            return String.format(
                "{\"id\":\"%s\",\"email\":\"%s\",\"role\":\"%s\",\"is_active\":%b,\"created_at\":%d}",
                user.id, user.email, user.role, user.is_active, user.created_at.getTime()
            );
        }

        public String userToXml(User user) {
            try {
                Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
                Element root = doc.createElement("user");
                doc.appendChild(root);
                
                Map.of(
                    "id", user.id.toString(),
                    "email", user.email,
                    "role", user.role.name(),
                    "is_active", String.valueOf(user.is_active),
                    "created_at", String.valueOf(user.created_at.getTime())
                ).forEach((key, val) -> {
                    Element el = doc.createElement(key);
                    el.setTextContent(val);
                    root.appendChild(el);
                });

                StringWriter sw = new StringWriter();
                TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(sw));
                return sw.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public User userFromXml(String xml) {
            try {
                Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
                User user = new User();
                user.id = UUID.fromString(doc.getElementsByTagName("id").item(0).getTextContent());
                user.email = doc.getElementsByTagName("email").item(0).getTextContent();
                user.role = UserRole.valueOf(doc.getElementsByTagName("role").item(0).getTextContent());
                user.is_active = Boolean.parseBoolean(doc.getElementsByTagName("is_active").item(0).getTextContent());
                user.created_at = new Timestamp(Long.parseLong(doc.getElementsByTagName("created_at").item(0).getTextContent()));
                return user;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("--- Variation 3: Service-Oriented Demo ---");
        UserService userService = new UserService();

        // 1. Validation Demo with custom exception
        System.out.println("\n1. Validation Demo:");
        Map<String, String> invalidInput = new HashMap<>();
        invalidInput.put("email", "invalid-email");
        invalidInput.put("password", "short");
        invalidInput.put("role", "SUPERMAN");
        try {
            userService.createUser(invalidInput);
        } catch (ValidationException e) {
            System.out.println("Caught expected validation exception.");
            System.out.println("Formatted Error Messages: " + e.getErrorMessages());
        }

        // 2. Successful user creation
        System.out.println("\n2. Successful Creation:");
        Map<String, String> validInput = new HashMap<>();
        validInput.put("email", "jane.doe@example.com");
        validInput.put("password", "a-very-long-and-secure-password");
        validInput.put("role", "ADMIN");
        try {
            User jane = userService.createUser(validInput);
            System.out.println("Successfully created user: " + jane);

            // 3. Serialization
            System.out.println("\n3. Serialization:");
            String json = userService.userToJson(jane);
            System.out.println("As JSON: " + json);
            String xml = userService.userToXml(jane);
            System.out.println("As XML: " + xml);

            // 4. Deserialization
            System.out.println("\n4. Deserialization:");
            User userFromXml = userService.userFromXml(xml);
            System.out.println("From XML: " + userFromXml);

        } catch (ValidationException e) {
            System.out.println("Caught unexpected validation exception: " + e.getMessage());
        }
    }
}