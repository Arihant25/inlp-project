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
	ROLE_ADMIN UserRole = "ADMIN"
	ROLE_USER  UserRole = "USER"
)

const (
	STATUS_DRAFT     PostStatus = "DRAFT"
	STATUS_PUBLISHED PostStatus = "PUBLISHED"
)

type User struct {
	ID           string    `json:"id" xml:"id,attr"`
	Email        string    `json:"email" xml:"email"`
	PasswordHash string    `json:"-" xml:"-"`
	Role         UserRole  `json:"role" xml:"role"`
	IsActive     bool      `json:"is_active" xml:"is_active"`
	CreatedAt    time.Time `json:"created_at" xml:"created_at"`
}

type Post struct {
	ID      string     `json:"id" xml:"id,attr"`
	UserID  string     `json:"user_id" xml:"user_id,attr"`
	Title   string     `json:"title" xml:"title"`
	Content string     `json:"content" xml:"content"`
	Status  PostStatus `json:"status" xml:"status"`
}

// --- Validator Struct Pattern ---

type Validator struct {
	Errors map[string]string
}

func NewValidator() *Validator {
	return &Validator{Errors: make(map[string]string)}
}

func (v *Validator) Valid() bool {
	return len(v.Errors) == 0
}

func (v *Validator) AddError(field, message string) {
	if _, exists := v.Errors[field]; !exists {
		v.Errors[field] = message
	}
}

func (v *Validator) Check(ok bool, field, message string) {
	if !ok {
		v.AddError(field, message)
	}
}

func (v *Validator) Required(field, value string) {
	v.Check(strings.TrimSpace(value) != "", field, "This field is required")
}

func (v *Validator) Matches(field, value string, rx *regexp.Regexp) {
	v.Check(rx.MatchString(value), field, "This field has an invalid format")
}

func (v *Validator) PermittedValue(field string, value any, permitted ...any) {
	for i := range permitted {
		if value == permitted[i] {
			return
		}
	}
	v.Check(false, field, "This field has an invalid value")
}

// --- Main Execution ---

func main() {
	fmt.Println("--- Variation 3: Validator Struct Pattern ---")

	// --- User Processing ---
	fmt.Println("\n--- Processing User ---")
	userInputJSON := `{
		"id": "123e4567-e89b-12d3-a456-426614174000",
		"email": "test.user@example.com",
		"role": "ADMIN",
		"is_active": true,
		"created_at": "2023-10-27T10:00:00Z"
	}`

	var user User
	if err := json.Unmarshal([]byte(userInputJSON), &user); err != nil {
		panic(err)
	}
	fmt.Printf("Deserialized User: %+v\n", user)

	// Validate User using the Validator struct
	userValidator := NewValidator()
	userValidator.Matches("id", user.ID, regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`))
	userValidator.Required("email", user.Email)
	userValidator.Matches("email", user.Email, regexp.MustCompile(`^[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,4}$`))
	userValidator.PermittedValue("role", user.Role, ROLE_ADMIN, ROLE_USER)

	if !userValidator.Valid() {
		fmt.Println("User validation failed:")
		for field, msg := range userValidator.Errors {
			fmt.Printf("  - %s: %s\n", field, msg)
		}
	} else {
		fmt.Println("User validation successful.")
		xmlBytes, _ := xml.MarshalIndent(user, "", "  ")
		fmt.Println("\nSerialized User to XML:")
		fmt.Println(string(xmlBytes))
	}

	// --- Post Processing (Failure Case) ---
	fmt.Println("\n--- Processing Post ---")
	postInputJSON := `{
		"id": "a1b2c3d4-e5f6-7890-1234-567890abcdef",
		"user_id": "",
		"title": "  ",
		"content": "This post has validation errors.",
		"status": "ARCHIVED"
	}`

	var post Post
	if err := json.Unmarshal([]byte(postInputJSON), &post); err != nil {
		panic(err)
	}
	fmt.Printf("Deserialized Post: %+v\n", post)

	// Validate Post using the Validator struct
	postValidator := NewValidator()
	postValidator.Matches("id", post.ID, regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`))
	postValidator.Required("user_id", post.UserID)
	postValidator.Matches("user_id", post.UserID, regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`))
	postValidator.Required("title", post.Title)
	postValidator.PermittedValue("status", post.Status, STATUS_DRAFT, STATUS_PUBLISHED)

	if !postValidator.Valid() {
		fmt.Println("Post validation failed:")
		for field, msg := range postValidator.Errors {
			fmt.Printf("  - %s: %s\n", field, msg)
		}
	} else {
		fmt.Println("Post validation successful.")
		xmlBytes, _ := xml.MarshalIndent(post, "", "  ")
		fmt.Println("\nSerialized Post to XML:")
		fmt.Println(string(xmlBytes))
	}
}
</pre>