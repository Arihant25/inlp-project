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
	ROLE_ADMIN UserRole = "ADMIN"
	ROLE_USER  UserRole = "USER"
)

type User struct {
	Id          string    `json:"id"`
	Email       string    `json:"email"`
	Password    string    `json:"-"` // Internal only
	Role        UserRole  `json:"role"`
	IsActive    bool      `json:"is_active"`
	CreatedAt   time.Time `json:"created_at"`
}

// --- Data Persistence Layer ---
type UserDataStore struct {
	lock  sync.RWMutex
	users map[string]*User
}

func NewUserDataStore() *UserDataStore {
	return &UserDataStore{users: make(map[string]*User)}
}

func (db *UserDataStore) Add(user *User) {
	db.lock.Lock()
	defer db.lock.Unlock()
	db.users[user.Id] = user
}

func (db *UserDataStore) Get(id string) (*User, bool) {
	db.lock.RLock()
	defer db.lock.RUnlock()
	user, ok := db.users[id]
	return user, ok
}

func (db *UserDataStore) GetAll() []*User {
	db.lock.RLock()
	defer db.lock.RUnlock()
	list := make([]*User, 0, len(db.users))
	for _, u := range db.users {
		list = append(list, u)
	}
	return list
}

func (db *UserDataStore) Remove(id string) {
	db.lock.Lock()
	defer db.lock.Unlock()
	delete(db.users, id)
}

// --- Resource/Controller Layer ---
type UserResource struct {
	db *UserDataStore
}

func NewUserResource(db *UserDataStore) *UserResource {
	return &UserResource{db: db}
}

// Handles /users
func (ur *UserResource) handleCollection(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		ur.listUsers(w, r)
	case http.MethodPost:
		ur.createUser(w, r)
	default:
		ur.jsonError(w, http.StatusText(http.StatusMethodNotAllowed), http.StatusMethodNotAllowed)
	}
}

// Handles /users/{id}
func (ur *UserResource) handleItem(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/users/")
	if id == "" {
		ur.jsonError(w, "Not Found", http.StatusNotFound)
		return
	}

	switch r.Method {
	case http.MethodGet:
		ur.getUser(w, r, id)
	case http.MethodPut, http.MethodPatch:
		ur.updateUser(w, r, id)
	case http.MethodDelete:
		ur.deleteUser(w, r, id)
	default:
		ur.jsonError(w, http.StatusText(http.StatusMethodNotAllowed), http.StatusMethodNotAllowed)
	}
}

func (ur *UserResource) createUser(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Email    string   `json:"email"`
		Password string   `json:"password"`
		Role     UserRole `json:"role"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		ur.jsonError(w, "Bad request body", http.StatusBadRequest)
		return
	}
	if body.Role == "" {
		body.Role = ROLE_USER
	}

	id, _ := generateUUID()
	user := &User{
		Id:        id,
		Email:     body.Email,
		Password:  "hashed:" + body.Password, // Use bcrypt
		Role:      body.Role,
		IsActive:  true,
		CreatedAt: time.Now().UTC(),
	}
	ur.db.Add(user)
	ur.writeJSON(w, http.StatusCreated, user)
}

func (ur *UserResource) listUsers(w http.ResponseWriter, r *http.Request) {
	allUsers := ur.db.GetAll()
	
	// Filtering
	q := r.URL.Query()
	var result []*User
	for _, u := range allUsers {
		if role := q.Get("role"); role != "" && string(u.Role) != role {
			continue
		}
		if active := q.Get("is_active"); active != "" {
			isActive, _ := strconv.ParseBool(active)
			if u.IsActive != isActive {
				continue
			}
		}
		result = append(result, u)
	}

	// Sorting
	sort.Slice(result, func(i, j int) bool { return result[i].CreatedAt.Before(result[j].CreatedAt) })

	// Pagination
	page, _ := strconv.Atoi(q.Get("page"))
	if page < 1 { page = 1 }
	limit, _ := strconv.Atoi(q.Get("limit"))
	if limit <= 0 { limit = 10 }
	start := (page - 1) * limit
	if start >= len(result) {
		ur.writeJSON(w, http.StatusOK, []*User{})
		return
	}
	end := start + limit
	if end > len(result) {
		end = len(result)
	}
	ur.writeJSON(w, http.StatusOK, result[start:end])
}

func (ur *UserResource) getUser(w http.ResponseWriter, r *http.Request, id string) {
	user, ok := ur.db.Get(id)
	if !ok {
		ur.jsonError(w, "User not found", http.StatusNotFound)
		return
	}
	ur.writeJSON(w, http.StatusOK, user)
}

func (ur *UserResource) updateUser(w http.ResponseWriter, r *http.Request, id string) {
	user, ok := ur.db.Get(id)
	if !ok {
		ur.jsonError(w, "User not found", http.StatusNotFound)
		return
	}

	var body map[string]interface{}
	json.NewDecoder(r.Body).Decode(&body)

	if email, ok := body["email"].(string); ok {
		user.Email = email
	}
	if role, ok := body["role"].(string); ok {
		user.Role = UserRole(role)
	}
	if isActive, ok := body["is_active"].(bool); ok {
		user.IsActive = isActive
	}
	
	ur.db.Add(user) // Add acts as an upsert
	ur.writeJSON(w, http.StatusOK, user)
}

func (ur *UserResource) deleteUser(w http.ResponseWriter, r *http.Request, id string) {
	if _, ok := ur.db.Get(id); !ok {
		ur.jsonError(w, "User not found", http.StatusNotFound)
		return
	}
	ur.db.Remove(id)
	w.WriteHeader(http.StatusNoContent)
}

// --- JSON Helpers ---
func (ur *UserResource) jsonError(w http.ResponseWriter, message string, code int) {
	ur.writeJSON(w, code, map[string]string{"error": message})
}

func (ur *UserResource) writeJSON(w http.ResponseWriter, code int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(data)
}

// --- Util ---
func generateUUID() (string, error) {
	b := make([]byte, 16)
	_, err := rand.Read(b)
	if err != nil { return "", err }
	return fmt.Sprintf("%X-%X-%X-%X-%X", b[0:4], b[4:6], b[6:8], b[8:10], b[10:]), nil
}

// --- Main ---
func main() {
	db := NewUserDataStore()
	// Seed
	id1, _ := generateUUID()
	db.Add(&User{Id: id1, Email: "admin@example.com", Role: ROLE_ADMIN, IsActive: true, CreatedAt: time.Now().UTC().Add(-time.Hour)})
	id2, _ := generateUUID()
	db.Add(&User{Id: id2, Email: "user@example.com", Role: ROLE_USER, IsActive: false, CreatedAt: time.Now().UTC()})

	resource := NewUserResource(db)
	mux := http.NewServeMux()
	mux.HandleFunc("/users", resource.handleCollection)
	mux.HandleFunc("/users/", resource.handleItem)

	log.Println("Starting RESTful resource server on :8080")
	if err := http.ListenAndServe(":8080", mux); err != nil {
		log.Fatal(err)
	}
}