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
type UserRole string
const (
	ADMIN UserRole = "ADMIN"
	USER  UserRole = "USER"
)

type PostStatus string
const (
	DRAFT     PostStatus = "DRAFT"
	PUBLISHED PostStatus = "PUBLISHED"
)

type User struct {
	Id           string
	Email        string
	PasswordHash string
	IsActive     bool
	CreatedAt    time.Time
}

type Post struct {
	Id      string
	UserId  string
	Title   string
	Content string
	Status  PostStatus
}

// --- UUID Helper ---
func createUUID() (string, error) {
	b := make([]byte, 16)
	_, err := rand.Read(b)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:]), nil
}

// --- Data Access Object (DAO) Layer ---
// A Querier can be a *sql.DB or *sql.Tx
type Querier interface {
	ExecContext(ctx context.Context, query string, args ...interface{}) (sql.Result, error)
	QueryContext(ctx context.Context, query string, args ...interface{}) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...interface{}) *sql.Row
}

type UserDAO struct{}

func (d *UserDAO) Insert(ctx context.Context, q Querier, u *User) error {
	u.Id, _ = createUUID()
	u.CreatedAt = time.Now().UTC()
	stmt := "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)"
	_, err := q.ExecContext(ctx, stmt, u.Id, u.Email, u.PasswordHash, u.IsActive, u.CreatedAt)
	return err
}

func (d *UserDAO) Get(ctx context.Context, q Querier, id string) (*User, error) {
	stmt := "SELECT id, email, password_hash, is_active, created_at FROM users WHERE id = ?"
	row := q.QueryRowContext(ctx, stmt, id)
	var u User
	err := row.Scan(&u.Id, &u.Email, &u.PasswordHash, &u.IsActive, &u.CreatedAt)
	return &u, err
}

type PostDAO struct{}

func (d *PostDAO) Insert(ctx context.Context, q Querier, p *Post) error {
	p.Id, _ = createUUID()
	stmt := "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)"
	_, err := q.ExecContext(ctx, stmt, p.Id, p.UserId, p.Title, p.Content, p.Status)
	return err
}

type RoleDAO struct{}

func (d *RoleDAO) GetOrCreate(ctx context.Context, q Querier, name UserRole) (int64, error) {
	var id int64
	err := q.QueryRowContext(ctx, "SELECT id FROM roles WHERE name = ?", name).Scan(&id)
	if err == sql.ErrNoRows {
		res, err := q.ExecContext(ctx, "INSERT INTO roles (name) VALUES (?)", name)
		if err != nil { return 0, err }
		id, err = res.LastInsertId()
		return id, err
	}
	return id, err
}

func (d *RoleDAO) AssignToUser(ctx context.Context, q Querier, userId string, roleId int64) error {
	_, err := q.ExecContext(ctx, "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", userId, roleId)
	return err
}

// --- Service Layer ---

type DBManager struct {
	conn *sql.DB
}

func NewDBManager(conn *sql.DB) *DBManager {
	return &DBManager{conn: conn}
}

func (m *DBManager) ExecuteInTransaction(ctx context.Context, fn func(Querier) error) error {
	tx, err := m.conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if err := fn(tx); err != nil {
		return err
	}
	return tx.Commit()
}

type UserService struct {
	dbManager *DBManager
	userDAO   *UserDAO
	roleDAO   *RoleDAO
}

func NewUserService(dbm *DBManager) *UserService {
	return &UserService{
		dbManager: dbm,
		userDAO:   &UserDAO{},
		roleDAO:   &RoleDAO{},
	}
}

func (s *UserService) RegisterUser(ctx context.Context, email, password string) (*User, error) {
	user := &User{
		Email:        email,
		PasswordHash: fmt.Sprintf("hashed_%s", password), // Dummy hash
		IsActive:     true,
	}

	err := s.dbManager.ExecuteInTransaction(ctx, func(q Querier) error {
		if err := s.userDAO.Insert(ctx, q, user); err != nil {
			return err
		}
		defaultRoleID, err := s.roleDAO.GetOrCreate(ctx, q, USER)
		if err != nil {
			return err
		}
		return s.roleDAO.AssignToUser(ctx, q, user.Id, defaultRoleID)
	})

	if err != nil {
		return nil, err
	}
	return user, nil
}

func (s *UserService) GetUser(ctx context.Context, id string) (*User, error) {
	return s.userDAO.Get(ctx, s.dbManager.conn, id)
}

// --- Migration Management ---
func setupDatabase(db *sql.DB) {
	log.Println("Setting up database schema...")
	schema := `
	CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, email TEXT UNIQUE, password_hash TEXT, is_active BOOLEAN, created_at TIMESTAMP);
	CREATE TABLE IF NOT EXISTS posts (id TEXT PRIMARY KEY, user_id TEXT, title TEXT, content TEXT, status TEXT, FOREIGN KEY(user_id) REFERENCES users(id));
	CREATE TABLE IF NOT EXISTS roles (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE);
	CREATE TABLE IF NOT EXISTS user_roles (user_id TEXT, role_id INTEGER, PRIMARY KEY (user_id, role_id), FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(role_id) REFERENCES roles(id));
	`
	_, err := db.Exec(schema)
	if err != nil {
		log.Fatalf("Failed to apply schema: %v", err)
	}
	log.Println("Schema setup complete.")
}

func main() {
	ctx := context.Background()
	db, err := sql.Open("sqlite3", ":memory:")
	if err != nil {
		log.Fatalf("Cannot open database: %v", err)
	}
	defer db.Close()

	setupDatabase(db)

	dbManager := NewDBManager(db)
	userService := NewUserService(dbManager)

	// 1. CRUD and M-M Demo via Service Layer
	log.Println("\n--- Service Layer Demo (CRUD, M-M, Transaction) ---")
	newUser, err := userService.RegisterUser(ctx, "service.user@example.com", "password123")
	if err != nil {
		log.Fatalf("Failed to register user: %v", err)
	}
	log.Printf("Registered user via service: ID %s", newUser.Id)

	fetchedUser, err := userService.GetUser(ctx, newUser.Id)
	if err != nil {
		log.Fatalf("Failed to fetch user: %v", err)
	}
	log.Printf("Fetched user: %+v", fetchedUser)

	// 2. One-to-Many Demo
	log.Println("\n--- One-to-Many Demo (User -> Post) ---")
	postDAO := &PostDAO{}
	newPost := &Post{UserId: newUser.Id, Title: "Service Post", Content: "Content from service", Status: PUBLISHED}
	if err := postDAO.Insert(ctx, db, newPost); err != nil {
		log.Fatalf("Failed to create post: %v", err)
	}
	log.Printf("Created post %s for user %s", newPost.Id, newUser.Id)

	// 3. Transaction Rollback Demo
	log.Println("\n--- Transaction Rollback Demo ---")
	err = dbManager.ExecuteInTransaction(ctx, func(q Querier) error {
		userDAO := &UserDAO{}
		u := &User{Email: "rollback@test.com", PasswordHash: "123", IsActive: false}
		if err := userDAO.Insert(ctx, q, u); err != nil {
			return err
		}
		log.Printf("User %s created inside transaction...", u.Id)
		return fmt.Errorf("simulating error for rollback")
	})
	if err != nil {
		log.Printf("Transaction correctly rolled back: %v", err)
	} else {
		log.Println("Transaction was not rolled back, which is an error.")
	}

	// 4. Query Building with Filters
	log.Println("\n--- Query Filter Demo ---")
	var foundUsers []*User
	query := "SELECT id, email, is_active FROM users WHERE is_active = ? AND email LIKE ?"
	rows, err := db.QueryContext(ctx, query, true, "%service.user%")
	if err != nil {
		log.Fatalf("Filter query failed: %v", err)
	}
	defer rows.Close()
	for rows.Next() {
		var u User
		if err := rows.Scan(&u.Id, &u.Email, &u.IsActive); err != nil {
			log.Fatalf("Scan failed: %v", err)
		}
		foundUsers = append(foundUsers, &u)
	}
	log.Printf("Found %d users with filter: %+v", len(foundUsers), foundUsers)
}