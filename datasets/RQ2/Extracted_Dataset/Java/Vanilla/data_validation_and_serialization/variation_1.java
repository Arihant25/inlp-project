import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

// Variation 1: Classic OOP Approach
// - Separate classes for models, validators, and serializers.
// - Clear separation of concerns.
// - Validation returns a map of errors.
// - Verbose but explicit and easy to follow.

public class ClassicOopDemo {

    // --- Domain Models (POJOs) ---

    public enum UserRole { ADMIN, USER }
    public enum PostStatus { DRAFT, PUBLISHED }

    public static class User {
        private UUID id;
        private String email;
        private String passwordHash;
        private UserRole role;
        private boolean isActive;
        private Timestamp createdAt;

        // Getters and Setters
        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPasswordHash() { return passwordHash; }
        public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
        public UserRole getRole() { return role; }
        public void setRole(UserRole role) { this.role = role; }
        public boolean isActive() { return isActive; }
        public void setActive(boolean active) { isActive = active; }
        public Timestamp getCreatedAt() { return createdAt; }
        public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

        @Override
        public String toString() {
            return "User{id=" + id + ", email='" + email + "', role=" + role + ", isActive=" + isActive + "}";
        }
    }

    public static class Post {
        private UUID id;
        private UUID userId;
        private String title;
        private String content;
        private PostStatus status;

        // Getters and Setters
        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public UUID getUserId() { return userId; }
        public void setUserId(UUID userId) { this.userId = userId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public PostStatus getStatus() { return status; }
        public void setStatus(PostStatus status) { this.status = status; }

        @Override
        public String toString() {
            return "Post{id=" + id + ", userId=" + userId + ", title='" + title + "', status=" + status + "}";
        }
    }

    // --- Validation Logic ---

    public static class Validator {
        private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9_!#$%&'*+/=?`{|}~^.-]+@[a-zA-Z0-9.-]+$");
        // A simple phone pattern for demonstration
        private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9. ()-]{7,25}$");

        public static Map<String, List<String>> validateUser(Map<String, String> data) {
            Map<String, List<String>> errors = new HashMap<>();

            // Required fields
            if (data.get("email") == null || data.get("email").trim().isEmpty()) {
                addError(errors, "email", "Email is required.");
            } else if (!EMAIL_PATTERN.matcher(data.get("email")).matches()) {
                addError(errors, "email", "Email format is invalid.");
            }

            if (data.get("password_hash") == null || data.get("password_hash").isEmpty()) {
                addError(errors, "password_hash", "Password hash is required.");
            }

            // Custom validator for a non-model field (e.g., phone number from a registration form)
            if (data.containsKey("phone") && !PHONE_PATTERN.matcher(data.get("phone")).matches()) {
                addError(errors, "phone", "Phone number format is invalid.");
            }
            
            // Type conversion check
            try {
                if (data.get("role") != null) UserRole.valueOf(data.get("role").toUpperCase());
            } catch (IllegalArgumentException e) {
                addError(errors, "role", "Invalid role specified.");
            }

            return errors;
        }
        
        private static void addError(Map<String, List<String>> errors, String field, String message) {
            errors.computeIfAbsent(field, k -> new ArrayList<>()).add(message);
        }
    }

    // --- Serialization/Deserialization Logic ---

    public static class JsonDataHandler {
        public static String serializeUser(User user) {
            return "{\"id\":\"" + user.getId() + "\",\"email\":\"" + user.getEmail() + "\",\"password_hash\":\"" + user.getPasswordHash() + "\",\"role\":\"" + user.getRole() + "\",\"is_active\":" + user.isActive() + ",\"created_at\":" + user.getCreatedAt().getTime() + "}";
        }

        public static User deserializeUser(String json) {
            User user = new User();
            Map<String, String> map = parseSimpleJson(json);
            
            user.setId(UUID.fromString(map.get("id")));
            user.setEmail(map.get("email"));
            user.setPasswordHash(map.get("password_hash"));
            user.setRole(UserRole.valueOf(map.get("role")));
            user.setActive(Boolean.parseBoolean(map.get("is_active")));
            user.setCreatedAt(new Timestamp(Long.parseLong(map.get("created_at"))));
            
            return user;
        }

        private static Map<String, String> parseSimpleJson(String json) {
            Map<String, String> result = new HashMap<>();
            json = json.trim().substring(1, json.length() - 1); // Remove {}
            String[] pairs = json.split(",");
            for (String pair : pairs) {
                String[] keyValue = pair.split(":", 2);
                String key = keyValue[0].trim().replace("\"", "");
                String value = keyValue[1].trim();
                if (value.startsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(key, value);
            }
            return result;
        }
    }

    public static class XmlDataHandler {
        public static String serializeUser(User user) {
            try {
                DocumentBuilder docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
                Document doc = docBuilder.newDocument();
                Element rootElement = doc.createElement("user");
                doc.appendChild(rootElement);

                createElement(doc, rootElement, "id", user.getId().toString());
                createElement(doc, rootElement, "email", user.getEmail());
                createElement(doc, rootElement, "password_hash", user.getPasswordHash());
                createElement(doc, rootElement, "role", user.getRole().name());
                createElement(doc, rootElement, "is_active", String.valueOf(user.isActive()));
                createElement(doc, rootElement, "created_at", String.valueOf(user.getCreatedAt().getTime()));

                Transformer transformer = TransformerFactory.newInstance().newTransformer();
                transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                StringWriter writer = new StringWriter();
                transformer.transform(new DOMSource(doc), new StreamResult(writer));
                return writer.toString();
            } catch (Exception e) {
                throw new RuntimeException("XML serialization failed", e);
            }
        }

        public static User deserializeUser(String xml) {
            try {
                DocumentBuilder docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
                Document doc = docBuilder.parse(new InputSource(new StringReader(xml)));
                doc.getDocumentElement().normalize();
                
                User user = new User();
                user.setId(UUID.fromString(getTagValue("id", doc.getDocumentElement())));
                user.setEmail(getTagValue("email", doc.getDocumentElement()));
                user.setPasswordHash(getTagValue("password_hash", doc.getDocumentElement()));
                user.setRole(UserRole.valueOf(getTagValue("role", doc.getDocumentElement())));
                user.setActive(Boolean.parseBoolean(getTagValue("is_active", doc.getDocumentElement())));
                user.setCreatedAt(new Timestamp(Long.parseLong(getTagValue("created_at", doc.getDocumentElement()))));
                
                return user;
            } catch (Exception e) {
                throw new RuntimeException("XML deserialization failed", e);
            }
        }

        private static void createElement(Document doc, Element parent, String name, String value) {
            Element element = doc.createElement(name);
            element.appendChild(doc.createTextNode(value));
            parent.appendChild(element);
        }

        private static String getTagValue(String tag, Element element) {
            NodeList nodeList = element.getElementsByTagName(tag).item(0).getChildNodes();
            return nodeList.item(0).getNodeValue();
        }
    }

    // --- Main Application ---

    public static void main(String[] args) {
        System.out.println("--- Variation 1: Classic OOP Demo ---");

        // 1. Input Validation
        System.out.println("\n1. Validation Demo:");
        Map<String, String> invalidUserData = new HashMap<>();
        invalidUserData.put("email", "not-an-email");
        invalidUserData.put("phone", "123"); // Custom validator
        
        Map<String, List<String>> errors = Validator.validateUser(invalidUserData);
        System.out.println("Validation errors for invalid data: " + errors);

        Map<String, String> validUserData = new HashMap<>();
        validUserData.put("email", "test@example.com");
        validUserData.put("password_hash", "some_hash");
        validUserData.put("role", "ADMIN");
        errors = Validator.validateUser(validUserData);
        System.out.println("Validation errors for valid data: " + errors);

        // 2. Create a User object
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("prod.user@example.com");
        user.setPasswordHash("a_very_secure_hash_string");
        user.setRole(UserRole.ADMIN);
        user.setActive(true);
        user.setCreatedAt(Timestamp.from(Instant.now()));

        // 3. JSON Serialization & Deserialization
        System.out.println("\n2. JSON Serialization/Deserialization:");
        String json = JsonDataHandler.serializeUser(user);
        System.out.println("Serialized JSON: " + json);
        User userFromJson = JsonDataHandler.deserializeUser(json);
        System.out.println("Deserialized User from JSON: " + userFromJson);

        // 4. XML Serialization & Deserialization
        System.out.println("\n3. XML Serialization/Deserialization:");
        String xml = XmlDataHandler.serializeUser(user);
        System.out.println("Serialized XML: " + xml);
        User userFromXml = XmlDataHandler.deserializeUser(xml);
        System.out.println("Deserialized User from XML: " + userFromXml);
    }
}