package main

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"strings"
	"time"
	"crypto/rand"

	_ "github.com/mattn/go-sqlite3"
)

// --- Domain Models & Enums ---

type RoleName string
const (
	AdminRole RoleName = "ADMIN"
	UserRole  RoleName = "USER"
)

type PostStatus string
const (
	DraftStatus     PostStatus = "DRAFT"
	PublishedStatus PostStatus = "PUBLISHED"
)

type User struct {
	ID           string
	Email        string
	PasswordHash string
	IsActive     bool
	CreatedAt    time.Time
}

type Post struct {
	ID      string
	UserID  string
	Title   string
	Content string
	Status  PostStatus
}

type Role struct {
	ID   int64
	Name RoleName
}

// --- UUID Helper ---
func generateUUID() string {
	b := make([]byte, 16)
	_, err := rand.Read(b)
	if err != nil {
		log.Fatalf("Failed to generate UUID: %v", err)
	}
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}

// --- Repository Layer ---

type Querier interface {
	ExecContext(ctx context.Context, query string, args ...interface{}) (sql.Result, error)
	QueryContext(ctx context.Context, query string, args ...interface{}) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...interface{}) *sql.Row
}

type UserRepository interface {
	Create(ctx context.Context, q Querier, user *User) error
	FindByID(ctx context.Context, q Querier, id string) (*User, error)
	FindByFilter(ctx context.Context, q Querier, filter UserFilter) ([]User, error)
	AssignRole(ctx context.Context, q Querier, userID string, roleID int64) error
	FindRolesByUserID(ctx context.Context, q Querier, userID string) ([]Role, error)
}

type PostRepository interface {
	Create(ctx context.Context, q Querier, post *Post) error
	FindByUserID(ctx context.Context, q Querier, userID string) ([]Post, error)
}

type RoleRepository interface {
	FindOrCreateByName(ctx context.Context, q Querier, name RoleName) (*Role, error)
}

// --- Concrete Implementations ---

type DBStore struct {
	db *sql.DB
	UserRepository
	PostRepository
	RoleRepository
}

func NewDBStore(db *sql.DB) *DBStore {
	return &DBStore{
		db:             db,
		UserRepository: &dbUserRepository{},
		PostRepository: &dbPostRepository{},
		RoleRepository: &dbRoleRepository{},
	}
}

// WithTransaction provides a managed transaction.
func (s *DBStore) WithTransaction(ctx context.Context, fn func(q Querier) error) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if err := fn(tx); err != nil {
		return err
	}

	return tx.Commit()
}

// --- User Repository ---
type dbUserRepository struct{}

type UserFilter struct {
	IsActive  *bool
	EmailLike *string
}

func (r *dbUserRepository) Create(ctx context.Context, q Querier, user *User) error {
	user.ID = generateUUID()
	user.CreatedAt = time.Now().UTC()
	query := "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)"
	_, err := q.ExecContext(ctx, query, user.ID, user.Email, user.PasswordHash, user.IsActive, user.CreatedAt)
	return err
}

func (r *dbUserRepository) FindByID(ctx context.Context, q Querier, id string) (*User, error) {
	query := "SELECT id, email, password_hash, is_active, created_at FROM users WHERE id = ?"
	row := q.QueryRowContext(ctx, query, id)
	var u User
	err := row.Scan(&u.ID, &u.Email, &u.PasswordHash, &u.IsActive, &u.CreatedAt)
	if err == sql.ErrNoRows {
		return nil, fmt.Errorf("user with id %s not found", id)
	}
	return &u, err
}

func (r *dbUserRepository) FindByFilter(ctx context.Context, q Querier, filter UserFilter) ([]User, error) {
	var args []interface{}
	var conditions []string

	if filter.IsActive != nil {
		conditions = append(conditions, "is_active = ?")
		args = append(args, *filter.IsActive)
	}
	if filter.EmailLike != nil {
		conditions = append(conditions, "email LIKE ?")
		args = append(args, *filter.EmailLike)
	}

	query := "SELECT id, email, password_hash, is_active, created_at FROM users"
	if len(conditions) > 0 {
		query += " WHERE " + strings.Join(conditions, " AND ")
	}

	rows, err := q.QueryContext(ctx, query, args...)
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

func (r *dbUserRepository) AssignRole(ctx context.Context, q Querier, userID string, roleID int64) error {
	query := "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)"
	_, err := q.ExecContext(ctx, query, userID, roleID)
	return err
}

func (r *dbUserRepository) FindRolesByUserID(ctx context.Context, q Querier, userID string) ([]Role, error) {
	query := `SELECT r.id, r.name FROM roles r JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = ?`
	rows, err := q.QueryContext(ctx, query, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var roles []Role
	for rows.Next() {
		var role Role
		if err := rows.Scan(&role.ID, &role.Name); err != nil {
			return nil, err
		}
		roles = append(roles, role)
	}
	return roles, rows.Err()
}

// --- Post Repository ---
type dbPostRepository struct{}

func (r *dbPostRepository) Create(ctx context.Context, q Querier, post *Post) error {
	post.ID = generateUUID()
	query := "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)"
	_, err := q.ExecContext(ctx, query, post.ID, post.UserID, post.Title, post.Content, post.Status)
	return err
}

func (r *dbPostRepository) FindByUserID(ctx context.Context, q Querier, userID string) ([]Post, error) {
	query := "SELECT id, user_id, title, content, status FROM posts WHERE user_id = ?"
	rows, err := q.QueryContext(ctx, query, userID)
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

// --- Role Repository ---
type dbRoleRepository struct{}

func (r *dbRoleRepository) FindOrCreateByName(ctx context.Context, q Querier, name RoleName) (*Role, error) {
	var role Role
	query := "SELECT id, name FROM roles WHERE name = ?"
	err := q.QueryRowContext(ctx, query, name).Scan(&role.ID, &role.Name)
	if err == sql.ErrNoRows {
		insertQuery := "INSERT INTO roles (name) VALUES (?) RETURNING id"
		// Note: RETURNING is PostgreSQL specific. For SQLite, we do it in two steps.
		res, err := q.ExecContext(ctx, "INSERT INTO roles (name) VALUES (?)", name)
		if err != nil {
			return nil, err
		}
		id, _ := res.LastInsertId()
		return &Role{ID: id, Name: name}, nil
	}
	return &role, err
}

// --- Migrations ---
func applyMigrations(db *sql.DB) error {
	migrations := []string{
		`CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL, is_active BOOLEAN NOT NULL, created_at TIMESTAMP NOT NULL);`,
		`CREATE TABLE IF NOT EXISTS posts (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL, FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE);`,
		`CREATE TABLE IF NOT EXISTS roles (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL);`,
		`CREATE TABLE IF NOT EXISTS user_roles (user_id TEXT NOT NULL, role_id INTEGER NOT NULL, PRIMARY KEY (user_id, role_id), FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE, FOREIGN KEY(role_id) REFERENCES roles(id) ON DELETE CASCADE);`,
	}
	for _, m := range migrations {
		if _, err := db.Exec(m); err != nil {
			return fmt.Errorf("migration failed: %w", err)
		}
	}
	return nil
}

func main() {
	ctx := context.Background()
	db, err := sql.Open("sqlite3", ":memory:?_foreign_keys=on")
	if err != nil {
		log.Fatalf("DB connection error: %v", err)
	}
	defer db.Close()

	if err := applyMigrations(db); err != nil {
		log.Fatalf("Migration error: %v", err)
	}
	log.Println("Migrations applied.")

	store := NewDBStore(db)

	// 1. CRUD Demo
	log.Println("\n--- CRUD Demo ---")
	user1 := &User{Email: "repo.user@example.com", PasswordHash: "repo_hash", IsActive: true}
	if err := store.UserRepository.Create(ctx, db, user1); err != nil {
		log.Fatalf("Create user failed: %v", err)
	}
	log.Printf("Created user: %s", user1.ID)
	fetchedUser, err := store.UserRepository.FindByID(ctx, db, user1.ID)
	if err != nil {
		log.Fatalf("Find user failed: %v", err)
	}
	log.Printf("Fetched user: %+v", fetchedUser)

	// 2. One-to-Many Demo
	log.Println("\n--- One-to-Many Demo (User -> Posts) ---")
	post1 := &Post{UserID: user1.ID, Title: "Repo Post", Content: "Content here", Status: PublishedStatus}
	if err := store.PostRepository.Create(ctx, db, post1); err != nil {
		log.Fatalf("Create post failed: %v", err)
	}
	log.Printf("Created post: %s", post1.ID)
	userPosts, err := store.PostRepository.FindByUserID(ctx, db, user1.ID)
	if err != nil {
		log.Fatalf("Find posts failed: %v", err)
	}
	log.Printf("Found %d posts for user %s", len(userPosts), user1.ID)

	// 3. Many-to-Many Demo
	log.Println("\n--- Many-to-Many Demo (User <-> Roles) ---")
	adminRole, _ := store.RoleRepository.FindOrCreateByName(ctx, db, AdminRole)
	userRole, _ := store.RoleRepository.FindOrCreateByName(ctx, db, UserRole)
	store.UserRepository.AssignRole(ctx, db, user1.ID, adminRole.ID)
	store.UserRepository.AssignRole(ctx, db, user1.ID, userRole.ID)
	log.Println("Assigned roles to user.")
	
	roles, err := store.UserRepository.FindRolesByUserID(ctx, db, user1.ID)
	if err != nil {
		log.Fatalf("Find roles failed: %v", err)
	}
	log.Printf("User %s has roles: %+v", user1.ID, roles)

	// 4. Transaction Demo
	log.Println("\n--- Transaction Demo ---")
	err = store.WithTransaction(ctx, func(q Querier) error {
		userTx := &User{Email: "tx.user@example.com", PasswordHash: "tx_hash", IsActive: true}
		if err := store.UserRepository.Create(ctx, q, userTx); err != nil {
			return err
		}
		// Simulate a failure
		return fmt.Errorf("intentional rollback")
	})
	if err != nil {
		log.Printf("Transaction rolled back as expected: %v", err)
	}
	
	// 5. Query Filter Demo
	log.Println("\n--- Query Filter Demo ---")
	isActive := true
	emailPattern := "repo.user%"
	filter := UserFilter{IsActive: &isActive, EmailLike: &emailPattern}
	filteredUsers, err := store.UserRepository.FindByFilter(ctx, db, filter)
	if err != nil {
		log.Fatalf("Filter users failed: %v", err)
	}
	log.Printf("Found %d users via filter: %+v", len(filteredUsers), filteredUsers)
}