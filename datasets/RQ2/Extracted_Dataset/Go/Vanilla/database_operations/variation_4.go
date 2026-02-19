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
type Role string
const (
	ADMIN_ROLE Role = "ADMIN"
	USER_ROLE  Role = "USER"
)

type PostStatus string
const (
	DRAFT_STATUS     PostStatus = "DRAFT"
	PUBLISHED_STATUS PostStatus = "PUBLISHED"
)

// --- UUID Helper ---
func generateNewUUID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}

// --- CQRS: Commands ---

type CreateUserCommand struct {
	ID           string
	Email        string
	PasswordHash string
	IsActive     bool
}

type CreatePostCommand struct {
	ID      string
	UserID  string
	Title   string
	Content string
	Status  PostStatus
}

type AssignRoleToUserCommand struct {
	UserID string
	Role   Role
}

// --- CQRS: Command Handlers ---

type CommandHandler struct {
	db *sql.DB
}

func NewCommandHandler(db *sql.DB) *CommandHandler {
	return &CommandHandler{db: db}
}

func (h *CommandHandler) HandleCreateUser(ctx context.Context, cmd *CreateUserCommand) error {
	tx, err := h.db.BeginTx(ctx, nil)
	if err != nil { return err }
	defer tx.Rollback()

	cmd.ID = generateNewUUID()
	stmt := "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)"
	_, err = tx.ExecContext(ctx, stmt, cmd.ID, cmd.Email, cmd.PasswordHash, cmd.IsActive, time.Now().UTC())
	if err != nil { return err }

	return tx.Commit()
}

func (h *CommandHandler) HandleCreatePost(ctx context.Context, cmd *CreatePostCommand) error {
	cmd.ID = generateNewUUID()
	stmt := "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)"
	_, err := h.db.ExecContext(ctx, stmt, cmd.ID, cmd.UserID, cmd.Title, cmd.Content, cmd.Status)
	return err
}

func (h *CommandHandler) HandleAssignRoleToUser(ctx context.Context, cmd *AssignRoleToUserCommand) error {
	tx, err := h.db.BeginTx(ctx, nil)
	if err != nil { return err }
	defer tx.Rollback()

	var roleID int64
	err = tx.QueryRowContext(ctx, "SELECT id FROM roles WHERE name = ?", cmd.Role).Scan(&roleID)
	if err == sql.ErrNoRows {
		res, err := tx.ExecContext(ctx, "INSERT INTO roles (name) VALUES (?)", cmd.Role)
		if err != nil { return err }
		roleID, _ = res.LastInsertId()
	} else if err != nil {
		return err
	}

	stmt := "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)"
	_, err = tx.ExecContext(ctx, stmt, cmd.UserID, roleID)
	if err != nil { return err }

	return tx.Commit()
}

// --- CQRS: Queries & DTOs ---

type UserDTO struct {
	ID        string
	Email     string
	IsActive  bool
	CreatedAt time.Time
	Posts     []PostDTO
	Roles     []Role
}

type PostDTO struct {
	ID      string
	Title   string
	Status  PostStatus
}

type GetUserByIDQuery struct {
	ID string
}

type FindUsersQuery struct {
	IsActive *bool
	EmailLike *string
}

// --- CQRS: Query Handlers ---

type QueryHandler struct {
	db *sql.DB
}

func NewQueryHandler(db *sql.DB) *QueryHandler {
	return &QueryHandler{db: db}
}

func (h *QueryHandler) HandleGetUserByID(ctx context.Context, query GetUserByIDQuery) (*UserDTO, error) {
	stmt := "SELECT id, email, is_active, created_at FROM users WHERE id = ?"
	row := h.db.QueryRowContext(ctx, stmt, query.ID)
	
	var dto UserDTO
	err := row.Scan(&dto.ID, &dto.Email, &dto.IsActive, &dto.CreatedAt)
	if err != nil {
		return nil, err
	}

	// One-to-many: Get Posts
	postRows, err := h.db.QueryContext(ctx, "SELECT id, title, status FROM posts WHERE user_id = ?", query.ID)
	if err != nil { return nil, err }
	defer postRows.Close()
	for postRows.Next() {
		var p PostDTO
		if err := postRows.Scan(&p.ID, &p.Title, &p.Status); err != nil { return nil, err }
		dto.Posts = append(dto.Posts, p)
	}

	// Many-to-many: Get Roles
	roleRows, err := h.db.QueryContext(ctx, "SELECT r.name FROM roles r JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = ?", query.ID)
	if err != nil { return nil, err }
	defer roleRows.Close()
	for roleRows.Next() {
		var r Role
		if err := roleRows.Scan(&r); err != nil { return nil, err }
		dto.Roles = append(dto.Roles, r)
	}

	return &dto, nil
}

func (h *QueryHandler) HandleFindUsers(ctx context.Context, query FindUsersQuery) ([]UserDTO, error) {
	sqlBuilder := strings.Builder{}
	sqlBuilder.WriteString("SELECT id, email, is_active, created_at FROM users WHERE 1=1")
	var args []interface{}

	if query.IsActive != nil {
		sqlBuilder.WriteString(" AND is_active = ?")
		args = append(args, *query.IsActive)
	}
	if query.EmailLike != nil {
		sqlBuilder.WriteString(" AND email LIKE ?")
		args = append(args, *query.EmailLike)
	}

	rows, err := h.db.QueryContext(ctx, sqlBuilder.String(), args...)
	if err != nil { return nil, err }
	defer rows.Close()

	var users []UserDTO
	for rows.Next() {
		var u UserDTO
		if err := rows.Scan(&u.ID, &u.Email, &u.IsActive, &u.CreatedAt); err != nil { return nil, err }
		users = append(users, u)
	}
	return users, rows.Err()
}

// --- Migrations ---
func runDatabaseMigrations(db *sql.DB) {
	log.Println("Running migrations...")
	migrationSQL := `
	CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL, is_active BOOLEAN NOT NULL, created_at TIMESTAMP NOT NULL);
	CREATE TABLE IF NOT EXISTS posts (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL, FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE);
	CREATE TABLE IF NOT EXISTS roles (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL);
	CREATE TABLE IF NOT EXISTS user_roles (user_id TEXT NOT NULL, role_id INTEGER NOT NULL, PRIMARY KEY (user_id, role_id), FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE, FOREIGN KEY(role_id) REFERENCES roles(id) ON DELETE CASCADE);
	`
	if _, err := db.Exec(migrationSQL); err != nil {
		log.Fatalf("Migration failed: %v", err)
	}
	log.Println("Migrations successful.")
}

func main() {
	ctx := context.Background()
	db, err := sql.Open("sqlite3", "file:cqrs_demo.db?cache=shared&mode=memory")
	if err != nil {
		log.Fatalf("DB open error: %v", err)
	}
	defer db.Close()

	runDatabaseMigrations(db)

	commandHandler := NewCommandHandler(db)
	queryHandler := NewQueryHandler(db)

	// 1. CRUD Demo (Commands)
	log.Println("\n--- CQRS Command Demo ---")
	createUserCmd := &CreateUserCommand{Email: "cqrs.user@example.com", PasswordHash: "cqrs_hash", IsActive: true}
	if err := commandHandler.HandleCreateUser(ctx, createUserCmd); err != nil {
		log.Fatalf("HandleCreateUser failed: %v", err)
	}
	log.Printf("Created user with ID: %s", createUserCmd.ID)

	// 2. One-to-Many Demo
	createPostCmd := &CreatePostCommand{UserID: createUserCmd.ID, Title: "CQRS Post", Content: "A post created via command", Status: PUBLISHED_STATUS}
	if err := commandHandler.HandleCreatePost(ctx, createPostCmd); err != nil {
		log.Fatalf("HandleCreatePost failed: %v", err)
	}
	log.Printf("Created post with ID: %s", createPostCmd.ID)

	// 3. Many-to-Many Demo
	assignRoleCmd := &AssignRoleToUserCommand{UserID: createUserCmd.ID, Role: ADMIN_ROLE}
	if err := commandHandler.HandleAssignRoleToUser(ctx, assignRoleCmd); err != nil {
		log.Fatalf("HandleAssignRoleToUser failed: %v", err)
	}
	log.Printf("Assigned role '%s' to user %s", assignRoleCmd.Role, assignRoleCmd.UserID)

	// 4. Query Demo
	log.Println("\n--- CQRS Query Demo ---")
	getUserQuery := GetUserByIDQuery{ID: createUserCmd.ID}
	userDTO, err := queryHandler.HandleGetUserByID(ctx, getUserQuery)
	if err != nil {
		log.Fatalf("HandleGetUserByID failed: %v", err)
	}
	log.Printf("Fetched User DTO: ID=%s, Email=%s, Posts=%d, Roles=%v", userDTO.ID, userDTO.Email, len(userDTO.Posts), userDTO.Roles)

	// 5. Query with Filters Demo
	log.Println("\n--- CQRS Filtered Query Demo ---")
	isActive := true
	findUsersQuery := FindUsersQuery{IsActive: &isActive, EmailLike: "%cqrs%"}
	filteredUsers, err := queryHandler.HandleFindUsers(ctx, findUsersQuery)
	if err != nil {
		log.Fatalf("HandleFindUsers failed: %v", err)
	}
	log.Printf("Found %d users via filter query.", len(filteredUsers))
	for _, u := range filteredUsers {
		log.Printf("  - User: %s, %s", u.ID, u.Email)
	}

	// 6. Transaction Rollback is implicit in command handlers if they fail
	log.Println("\n--- Transaction Rollback Demo ---")
	// This command will fail due to UNIQUE constraint on email
	failingCmd := &CreateUserCommand{Email: "cqrs.user@example.com", PasswordHash: "fail_hash", IsActive: false}
	err = commandHandler.HandleCreateUser(ctx, failingCmd)
	if err != nil {
		log.Printf("Command failed as expected, transaction was rolled back: %v", err)
	}
}