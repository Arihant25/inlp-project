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

// --- Domain Models ---

type UserRole string
type PostStatus string

const (
	RoleAdmin UserRole = "ADMIN"
	RoleUser  UserRole = "USER"
)

const (
	StatusDraft     PostStatus = "DRAFT"
	StatusPublished PostStatus = "PUBLISHED"
)

// User represents a user in the system.
type User struct {
	ID           string    `json:"id" xml:"id,attr"`
	Email        string    `json:"email" xml:"Email"`
	PasswordHash string    `json:"-" xml:"-"` // Omitted from serialization
	Role         UserRole  `json:"role" xml:"Role"`
	IsActive     bool      `json:"is_active" xml:"IsActive"`
	CreatedAt    time.Time `json:"created_at" xml:"CreatedAt"`
}

// Post represents a blog post written by a user.
type Post struct {
	ID      string     `json:"id" xml:"id,attr"`
	UserID  string     `json:"user_id" xml:"user_id,attr"`
	Title   string     `json:"title" xml:"Title"`
	Content string     `json:"content" xml:"Content"`
	Status  PostStatus `json:"status" xml:"Status"`
}

// --- Validation Logic (Functional Approach) ---

var (
	emailRegex = regexp.MustCompile(`^[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,4}$`)
	uuidRegex  = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
)

// validateRequired checks if a string field is empty.
func validateRequired(field, value string, errors map[string]string) {
	if strings.TrimSpace(value) == "" {
		errors[field] = "is required"
	}
}

// validateEmail checks for a valid email format.
func validateEmail(field, value string, errors map[string]string) {
	if !emailRegex.MatchString(value) {
		errors[field] = "is not a valid email address"
	}
}

// validateUUID checks for a valid UUID format.
func validateUUID(field, value string, errors map[string]string) {
	if !uuidRegex.MatchString(value) {
		errors[field] = "is not a valid UUID"
	}
}

// validateUserRole checks if the role is one of the predefined values.
func validateUserRole(field string, role UserRole, errors map[string]string) {
	switch role {
	case RoleAdmin, RoleUser:
		// valid
	default:
		errors[field] = "is not a valid role"
	}
}

// validatePostStatus checks if the status is one of the predefined values.
func validatePostStatus(field string, status PostStatus, errors map[string]string) {
	switch status {
	case StatusDraft, StatusPublished:
		// valid
	default:
		errors[field] = "is not a valid status"
	}
}

// ValidateUser performs a full validation of a User struct.
func ValidateUser(user *User) map[string]string {
	errors := make(map[string]string)
	validateUUID("id", user.ID, errors)
	validateRequired("email", user.Email, errors)
	validateEmail("email", user.Email, errors)
	validateUserRole("role", user.Role, errors)
	return errors
}

// ValidatePost performs a full validation of a Post struct.
func ValidatePost(post *Post) map[string]string {
	errors := make(map[string]string)
	validateUUID("id", post.ID, errors)
	validateUUID("user_id", post.UserID, errors)
	validateRequired("title", post.Title, errors)
	validatePostStatus("status", post.Status, errors)
	return errors
}

// formatErrors converts the error map to a readable string.
func formatErrors(errors map[string]string) string {
	var b strings.Builder
	b.WriteString("Validation failed:\n")
	for field, msg := range errors {
		b.WriteString(fmt.Sprintf("  - Field '%s': %s\n", field, msg))
	}
	return b.String()
}

// --- Main Execution ---

func main() {
	fmt.Println("--- Variation 1: Functional Approach ---")

	// --- User Processing ---
	fmt.Println("\n--- Processing User ---")
	userInputJSON := `{
		"id": "123e4567-e89b-12d3-a456-426614174000",
		"email": "invalid-email",
		"role": "GUEST",
		"is_active": true,
		"created_at": "2023-10-27T10:00:00Z"
	}`

	var user User
	// Deserialize from JSON
	if err := json.Unmarshal([]byte(userInputJSON), &user); err != nil {
		fmt.Println("JSON Deserialization Error:", err)
		return
	}
	fmt.Printf("Deserialized User: %+v\n", user)

	// Validate
	userErrors := ValidateUser(&user)
	if len(userErrors) > 0 {
		fmt.Println(formatErrors(userErrors))
	} else {
		fmt.Println("User validation successful.")
		// Serialize to XML
		user.PasswordHash = "should-not-be-serialized" // Add data that should be ignored
		xmlOutput, err := xml.MarshalIndent(user, "", "  ")
		if err != nil {
			fmt.Println("XML Serialization Error:", err)
		} else {
			fmt.Println("\nSerialized User to XML:")
			fmt.Println(string(xmlOutput))
		}
	}

	// --- Post Processing (Successful Case) ---
	fmt.Println("\n--- Processing Post ---")
	postInputJSON := `{
		"id": "a1b2c3d4-e5f6-7890-1234-567890abcdef",
		"user_id": "123e4567-e89b-12d3-a456-426614174000",
		"title": "Go Standard Library Rocks",
		"content": "Here is some content.",
		"status": "PUBLISHED"
	}`

	var post Post
	if err := json.Unmarshal([]byte(postInputJSON), &post); err != nil {
		fmt.Println("JSON Deserialization Error:", err)
		return
	}
	fmt.Printf("Deserialized Post: %+v\n", post)

	postErrors := ValidatePost(&post)
	if len(postErrors) > 0 {
		fmt.Println(formatErrors(postErrors))
	} else {
		fmt.Println("Post validation successful.")
		xmlOutput, err := xml.MarshalIndent(post, "", "  ")
		if err != nil {
			fmt.Println("XML Serialization Error:", err)
		} else {
			fmt.Println("\nSerialized Post to XML:")
			fmt.Println(string(xmlOutput))
		}
	}
}
</pre>