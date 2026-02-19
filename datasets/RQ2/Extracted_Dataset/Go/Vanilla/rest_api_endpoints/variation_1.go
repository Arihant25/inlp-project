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

type Role string

const (
	RoleAdmin Role = "ADMIN"
	RoleUser  Role = "USER"
)

type User struct {
	ID           string    `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"` // Never expose password hash
	Role         Role      `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

// --- In-Memory Storage ---

var (
	userStore = make(map[string]User)
	storeLock = sync.RWMutex{}
)

// --- Utility Functions ---

func newUUID() (string, error) {
	b := make([]byte, 16)
	_, err := rand.Read(b)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:]), nil
}

func respondWithError(w http.ResponseWriter, code int, message string) {
	respondWithJSON(w, code, map[string]string{"error": message})
}

func respondWithJSON(w http.ResponseWriter, code int, payload interface{}) {
	response, err := json.Marshal(payload)
	if err != nil {
		log.Printf("Error marshalling JSON: %v", err)
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte(`{"error": "Internal Server Error"}`))
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	w.Write(response)
}

// --- Request DTOs ---

type CreateUserRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
	Role     Role   `json:"role"`
}

type UpdateUserRequest struct {
	Email    *string `json:"email,omitempty"`
	Role     *Role   `json:"role,omitempty"`
	IsActive *bool   `json:"is_active,omitempty"`
}

// --- Main Handler ---

func usersHandler(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/users")
	path = strings.Trim(path, "/")
	parts := strings.Split(path, "/")
	id := ""
	if len(parts) > 0 && parts[0] != "" {
		id = parts[0]
	}

	switch r.Method {
	case http.MethodGet:
		if id != "" {
			getUserByID(w, r, id)
		} else {
			listUsers(w, r)
		}
	case http.MethodPost:
		if id == "" {
			createUser(w, r)
		} else {
			respondWithError(w, http.StatusMethodNotAllowed, "Method Not Allowed")
		}
	case http.MethodPut, http.MethodPatch:
		if id != "" {
			updateUser(w, r, id)
		} else {
			respondWithError(w, http.StatusMethodNotAllowed, "Method Not Allowed")
		}
	case http.MethodDelete:
		if id != "" {
			deleteUser(w, r, id)
		} else {
			respondWithError(w, http.StatusMethodNotAllowed, "Method Not Allowed")
		}
	default:
		respondWithError(w, http.StatusMethodNotAllowed, "Method Not Allowed")
	}
}

// --- CRUD Logic Functions ---

func createUser(w http.ResponseWriter, r *http.Request) {
	var req CreateUserRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		respondWithError(w, http.StatusBadRequest, "Invalid request payload")
		return
	}

	if req.Email == "" || req.Password == "" {
		respondWithError(w, http.StatusBadRequest, "Email and password are required")
		return
	}

	uuid, err := newUUID()
	if err != nil {
		respondWithError(w, http.StatusInternalServerError, "Could not generate user ID")
		return
	}

	newUser := User{
		ID:           uuid,
		Email:        req.Email,
		PasswordHash: "hashed_" + req.Password, // In a real app, use bcrypt
		Role:         req.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}
	if newUser.Role == "" {
		newUser.Role = RoleUser
	}

	storeLock.Lock()
	defer storeLock.Unlock()
	for _, u := range userStore {
		if u.Email == newUser.Email {
			respondWithError(w, http.StatusConflict, "Email already exists")
			return
		}
	}
	userStore[newUser.ID] = newUser

	respondWithJSON(w, http.StatusCreated, newUser)
}

func getUserByID(w http.ResponseWriter, r *http.Request, id string) {
	storeLock.RLock()
	defer storeLock.RUnlock()

	user, ok := userStore[id]
	if !ok {
		respondWithError(w, http.StatusNotFound, "User not found")
		return
	}
	respondWithJSON(w, http.StatusOK, user)
}

func listUsers(w http.ResponseWriter, r *http.Request) {
	storeLock.RLock()
	allUsers := make([]User, 0, len(userStore))
	for _, user := range userStore {
		allUsers = append(allUsers, user)
	}
	storeLock.RUnlock()

	// Filtering
	query := r.URL.Query()
	filteredUsers := []User{}
	roleFilter := query.Get("role")
	isActiveFilter := query.Get("is_active")

	for _, user := range allUsers {
		match := true
		if roleFilter != "" && string(user.Role) != roleFilter {
			match = false
		}
		if isActiveFilter != "" {
			isActive, err := strconv.ParseBool(isActiveFilter)
			if err == nil && user.IsActive != isActive {
				match = false
			}
		}
		if match {
			filteredUsers = append(filteredUsers, user)
		}
	}

	// Sort by creation date for consistent pagination
	sort.Slice(filteredUsers, func(i, j int) bool {
		return filteredUsers[i].CreatedAt.Before(filteredUsers[j].CreatedAt)
	})

	// Pagination
	page, _ := strconv.Atoi(query.Get("page"))
	if page < 1 {
		page = 1
	}
	limit, _ := strconv.Atoi(query.Get("limit"))
	if limit <= 0 {
		limit = 10
	}
	offset := (page - 1) * limit
	if offset >= len(filteredUsers) {
		respondWithJSON(w, http.StatusOK, []User{})
		return
	}
	end := offset + limit
	if end > len(filteredUsers) {
		end = len(filteredUsers)
	}

	paginatedUsers := filteredUsers[offset:end]
	respondWithJSON(w, http.StatusOK, paginatedUsers)
}

func updateUser(w http.ResponseWriter, r *http.Request, id string) {
	storeLock.Lock()
	defer storeLock.Unlock()

	user, ok := userStore[id]
	if !ok {
		respondWithError(w, http.StatusNotFound, "User not found")
		return
	}

	var req UpdateUserRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		respondWithError(w, http.StatusBadRequest, "Invalid request payload")
		return
	}

	if req.Email != nil {
		user.Email = *req.Email
	}
	if req.Role != nil {
		user.Role = *req.Role
	}
	if req.IsActive != nil {
		user.IsActive = *req.IsActive
	}

	userStore[id] = user
	respondWithJSON(w, http.StatusOK, user)
}

func deleteUser(w http.ResponseWriter, r *http.Request, id string) {
	storeLock.Lock()
	defer storeLock.Unlock()

	if _, ok := userStore[id]; !ok {
		respondWithError(w, http.StatusNotFound, "User not found")
		return
	}

	delete(userStore, id)
	w.WriteHeader(http.StatusNoContent)
}

// --- Main Application ---

func main() {
	// Seed data
	id1, _ := newUUID()
	id2, _ := newUUID()
	id3, _ := newUUID()
	userStore[id1] = User{ID: id1, Email: "admin@example.com", PasswordHash: "...", Role: RoleAdmin, IsActive: true, CreatedAt: time.Now().UTC().Add(-time.Hour)}
	userStore[id2] = User{ID: id2, Email: "user1@example.com", PasswordHash: "...", Role: RoleUser, IsActive: true, CreatedAt: time.Now().UTC()}
	userStore[id3] = User{ID: id3, Email: "user2@example.com", PasswordHash: "...", Role: RoleUser, IsActive: false, CreatedAt: time.Now().UTC().Add(time.Hour)}

	http.HandleFunc("/users/", usersHandler)

	log.Println("Starting server on :8080...")
	if err := http.ListenAndServe(":8080", nil); err != nil {
		log.Fatalf("Could not start server: %s\n", err)
	}
}