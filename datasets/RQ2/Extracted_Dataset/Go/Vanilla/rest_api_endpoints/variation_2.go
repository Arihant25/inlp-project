package main

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// --- Domain Model ---

type UserRole string

const (
	ADMIN UserRole = "ADMIN"
	USER  UserRole = "USER"
)

type User struct {
	ID           string    `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	Role         UserRole  `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

// --- Data Layer (Repository) ---

type UserRepository struct {
	mu    sync.RWMutex
	users map[string]User
}

func NewUserRepository() *UserRepository {
	return &UserRepository{
		users: make(map[string]User),
	}
}

func (r *UserRepository) Create(user User) (User, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	for _, u := range r.users {
		if u.Email == user.Email {
			return User{}, fmt.Errorf("email '%s' already exists", user.Email)
		}
	}
	r.users[user.ID] = user
	return user, nil
}

func (r *UserRepository) FindByID(id string) (User, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	user, found := r.users[id]
	return user, found
}

func (r *UserRepository) FindAll() []User {
	r.mu.RLock()
	defer r.mu.RUnlock()
	users := make([]User, 0, len(r.users))
	for _, u := range r.users {
		users = append(users, u)
	}
	return users
}

func (r *UserRepository) Update(id string, user User) (User, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, found := r.users[id]; !found {
		return User{}, false
	}
	user.ID = id // Ensure ID is not changed
	r.users[id] = user
	return user, true
}

func (r *UserRepository) Delete(id string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, found := r.users[id]; !found {
		return false
	}
	delete(r.users, id)
	return true
}

// --- Service/Handler Layer ---

type UserApiServer struct {
	repo *UserRepository
}

func NewUserApiServer(repo *UserRepository) *UserApiServer {
	return &UserApiServer{repo: repo}
}

func (s *UserApiServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/users/")
	
	if path == "" && r.Method == http.MethodGet {
		s.handleListUsers(w, r)
		return
	}
	if path == "" && r.Method == http.MethodPost {
		s.handleCreateUser(w, r)
		return
	}

	id := path
	switch r.Method {
	case http.MethodGet:
		s.handleGetUser(w, r, id)
	case http.MethodPut, http.MethodPatch:
		s.handleUpdateUser(w, r, id)
	case http.MethodDelete:
		s.handleDeleteUser(w, r, id)
	default:
		s.writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "Method not allowed"})
	}
}

func (s *UserApiServer) handleCreateUser(w http.ResponseWriter, r *http.Request) {
	var payload struct {
		Email    string   `json:"email"`
		Password string   `json:"password"`
		Role     UserRole `json:"role"`
	}
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		s.writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Invalid JSON body"})
		return
	}
	if payload.Role == "" {
		payload.Role = USER
	}

	uuid, _ := generateUUID()
	newUser := User{
		ID:           uuid,
		Email:        payload.Email,
		PasswordHash: "hashed_" + payload.Password, // Use bcrypt in production
		Role:         payload.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}

	createdUser, err := s.repo.Create(newUser)
	if err != nil {
		s.writeJSON(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	s.writeJSON(w, http.StatusCreated, createdUser)
}

func (s *UserApiServer) handleGetUser(w http.ResponseWriter, r *http.Request, id string) {
	user, found := s.repo.FindByID(id)
	if !found {
		s.writeJSON(w, http.StatusNotFound, map[string]string{"error": "User not found"})
		return
	}
	s.writeJSON(w, http.StatusOK, user)
}

func (s *UserApiServer) handleListUsers(w http.ResponseWriter, r *http.Request) {
	allUsers := s.repo.FindAll()
	query := r.URL.Query()

	// Filter
	var filteredUsers []User
	roleFilter := query.Get("role")
	activeFilter := query.Get("is_active")
	for _, u := range allUsers {
		if roleFilter != "" && string(u.Role) != roleFilter {
			continue
		}
		if activeFilter != "" {
			isActive, err := strconv.ParseBool(activeFilter)
			if err == nil && u.IsActive != isActive {
				continue
			}
		}
		filteredUsers = append(filteredUsers, u)
	}

	// Sort
	sort.Slice(filteredUsers, func(i, j int) bool {
		return filteredUsers[i].CreatedAt.Before(filteredUsers[j].CreatedAt)
	})

	// Paginate
	page, _ := strconv.Atoi(query.Get("page"))
	if page < 1 { page = 1 }
	limit, _ := strconv.Atoi(query.Get("limit"))
	if limit <= 0 { limit = 10 }
	start := (page - 1) * limit
	end := start + limit
	if start >= len(filteredUsers) {
		s.writeJSON(w, http.StatusOK, []User{})
		return
	}
	if end > len(filteredUsers) {
		end = len(filteredUsers)
	}
	s.writeJSON(w, http.StatusOK, filteredUsers[start:end])
}

func (s *UserApiServer) handleUpdateUser(w http.ResponseWriter, r *http.Request, id string) {
	existingUser, found := s.repo.FindByID(id)
	if !found {
		s.writeJSON(w, http.StatusNotFound, map[string]string{"error": "User not found"})
		return
	}

	var payload struct {
		Email    *string   `json:"email"`
		Role     *UserRole `json:"role"`
		IsActive *bool     `json:"is_active"`
	}
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		s.writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Invalid JSON body"})
		return
	}

	if payload.Email != nil { existingUser.Email = *payload.Email }
	if payload.Role != nil { existingUser.Role = *payload.Role }
	if payload.IsActive != nil { existingUser.IsActive = *payload.IsActive }

	updatedUser, _ := s.repo.Update(id, existingUser)
	s.writeJSON(w, http.StatusOK, updatedUser)
}

func (s *UserApiServer) handleDeleteUser(w http.ResponseWriter, r *http.Request, id string) {
	if !s.repo.Delete(id) {
		s.writeJSON(w, http.StatusNotFound, map[string]string{"error": "User not found"})
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *UserApiServer) writeJSON(w http.ResponseWriter, status int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Printf("Failed to write JSON response: %v", err)
	}
}

// --- Utilities & Main ---

func generateUUID() (string, error) {
	bytes := make([]byte, 16)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	return fmt.Sprintf("%x-%x-%x-%x-%x", bytes[0:4], bytes[4:6], bytes[6:8], bytes[8:10], bytes[10:]), nil
}

func main() {
	repo := NewUserRepository()
	// Seed data
	id1, _ := generateUUID()
	repo.Create(User{ID: id1, Email: "admin@example.com", PasswordHash: "...", Role: ADMIN, IsActive: true, CreatedAt: time.Now().UTC().Add(-time.Hour)})
	id2, _ := generateUUID()
	repo.Create(User{ID: id2, Email: "user1@example.com", PasswordHash: "...", Role: USER, IsActive: true, CreatedAt: time.Now().UTC()})
	id3, _ := generateUUID()
	repo.Create(User{ID: id3, Email: "user2@example.com", PasswordHash: "...", Role: USER, IsActive: false, CreatedAt: time.Now().UTC().Add(time.Hour)})

	server := NewUserApiServer(repo)
	http.Handle("/users/", server)

	log.Println("Server starting on port 8080")
	if err := http.ListenAndServe(":8080", nil); err != nil {
		log.Fatalf("Server failed to start: %v", err)
	}
}