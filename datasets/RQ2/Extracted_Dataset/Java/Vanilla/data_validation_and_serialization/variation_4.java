import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Variation 4: Builder & Fluent API Approach
// - Uses the Builder pattern for object construction.
// - Validation is performed inside the `build()` method of the builder.
// - Serialization is handled by dedicated, separate `Converter` classes.
// - Promotes immutability and a readable, fluent object creation syntax.

public class BuilderFluentDemo {

    // --- Domain Models with Inner Builders ---
    public enum UserRole { ADMIN, USER }
    public enum PostStatus { DRAFT, PUBLISHED }

    public static final class User {
        private final UUID id;
        private final String email;
        private final String password_hash;
        private final UserRole role;
        private final boolean is_active;
        private final Timestamp created_at;

        private User(Builder builder) {
            this.id = builder.id;
            this.email = builder.email;
            this.password_hash = builder.password_hash;
            this.role = builder.role;
            this.is_active = builder.is_active;
            this.created_at = builder.created_at;
        }

        // Getters only to promote immutability
        public UUID getId() { return id; }
        public String getEmail() { return email; }
        public String getPasswordHash() { return password_hash; }
        public UserRole getRole() { return role; }
        public boolean isActive() { return is_active; }
        public Timestamp getCreatedAt() { return created_at; }

        @Override
        public String toString() {
            return "User{id=" + id + ", email='" + email + "'}";
        }

        public static class Builder {
            private UUID id;
            private String email;
            private String password_hash;
            private UserRole role;
            private boolean is_active;
            private Timestamp created_at;
            private String phone; // Non-model field for validation demonstration

            private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}$");
            private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9. ()-]{7,25}$");

            public Builder id(UUID id) { this.id = id; return this; }
            public Builder email(String email) { this.email = email; return this; }
            public Builder password_hash(String hash) { this.password_hash = hash; return this; }
            public Builder role(UserRole role) { this.role = role; return this; }
            public Builder is_active(boolean active) { this.is_active = active; return this; }
            public Builder created_at(Timestamp ts) { this.created_at = ts; return this; }
            public Builder phone(String phone) { this.phone = phone; return this; } // Custom field

            public User build() {
                // Set defaults before validation
                if (id == null) id = UUID.randomUUID();
                if (role == null) role = UserRole.USER;
                if (created_at == null) created_at = Timestamp.from(Instant.now());

                // Perform validation
                List<String> errors = new ArrayList<>();
                if (email == null || !EMAIL_PATTERN.matcher(email).matches()) {
                    errors.add("A valid email is required.");
                }
                if (password_hash == null || password_hash.isBlank()) {
                    errors.add("Password hash cannot be empty.");
                }
                if (phone != null && !PHONE_PATTERN.matcher(phone).matches()) {
                    errors.add("Phone number is not in a valid format.");
                }
                if (!errors.isEmpty()) {
                    throw new IllegalStateException("User cannot be built: " + String.join("; ", errors));
                }
                return new User(this);
            }
        }
    }

    // --- Dedicated Converters ---
    public static class UserConverter {
        public String toJson(User user) {
            return "{" +
                "\"id\":\"" + user.getId() + "\"," +
                "\"email\":\"" + user.getEmail() + "\"," +
                "\"password_hash\":\"" + user.getPasswordHash() + "\"," +
                "\"role\":\"" + user.getRole() + "\"," +
                "\"is_active\":" + user.isActive() + "," +
                "\"created_at\":" + user.getCreatedAt().getTime() +
                "}";
        }

        public String toXml(User user) {
            try {
                Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
                Element root = doc.createElement("user");
                doc.appendChild(root);
                
                Map.of(
                    "id", user.getId().toString(), "email", user.getEmail(), "password_hash", user.getPasswordHash(),
                    "role", user.getRole().name(), "is_active", String.valueOf(user.isActive()),
                    "created_at", String.valueOf(user.getCreatedAt().getTime())
                ).forEach((key, val) -> {
                    Element el = doc.createElement(key);
                    el.setTextContent(val);
                    root.appendChild(el);
                });

                StringWriter sw = new StringWriter();
                TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(sw));
                return sw.toString().replaceAll("\\<\\?xml(.+?)\\?\\>", "").trim();
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        public User fromXml(String xml) {
            try {
                Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
                User.Builder builder = new User.Builder();
                builder.id(UUID.fromString(doc.getElementsByTagName("id").item(0).getTextContent()));
                builder.email(doc.getElementsByTagName("email").item(0).getTextContent());
                builder.password_hash(doc.getElementsByTagName("password_hash").item(0).getTextContent());
                builder.role(UserRole.valueOf(doc.getElementsByTagName("role").item(0).getTextContent()));
                builder.is_active(Boolean.parseBoolean(doc.getElementsByTagName("is_active").item(0).getTextContent()));
                builder.created_at(new Timestamp(Long.parseLong(doc.getElementsByTagName("created_at").item(0).getTextContent())));
                return builder.build();
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    public static void main(String[] args) {
        System.out.println("--- Variation 4: Builder & Fluent API Demo ---");

        // 1. Validation Demo via Builder
        System.out.println("\n1. Validation Demo:");
        try {
            new User.Builder()
                .email("not-a-valid-email")
                .password_hash("") // empty hash
                .phone("invalid-phone-number")
                .build();
        } catch (IllegalStateException e) {
            System.out.println("Caught expected build exception.");
            System.out.println("Error message: " + e.getMessage());
        }

        // 2. Successful object creation
        System.out.println("\n2. Successful Creation:");
        User user = null;
        try {
            user = new User.Builder()
                .email("builder.user@example.com")
                .password_hash("a_valid_and_strong_hash")
                .role(UserRole.ADMIN)
                .is_active(true)
                .build();
            System.out.println("Successfully built user: " + user);
        } catch (IllegalStateException e) {
            System.out.println("Caught unexpected build exception: " + e.getMessage());
        }

        if (user == null) return;

        // 3. Serialization/Deserialization with a dedicated converter
        UserConverter converter = new UserConverter();
        
        System.out.println("\n3. JSON Conversion:");
        String json = converter.toJson(user);
        System.out.println("Serialized to JSON: " + json);
        // Deserialization from JSON would require a manual parser or using the builder, omitted for brevity.

        System.out.println("\n4. XML Conversion:");
        String xml = converter.toXml(user);
        System.out.println("Serialized to XML: " + xml);
        User userFromXml = converter.fromXml(xml);
        System.out.println("Deserialized from XML: " + userFromXml);
    }
}