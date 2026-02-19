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

// --- Custom Error Type ---

type FieldValidationError struct {
	Field   string
	Message string
}

func (e FieldValidationError) Error() string {
	return fmt.Sprintf("validation failed for field '%s': %s", e.Field, e.Message)
}

// --- Domain Models with Methods ---

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

type User struct {
	Id           string    `json:"id" xml:"id,attr"`
	EmailAddress string    `json:"email" xml:"Email"`
	PasswordHash string    `json:"-" xml:"-"`
	Role         UserRole  `json:"role" xml:"Role"`
	IsActive     bool      `json:"is_active" xml:"IsActive"`
	CreatedAt    time.Time `json:"created_at" xml:"CreatedAt"`
}

// Validate checks all fields of the User struct.
func (u *User) Validate() []FieldValidationError {
	var errs []FieldValidationError

	if u.Id == "" {
		errs = append(errs, FieldValidationError{Field: "Id", Message: "is required"})
	} else if !regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`).MatchString(u.Id) {
		errs = append(errs, FieldValidationError{Field: "Id", Message: "is not a valid UUID"})
	}

	if u.EmailAddress == "" {
		errs = append(errs, FieldValidationError{Field: "EmailAddress", Message: "is required"})
	} else if !regexp.MustCompile(`^[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,4}$`).MatchString(u.EmailAddress) {
		errs = append(errs, FieldValidationError{Field: "EmailAddress", Message: "is not a valid email address"})
	}

	if u.Role != RoleAdmin && u.Role != RoleUser {
		errs = append(errs, FieldValidationError{Field: "Role", Message: "is not a valid role (must be ADMIN or USER)"})
	}

	return errs
}

// ToJSON serializes the User object to a JSON byte slice.
func (u *User) ToJSON() ([]byte, error) {
	return json.MarshalIndent(u, "", "  ")
}

// FromXML deserializes an XML byte slice into the User object.
func (u *User) FromXML(data []byte) error {
	return xml.Unmarshal(data, u)
}

type Post struct {
	PostId  string     `json:"id" xml:"id,attr"`
	AuthorId string     `json:"user_id" xml:"user_id,attr"`
	Title   string     `json:"title" xml:"Title"`
	Content string     `json:"content" xml:"Content"`
	Status  PostStatus `json:"status" xml:"Status"`
}

// Validate checks all fields of the Post struct.
func (p *Post) Validate() []FieldValidationError {
	var errs []FieldValidationError

	if !regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`).MatchString(p.PostId) {
		errs = append(errs, FieldValidationError{Field: "PostId", Message: "is not a valid UUID"})
	}

	if !regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`).MatchString(p.AuthorId) {
		errs = append(errs, FieldValidationError{Field: "AuthorId", Message: "is not a valid UUID"})
	}

	if strings.TrimSpace(p.Title) == "" {
		errs = append(errs, FieldValidationError{Field: "Title", Message: "cannot be empty"})
	}

	if p.Status != StatusDraft && p.Status != StatusPublished {
		errs = append(errs, FieldValidationError{Field: "Status", Message: "is not a valid status (must be DRAFT or PUBLISHED)"})
	}

	return errs
}

// ToXML serializes the Post object to an XML byte slice.
func (p *Post) ToXML() ([]byte, error) {
	return xml.MarshalIndent(p, "", "  ")
}

// FromJSON deserializes a JSON byte slice into the Post object.
func (p *Post) FromJSON(data []byte) error {
	return json.Unmarshal(data, p)
}

// --- Main Execution ---

func main() {
	fmt.Println("--- Variation 2: Object-Oriented Approach ---")

	// --- User Processing (Successful Case) ---
	fmt.Println("\n--- Processing User ---")
	userXMLInput := `
	<User id="f47ac10b-58cc-4372-a567-0e02b2c3d479">
		<Email>test.user@example.com</Email>
		<Role>ADMIN</Role>
		<IsActive>true</IsActive>
		<CreatedAt>2023-10-27T11:30:00Z</CreatedAt>
	</User>`

	var user User
	// Deserialize from XML
	if err := user.FromXML([]byte(userXMLInput)); err != nil {
		fmt.Println("XML Deserialization Error:", err)
		return
	}
	fmt.Printf("Deserialized User from XML: %+v\n", user)

	// Validate
	if errs := user.Validate(); len(errs) > 0 {
		fmt.Println("User validation failed:")
		for _, e := range errs {
			fmt.Printf("  - %s\n", e.Error())
		}
	} else {
		fmt.Println("User validation successful.")
		// Serialize to JSON
		jsonOutput, err := user.ToJSON()
		if err != nil {
			fmt.Println("JSON Serialization Error:", err)
		} else {
			fmt.Println("\nSerialized User to JSON:")
			fmt.Println(string(jsonOutput))
		}
	}

	// --- Post Processing (Failure Case) ---
	fmt.Println("\n--- Processing Post ---")
	postJSONInput := `{
		"id": "a1b2c3d4-e5f6-7890-1234-567890abcdef",
		"user_id": "not-a-uuid",
		"title": "  ",
		"content": "This post has validation errors.",
		"status": "PENDING"
	}`

	var post Post
	if err := post.FromJSON([]byte(postJSONInput)); err != nil {
		fmt.Println("JSON Deserialization Error:", err)
		return
	}
	fmt.Printf("Deserialized Post from JSON: %+v\n", post)

	if errs := post.Validate(); len(errs) > 0 {
		fmt.Println("Post validation failed:")
		for _, e := range errs {
			fmt.Printf("  - %s\n", e.Error())
		}
	} else {
		fmt.Println("Post validation successful.")
		xmlOutput, err := post.ToXML()
		if err != nil {
			fmt.Println("XML Serialization Error:", err)
		} else {
			fmt.Println("\nSerialized Post to XML:")
			fmt.Println(string(xmlOutput))
		}
	}
}
</pre>