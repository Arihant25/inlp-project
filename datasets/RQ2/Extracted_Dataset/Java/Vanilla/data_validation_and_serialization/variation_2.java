import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Variation 2: Functional / Utility-First Approach
// - Uses immutable records for domain models.
// - Validation logic is composed of Predicates.
// - A single utility class `DataProcessor` handles all operations.
// - Emphasizes static methods and functional programming concepts.

public class FunctionalUtilityDemo {

    // --- Domain Models (Records) ---
    enum UserRole { ADMIN, USER }
    enum PostStatus { DRAFT, PUBLISHED }

    record User(UUID id, String email, String password_hash, UserRole role, boolean is_active, Timestamp created_at) {}
    record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}

    // --- Validation Result Wrapper ---
    record ValidationResult(boolean isValid, Map<String, List<String>> errors) {
        static ValidationResult success() {
            return new ValidationResult(true, Collections.emptyMap());
        }
        static ValidationResult failure(Map<String, List<String>> errors) {
            return new ValidationResult(false, errors);
        }
    }

    // --- Central Data Processing Utility ---
    static class DataProcessor {

        // --- Validation Rules ---
        private static final Predicate<String> isPresent = s -> s != null && !s.trim().isEmpty();
        private static final Predicate<String> isValidEmail = Pattern.compile("^[a-zA-Z0-9_!#$%&'*+/=?`{|}~^.-]+@[a-zA-Z0-9.-]+$").asPredicate();
        private static final Predicate<String> isValidPhone = Pattern.compile("^\\+?[0-9. ()-]{7,25}$").asPredicate();

        // Generic validation rule structure
        private static class ValidationRule {
            final String field;
            final Predicate<String> predicate;
            final String message;

            ValidationRule(String field, Predicate<String> predicate, String message) {
                this.field = field;
                this.predicate = predicate;
                this.message = message;
            }
        }

        // --- Validation Logic ---
        public static ValidationResult validate(Map<String, String> data, List<ValidationRule> rules) {
            Map<String, List<String>> errors = new HashMap<>();
            rules.forEach(rule -> {
                String value = data.get(rule.field);
                if (!rule.predicate.test(value)) {
                    errors.computeIfAbsent(rule.field, k -> new ArrayList<>()).add(rule.message);
                }
            });
            return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
        }

        // --- Type Coercion/Conversion ---
        public static User coerceToUser(Map<String, String> data) {
            try {
                return new User(
                    data.containsKey("id") ? UUID.fromString(data.get("id")) : UUID.randomUUID(),
                    data.get("email"),
                    data.get("password_hash"),
                    UserRole.valueOf(data.get("role").toUpperCase()),
                    Boolean.parseBoolean(data.getOrDefault("is_active", "true")),
                    data.containsKey("created_at") ? new Timestamp(Long.parseLong(data.get("created_at"))) : Timestamp.from(Instant.now())
                );
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to coerce map to User: " + e.getMessage(), e);
            }
        }

        // --- JSON Serialization/Deserialization ---
        public static String toJson(Map<String, Object> data) {
            String entries = data.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + formatJsonValue(entry.getValue()))
                .collect(Collectors.joining(","));
            return "{" + entries + "}";
        }

        private static String formatJsonValue(Object value) {
            if (value instanceof String || value instanceof UUID || value instanceof Enum) {
                return "\"" + value.toString().replace("\"", "\\\"") + "\"";
            }
            return String.valueOf(value);
        }

        public static Map<String, String> fromJson(String json) {
            Map<String, String> map = new HashMap<>();
            Pattern p = Pattern.compile("\"(.*?)\"\\s*:\\s*(\".*?\"|[^,}\\]]+)");
            java.util.regex.Matcher m = p.matcher(json);
            while (m.find()) {
                String key = m.group(1);
                String value = m.group(2).replaceAll("^\"|\"$", ""); // Remove quotes
                map.put(key, value);
            }
            return map;
        }

        // --- XML Serialization/Deserialization ---
        public static String toXml(String rootElementName, Map<String, Object> data) {
            try {
                Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
                Element root = doc.createElement(rootElementName);
                doc.appendChild(root);
                data.forEach((key, value) -> {
                    Element el = doc.createElement(key);
                    el.appendChild(doc.createTextNode(String.valueOf(value)));
                    root.appendChild(el);
                });
                StringWriter sw = new StringWriter();
                Transformer transformer = TransformerFactory.newInstance().newTransformer();
                transformer.transform(new DOMSource(doc), new StreamResult(sw));
                return sw.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static Map<String, String> fromXml(String xml) {
            Map<String, String> map = new HashMap<>();
            try {
                DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
                Document doc = builder.parse(new InputSource(new StringReader(xml)));
                NodeList childNodes = doc.getDocumentElement().getChildNodes();
                for (int i = 0; i < childNodes.getLength(); i++) {
                    Node node = childNodes.item(i);
                    if (node.getNodeType() == Node.ELEMENT_NODE) {
                        map.put(node.getNodeName(), node.getTextContent());
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return map;
        }
    }

    public static void main(String[] args) {
        System.out.println("--- Variation 2: Functional/Utility Demo ---");

        // 1. Define validation rules for user creation
        List<DataProcessor.ValidationRule> userRules = List.of(
            new DataProcessor.ValidationRule("email", isPresent, "Email is required."),
            new DataProcessor.ValidationRule("email", isValidEmail, "Email format is invalid."),
            new DataProcessor.ValidationRule("password_hash", isPresent, "Password is required."),
            new DataProcessor.ValidationRule("phone", (v) -> v == null || isValidPhone.test(v), "Phone number is invalid.")
        );

        // 2. Validate invalid data
        System.out.println("\n1. Validation Demo:");
        Map<String, String> invalidData = new HashMap<>();
        invalidData.put("email", "bad-email");
        invalidData.put("phone", "123");
        ValidationResult result = DataProcessor.validate(invalidData, userRules);
        System.out.println("Invalid data validation successful: " + !result.isValid());
        System.out.println("Error messages: " + result.errors());

        // 3. Validate and process valid data
        Map<String, String> validData = new HashMap<>();
        validData.put("email", "test@example.com");
        validData.put("password_hash", "hashed_pw");
        validData.put("role", "USER");
        result = DataProcessor.validate(validData, userRules);
        System.out.println("Valid data validation successful: " + result.isValid());

        // 4. Coerce valid data to a User record
        User user = DataProcessor.coerceToUser(validData);
        System.out.println("\n2. Coerced User Object: " + user);

        // 5. Serialize to JSON and XML
        Map<String, Object> userMap = Map.of(
            "id", user.id(),
            "email", user.email(),
            "role", user.role(),
            "is_active", user.is_active(),
            "created_at", user.created_at().getTime()
        );
        
        System.out.println("\n3. Serialization:");
        String json = DataProcessor.toJson(userMap);
        System.out.println("JSON: " + json);
        String xml = DataProcessor.toXml("user", userMap);
        System.out.println("XML: " + xml);

        // 6. Deserialize from JSON and XML
        System.out.println("\n4. Deserialization:");
        Map<String, String> mapFromJson = DataProcessor.fromJson(json);
        System.out.println("Map from JSON: " + mapFromJson);
        Map<String, String> mapFromXml = DataProcessor.fromXml(xml);
        System.out.println("Map from XML: " + mapFromXml);
    }
}