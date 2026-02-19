package main

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"strings"
	"time"
	"crypto/rand"

	// The standard way to use a database in Go is to use the `database/sql`
	// package with a specific driver. `go-sqlite3` is a popular driver for SQLite.
	_ "github.com/mattn/go-sqlite3"
)

// --- Domain Models & Enums ---

type UserRole string
const (
	RoleAdmin UserRole = "ADMIN"
	RoleUser  UserRole = "USER"
)

type PostStatus string
const (
	StatusDraft     PostStatus = "DRAFT"
	StatusPublished PostStatus = "PUBLISHED"
)

type User struct {
	ID           string
	Email        string
	PasswordHash string
	IsActive     bool
	CreatedAt    time.Time
	Roles        []string // For M-M relationship
}

type Post struct {
	ID      string
	UserID  string
	Title   string
	Content string
	Status  PostStatus
}

// --- UUID Helper ---
// newUUID generates a random UUID v4.
func newUUID() (string, error) {
	b := make([]byte, 16)
	_, err := rand.Read(b)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:]), nil
}

// --- Database Migrations ---

func runMigrations(db *sql.DB) error {
	// Use a transaction for migrations
	tx, err := db.Begin()
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer tx.Rollback() // Rollback on error

	// Check and create users table
	_, err = tx.Exec(`
		CREATE TABLE IF NOT EXISTS users (
			id TEXT PRIMARY KEY,
			email TEXT UNIQUE NOT NULL,
			password_hash TEXT NOT NULL,
			is_active BOOLEAN NOT NULL,
			created_at TIMESTAMP NOT NULL
		);
	`)
	if err != nil {
		return fmt.Errorf("failed to create users table: %w", err)
	}

	// Check and create posts table
	_, err = tx.Exec(`
		CREATE TABLE IF NOT EXISTS posts (
			id TEXT PRIMARY KEY,
			user_id TEXT NOT NULL,
			title TEXT NOT NULL,
			content TEXT NOT NULL,
			status TEXT NOT NULL,
			FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
		);
	`)
	if err != nil {
		return fmt.Errorf("failed to create posts table: %w", err)
	}

	// Check and create roles table
	_, err = tx.Exec(`
		CREATE TABLE IF NOT EXISTS roles (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			name TEXT UNIQUE NOT NULL
		);
	`)
	if err != nil {
		return fmt.Errorf("failed to create roles table: %w", err)
	}

	// Check and create user_roles join table
	_, err = tx.Exec(`
		CREATE TABLE IF NOT EXISTS user_roles (
			user_id TEXT NOT NULL,
			role_id INTEGER NOT NULL,
			PRIMARY KEY (user_id, role_id),
			FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
			FOREIGN KEY(role_id) REFERENCES roles(id) ON DELETE CASCADE
		);
	`)
	if err != nil {
		return fmt.Errorf("failed to create user_roles table: %w", err)
	}

	return tx.Commit()
}

// --- Database Operations (Functional) ---

// CreateUser inserts a new user into the database.
func createUser(ctx context.Context, db *sql.DB, user *User) error {
	user.ID, _ = newUUID()
	user.CreatedAt = time.Now().UTC()
	_, err := db.ExecContext(ctx,
		"INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)",
		user.ID, user.Email, user.PasswordHash, user.IsActive, user.CreatedAt)
	return err
}

// getUserByID retrieves a user by their ID.
func getUserByID(ctx context.Context, db *sql.DB, id string) (*User, error) {
	row := db.QueryRowContext(ctx, "SELECT id, email, password_hash, is_active, created_at FROM users WHERE id = ?", id)
	user := &User{}
	err := row.Scan(&user.ID, &user.Email, &user.PasswordHash, &user.IsActive, &user.CreatedAt)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, fmt.Errorf("user not found")
		}
		return nil, err
	}
	return user, nil
}

// createPost inserts a new post for a user.
func createPost(ctx context.Context, db *sql.DB, post *Post) error {
	post.ID, _ = newUUID()
	_, err := db.ExecContext(ctx,
		"INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)",
		post.ID, post.UserID, post.Title, post.Content, post.Status)
	return err
}

// getPostsByUserID retrieves all posts for a given user.
func getPostsByUserID(ctx context.Context, db *sql.DB, userID string) ([]Post, error) {
	rows, err := db.QueryContext(ctx, "SELECT id, user_id, title, content, status FROM posts WHERE user_id = ?", userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var posts []Post
	for rows.Next() {
		var p Post
		if err := rows.Scan(&p.ID, &p.UserID, &p.Title, &p.Content, &p.Status); err != nil {
			return nil, err
		}
		posts = append(posts, p)
	}
	return posts, rows.Err()
}

// findUsers builds a query with filters to find users.
func findUsers(ctx context.Context, db *sql.DB, isActive *bool, emailLike *string) ([]User, error) {
	query := "SELECT id, email, password_hash, is_active, created_at FROM users WHERE 1=1"
	args := []interface{}{}

	if isActive != nil {
		query += " AND is_active = ?"
		args = append(args, *isActive)
	}
	if emailLike != nil {
		query += " AND email LIKE ?"
		args = append(args, *emailLike)
	}

	rows, err := db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var users []User
	for rows.Next() {
		var u User
		if err := rows.Scan(&u.ID, &u.Email, &u.PasswordHash, &u.IsActive, &u.CreatedAt); err != nil {
			return nil, err
		}
		users = append(users, u)
	}
	return users, rows.Err()
}

// performTransactionalWork demonstrates a transaction with a potential rollback.
func performTransactionalWork(ctx context.Context, db *sql.DB, user *User, post *Post, shouldFail bool) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	// Defer a rollback. If the transaction is committed, this is a no-op.
	defer tx.Rollback()

	user.ID, _ = newUUID()
	user.CreatedAt = time.Now().UTC()
	_, err = tx.ExecContext(ctx,
		"INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)",
		user.ID, user.Email, user.PasswordHash, user.IsActive, user.CreatedAt)
	if err != nil {
		return fmt.Errorf("failed to insert user in transaction: %w", err)
	}

	post.ID, _ = newUUID()
	post.UserID = user.ID
	_, err = tx.ExecContext(ctx,
		"INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)",
		post.ID, post.UserID, post.Title, post.Content, post.Status)
	if err != nil {
		return fmt.Errorf("failed to insert post in transaction: %w", err)
	}

	if shouldFail {
		return fmt.Errorf("simulating a failure to trigger rollback")
	}

	return tx.Commit()
}

// --- Many-to-Many Operations ---
func ensureRole(ctx context.Context, db *sql.DB, name string) (int64, error) {
	var id int64
	err := db.QueryRowContext(ctx, "SELECT id FROM roles WHERE name = ?", name).Scan(&id)
	if err == sql.ErrNoRows {
		res, err := db.ExecContext(ctx, "INSERT INTO roles (name) VALUES (?)", name)
		if err != nil {
			return 0, err
		}
		return res.LastInsertId()
	}
	return id, err
}

func assignRoleToUser(ctx context.Context, db *sql.DB, userID string, roleID int64) error {
	_, err := db.ExecContext(ctx, "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", userID, roleID)
	return err
}

func getUserWithRoles(ctx context.Context, db *sql.DB, userID string) (*User, error) {
	user, err := getUserByID(ctx, db, userID)
	if err != nil {
		return nil, err
	}

	rows, err := db.QueryContext(ctx, `
		SELECT r.name FROM roles r
		JOIN user_roles ur ON r.id = ur.role_id
		WHERE ur.user_id = ?`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var roles []string
	for rows.Next() {
		var roleName string
		if err := rows.Scan(&roleName); err != nil {
			return nil, err
		}
		roles = append(roles, roleName)
	}
	user.Roles = roles
	return user, rows.Err()
}


func main() {
	ctx := context.Background()
	// Use in-memory SQLite database for demonstration
	db, err := sql.Open("sqlite3", ":memory:")
	if err != nil {
		log.Fatalf("Failed to open database: %v", err)
	}
	defer db.Close()

	if err := runMigrations(db); err != nil {
		log.Fatalf("Failed to run migrations: %v", err)
	}
	log.Println("Migrations completed successfully.")

	// 1. CRUD Demo
	log.Println("\n--- CRUD Demo ---")
	user1 := &User{Email: "test@example.com", PasswordHash: "hash123", IsActive: true}
	if err := createUser(ctx, db, user1); err != nil {
		log.Fatalf("Failed to create user: %v", err)
	}
	log.Printf("Created user with ID: %s", user1.ID)

	fetchedUser, err := getUserByID(ctx, db, user1.ID)
	if err != nil {
		log.Fatalf("Failed to get user: %v", err)
	}
	log.Printf("Fetched user: %+v", fetchedUser)

	// 2. One-to-Many Demo
	log.Println("\n--- One-to-Many Demo (User -> Posts) ---")
	post1 := &Post{UserID: user1.ID, Title: "My First Post", Content: "Hello World!", Status: StatusPublished}
	if err := createPost(ctx, db, post1); err != nil {
		log.Fatalf("Failed to create post: %v", err)
	}
	log.Printf("Created post with ID: %s for user %s", post1.ID, user1.ID)

	userPosts, err := getPostsByUserID(ctx, db, user1.ID)
	if err != nil {
		log.Fatalf("Failed to get posts for user: %v", err)
	}
	log.Printf("Fetched posts for user %s: %+v", user1.ID, userPosts)

	// 3. Many-to-Many Demo
	log.Println("\n--- Many-to-Many Demo (User <-> Roles) ---")
	adminRoleID, _ := ensureRole(ctx, db, string(RoleAdmin))
	userRoleID, _ := ensureRole(ctx, db, string(RoleUser))
	assignRoleToUser(ctx, db, user1.ID, adminRoleID)
	assignRoleToUser(ctx, db, user1.ID, userRoleID)
	log.Printf("Assigned roles 'ADMIN' and 'USER' to user %s", user1.ID)
	
	userWithRoles, err := getUserWithRoles(ctx, db, user1.ID)
	if err != nil {
		log.Fatalf("Failed to get user with roles: %v", err)
	}
	log.Printf("Fetched user with roles: %+v", userWithRoles)

	// 4. Transaction Demo
	log.Println("\n--- Transaction Demo ---")
	txUser := &User{Email: "tx_success@example.com", PasswordHash: "hash_tx", IsActive: true}
	txPost := &Post{Title: "TX Post", Content: "This should succeed", Status: StatusDraft}
	err = performTransactionalWork(ctx, db, txUser, txPost, false)
	if err != nil {
		log.Fatalf("Transactional work failed unexpectedly: %v", err)
	}
	log.Println("Transactional work committed successfully.")

	txUserFail := &User{Email: "tx_fail@example.com", PasswordHash: "hash_tx_fail", IsActive: false}
	txPostFail := &Post{Title: "TX Fail Post", Content: "This should fail", Status: StatusDraft}
	err = performTransactionalWork(ctx, db, txUserFail, txPostFail, true)
	if err != nil {
		log.Printf("Transactional work failed as expected and was rolled back: %v", err)
	}
	_, err = getUserByID(ctx, db, txUserFail.ID)
	if err != nil {
		log.Printf("User from failed transaction was not found, as expected.")
	}

	// 5. Query Building with Filters Demo
	log.Println("\n--- Query Filter Demo ---")
	isActive := true
	emailPattern := "%@example.com"
	filteredUsers, err := findUsers(ctx, db, &isActive, &emailPattern)
	if err != nil {
		log.Fatalf("Failed to find users with filter: %v", err)
	}
	log.Printf("Found %d active users with email ending in '@example.com':", len(filteredUsers))
	for _, u := range filteredUsers {
		log.Printf("  - User: %+v", u)
	}
}