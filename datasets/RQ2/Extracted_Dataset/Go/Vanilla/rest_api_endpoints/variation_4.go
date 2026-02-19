package main

import (
	"context"
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
	AdminRole Role = "ADMIN"
	UserRole  Role = "USER"
)

type User struct {
	ID           string    `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	Role         Role      `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

// --- Data Store ---
var (
	dataStore = struct {
		sync.RWMutex
		m map[string]User
	}{m: make(map[string]User)}
)

// --- Context Key ---
type contextKey string
const userContextKey = contextKey("user")

// --- Middleware-like Functions ---
func userContextMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path := strings.TrimPrefix(r.URL.Path, "/users/")
		userID := strings.Split(path, "/")[0]

		if userID == "" {
			http.Error(w, "Not Found", http.StatusNotFound)
			return
		}

		dataStore.RLock()
		user, ok := dataStore.m[userID]
		dataStore.RUnlock()

		if !ok {
			http.Error(w, "User not found", http.StatusNotFound)
			return
		}

		ctx := context.WithValue(r.Context(), userContextKey, user)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// --- Handlers ---
func masterUserHandler(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/users/")
	
	// Collection endpoint: /users
	if path == "" {
		switch r.Method {
		case http.MethodGet:
			listUsersHandler(w, r)
		case http.MethodPost:
			createUserHandler(w, r)
		default:
			writeError(w, http.StatusMethodNotAllowed, "Method not allowed for this endpoint")
		}
		return
	}

	// Item endpoint: /users/{id}
	// The middleware will handle ID parsing and fetching
	var itemHandler http.Handler
	switch r.Method {
	case http.MethodGet:
		itemHandler = http.HandlerFunc(getUserHandler)
	case http.MethodPut, http.MethodPatch:
		itemHandler = http.HandlerFunc(updateUserHandler)
	case http.MethodDelete:
		itemHandler = http.HandlerFunc(deleteUserHandler)
	default:
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed for this endpoint")
		return
	}
	
	userContextMiddleware(itemHandler).ServeHTTP(w, r)
}

func createUserHandler(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
		Role     Role   `json:"role"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "Invalid request body")
		return
	}
	if req.Role == "" {
		req.Role = UserRole
	}

	id, _ := newUUID()
	newUser := User{
		ID:           id,
		Email:        req.Email,
		PasswordHash: "secret", // Use bcrypt in production
		Role:         req.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}

	dataStore.Lock()
	dataStore.m[newUser.ID] = newUser
	dataStore.Unlock()

	writeJSON(w, http.StatusCreated, newUser)
}

func listUsersHandler(w http.ResponseWriter, r *http.Request) {
	dataStore.RLock()
	allUsers := make([]User, 0, len(dataStore.m))
	for _, u := range dataStore.m {
		allUsers = append(allUsers, u)
	}
	dataStore.RUnlock()

	// Filter
	query := r.URL.Query()
	var filtered []User
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
		filtered = append(filtered, u)
	}

	// Sort
	sort.Slice(filtered, func(i, j int) bool { return filtered[i].CreatedAt.Before(filtered[j].CreatedAt) })

	// Paginate
	page, _ := strconv.Atoi(query.Get("page"))
	if page < 1 { page = 1 }
	limit, _ := strconv.Atoi(query.Get("limit"))
	if limit <= 0 { limit = 10 }
	start := (page - 1) * limit
	if start >= len(filtered) {
		writeJSON(w, http.StatusOK, []User{})
		return
	}
	end := start + limit
	if end > len(filtered) {
		end = len(filtered)
	}
	writeJSON(w, http.StatusOK, filtered[start:end])
}

func getUserHandler(w http.ResponseWriter, r *http.Request) {
	user, ok := r.Context().Value(userContextKey).(User)
	if !ok {
		// This should not happen if middleware is correct
		writeError(w, http.StatusInternalServerError, "Could not retrieve user from context")
		return
	}
	writeJSON(w, http.StatusOK, user)
}

func updateUserHandler(w http.ResponseWriter, r *http.Request) {
	user, _ := r.Context().Value(userContextKey).(User)
	
	var req struct {
		Email    *string `json:"email"`
		Role     *Role   `json:"role"`
		IsActive *bool   `json:"is_active"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "Invalid request body")
		return
	}

	if req.Email != nil { user.Email = *req.Email }
	if req.Role != nil { user.Role = *req.Role }
	if req.IsActive != nil { user.IsActive = *req.IsActive }

	dataStore.Lock()
	dataStore.m[user.ID] = user
	dataStore.Unlock()

	writeJSON(w, http.StatusOK, user)
}

func deleteUserHandler(w http.ResponseWriter, r *http.Request) {
	user, _ := r.Context().Value(userContextKey).(User)
	
	dataStore.Lock()
	delete(dataStore.m, user.ID)
	dataStore.Unlock()

	w.WriteHeader(http.StatusNoContent)
}

// --- Helper Functions ---
func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if data != nil {
		json.NewEncoder(w).Encode(data)
	}
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"message": message})
}

func newUUID() (string, error) {
	b := make([]byte, 16)
	_, err := rand.Read(b)
	if err != nil { return "", err }
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:]), nil
}

// --- Main Entrypoint ---
func main() {
	// Seed data
	id1, _ := newUUID()
	dataStore.m[id1] = User{ID: id1, Email: "admin@example.com", Role: AdminRole, IsActive: true, CreatedAt: time.Now().UTC().Add(-time.Hour)}
	id2, _ := newUUID()
	dataStore.m[id2] = User{ID: id2, Email: "user@example.com", Role: UserRole, IsActive: true, CreatedAt: time.Now().UTC()}

	http.HandleFunc("/users/", masterUserHandler)

	log.Println("Context-driven server starting on http://localhost:8080")
	err := http.ListenAndServe(":8080", nil)
	if err != nil {
		log.Fatalf("Server failed: %v", err)
	}
}