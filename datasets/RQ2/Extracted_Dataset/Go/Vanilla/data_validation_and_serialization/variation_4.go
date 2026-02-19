<pre class="go-highlight">
package main

import (
	"encoding/json"
	"encoding/xml"
	"fmt"
	"regexp"
	"strings"
	"time"
)

// --- Domain Models & Enums ---

type UserRole string
type PostStatus string

const (
	UserRoleAdmin UserRole = "ADMIN"
	UserRoleUser  UserRole = "USER"
)

const (
	PostStatusDraft     PostStatus = "DRAFT"
	PostStatusPublished PostStatus = "PUBLISHED"
)

// User model
type User struct {
	ID           string    `json:"id" xml:"id,attr"`
	Email        string    `json:"email" xml:"email"`
	PasswordHash string    `json:"-" xml:"-"`
	Role         UserRole  `json:"role" xml:"role"`
	IsActive     bool      `json:"is_active" xml:"is_active"`
	CreatedAt    time.Time `json:"created_at" xml:"created_at"`
}

// Post model
type Post struct {
	ID      string     `json:"id" xml:"id,attr"`
	UserID  string     `json:"user_id" xml:"user_id,attr"`
	Title   string     `json:"title" xml:"title"`
	Content string     `json:"content" xml:"content"`
	Status  PostStatus `json:"status" xml:"status"`
}

// --- Table-Driven Validation with Custom Errors ---

type ValidationErrors struct {
	Message string            `json:"message"`
	Errors  map[string]string `json:"errors"`
}

func (e *ValidationErrors) Error() string {
	var b strings.Builder
	b.WriteString(e.Message)
	b.WriteString(":\n")
	for field, err := range e.Errors {
		b.WriteString(fmt.Sprintf("  - %s: %s\n", field, err))
	}
	return b.String()
}

type ValidatorFunc func(value string) bool

type FieldRule struct {
	FieldName string
	Value     string
	Validator ValidatorFunc
	Message   string
}

func RunValidator(rules []FieldRule) error {
	errs := &ValidationErrors{
		Message: "Input validation failed",
		Errors:  make(map[string]string),
	}

	for _, rule := range rules {
		if !rule.Validator(rule.Value) {
			errs.Errors[rule.FieldName] = rule.Message
		}
	}

	if len(errs.Errors) > 0 {
		return errs
	}
	return nil
}

// --- Reusable Validator Functions ---

var isUUID ValidatorFunc = func(value string) bool {
	return regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`).MatchString(value)
}

var isEmail ValidatorFunc = func(value string) bool {
	return regexp.MustCompile(`^[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,4}$`).MatchString(value)
}

var isNotEmpty ValidatorFunc = func(value string) bool {
	return strings.TrimSpace(value) != ""
}

func isOneOf(allowed ...string) ValidatorFunc {
	return func(value string) bool {
		for _, v := range allowed {
			if value == v {
				return true
			}
		}
		return false
	}
}

// --- Main Execution ---

func main() {
	fmt.Println("--- Variation 4: Table-Driven Validation ---")

	// --- User Processing (Failure Case) ---
	fmt.Println("\n--- Processing User ---")
	userInputJSON := `{
		"id": "123e4567-e89b-12d3-a456-426614174000",
		"email": "not-an-email",
		"role": "SUPERUSER",
		"is_active": true,
		"created_at": "2023-10-27T10:00:00Z"
	}`

	var user User
	json.Unmarshal([]byte(userInputJSON), &user)
	fmt.Printf("Deserialized User: %+v\n", user)

	// Define validation rules for User
	userValidationRules := []FieldRule{
		{"id", user.ID, isUUID, "Must be a valid UUID"},
		{"email", user.Email, isNotEmpty, "Email is required"},
		{"email", user.Email, isEmail, "Must be a valid email address"},
		{"role", string(user.Role), isOneOf(string(UserRoleAdmin), string(UserRoleUser)), "Invalid role specified"},
	}

	// Run validation
	if err := RunValidator(userValidationRules); err != nil {
		fmt.Println(err.Error())
		// Demonstrate serializing the structured error to JSON
		errJson, _ := json.MarshalIndent(err, "", "  ")
		fmt.Println("Serialized Error Response:")
		fmt.Println(string(errJson))
	} else {
		fmt.Println("User validation successful.")
	}

	// --- Post Processing (Successful Case) ---
	fmt.Println("\n--- Processing Post ---")
	postInputJSON := `{
		"id": "a1b2c3d4-e5f6-7890-1234-567890abcdef",
		"user_id": "123e4567-e89b-12d3-a456-426614174000",
		"title": "A Valid Post",
		"content": "This is a valid post.",
		"status": "DRAFT"
	}`

	var post Post
	json.Unmarshal([]byte(postInputJSON), &post)
	fmt.Printf("Deserialized Post: %+v\n", post)

	// Define validation rules for Post
	postValidationRules := []FieldRule{
		{"id", post.ID, isUUID, "Must be a valid UUID"},
		{"user_id", post.UserID, isUUID, "Must be a valid UUID"},
		{"title", post.Title, isNotEmpty, "Title is required"},
		{"status", string(post.Status), isOneOf(string(PostStatusDraft), string(PostStatusPublished)), "Invalid status specified"},
	}

	if err := RunValidator(postValidationRules); err != nil {
		fmt.Println(err.Error())
	} else {
		fmt.Println("Post validation successful.")
		// Serialize to XML
		xmlBytes, _ := xml.MarshalIndent(post, "", "  ")
		fmt.Println("\nSerialized Post to XML:")
		fmt.Println(string(xmlBytes))
	}
}
</pre>