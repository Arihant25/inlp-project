use std::collections::HashMap;
use std::ptr;
use std::time::{Duration, Instant, SystemTime};
use std::thread;

// --- Domain Schema ---

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct Uuid(u128);

impl Uuid {
    fn new() -> Self {
        // Simple pseudo-random generator for demonstration
        Uuid(SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap().as_nanos())
    }
}

#[derive(Debug, Clone, Copy)]
enum UserRole { ADMIN, USER }

#[derive(Debug, Clone)]
struct User {
    id: Uuid,
    email: String,
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: SystemTime,
}

#[derive(Debug, Clone, Copy)]
enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug, Clone)]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Data Store (Mock DB) ---

struct Db {
    users: HashMap<Uuid, User>,
    posts: HashMap<Uuid, Post>,
}

impl Db {
    fn new_with_data() -> Self {
        let mut users = HashMap::new();
        let user_id = Uuid::new();
        users.insert(user_id, User {
            id: user_id,
            email: "test@dev.io".to_string(),
            password_hash: "abc".to_string(),
            role: UserRole::USER,
            is_active: true,
            created_at: SystemTime::now(),
        });
        Db { users, posts: HashMap::new() }
    }
}

// --- Caching Implementation (Data-Oriented with `unsafe` for performance) ---

struct CacheItem<V> {
    value: V,
    expires: Instant,
}

struct Node<K, V> {
    key: K,
    item: CacheItem<V>,
    prev: *mut Node<K, V>,
    next: *mut Node<K, V>,
}

// This LRU implementation uses raw pointers for the linked list.
// It's faster but requires `unsafe` blocks and careful memory management.
struct LruCache<K, V> {
    capacity: usize,
    map: HashMap<K, Box<Node<K, V>>>,
    head: *mut Node<K, V>,
    tail: *mut Node<K, V>,
    ttl: Duration,
}

impl<K, V> LruCache<K, V>
where
    K: std::hash::Hash + Eq + Clone,
    V: Clone,
{
    fn new(cap: usize, ttl: Duration) -> Self {
        LruCache {
            capacity: cap,
            map: HashMap::with_capacity(cap),
            head: ptr::null_mut(),
            tail: ptr::null_mut(),
            ttl,
        }
    }

    fn get(&mut self, key: &K) -> Option<V> {
        if !self.map.contains_key(key) {
            return None;
        }
        
        let node_ptr = self.map.get_mut(key).unwrap().as_mut() as *mut _;
        
        // Using unsafe for raw pointer manipulation
        unsafe {
            if Instant::now() > (*node_ptr).item.expires {
                self.remove_node(node_ptr);
                self.map.remove(key);
                return None;
            }
            self.move_to_head(node_ptr);
            Some((*node_ptr).item.value.clone())
        }
    }

    fn set(&mut self, key: K, value: V) {
        if self.map.contains_key(&key) {
            let node_ptr = self.map.get_mut(&key).unwrap().as_mut() as *mut _;
            unsafe {
                (*node_ptr).item = CacheItem { value, expires: Instant::now() + self.ttl };
                self.move_to_head(node_ptr);
            }
        } else {
            if self.map.len() >= self.capacity {
                if !self.tail.is_null() {
                    unsafe {
                        let tail_key = (*self.tail).key.clone();
                        let tail_ptr = self.tail;
                        self.remove_node(tail_ptr);
                        self.map.remove(&tail_key);
                    }
                }
            }
            let mut new_node = Box::new(Node {
                key: key.clone(),
                item: CacheItem { value, expires: Instant::now() + self.ttl },
                prev: ptr::null_mut(),
                next: ptr::null_mut(),
            });

            let node_ptr = new_node.as_mut() as *mut _;
            unsafe { self.move_to_head(node_ptr); }
            self.map.insert(key, new_node);
        }
    }

    fn delete(&mut self, key: &K) {
        if self.map.contains_key(key) {
            let node_ptr = self.map.get_mut(key).unwrap().as_mut() as *mut _;
            unsafe { self.remove_node(node_ptr); }
            self.map.remove(key);
        }
    }

    unsafe fn move_to_head(&mut self, node: *mut Node<K, V>) {
        self.remove_node(node);
        (*node).next = self.head;
        if !self.head.is_null() {
            (*self.head).prev = node;
        }
        self.head = node;
        if self.tail.is_null() {
            self.tail = node;
        }
    }

    unsafe fn remove_node(&mut self, node: *mut Node<K, V>) {
        if !(*node).prev.is_null() {
            (*(*node).prev).next = (*node).next;
        } else {
            self.head = (*node).next;
        }
        if !(*node).next.is_null() {
            (*(*node).next).prev = (*node).prev;
        } else {
            self.tail = (*node).prev;
        }
        (*node).prev = ptr::null_mut();
        (*node).next = ptr::null_mut();
    }
}

impl<K, V> Drop for LruCache<K, V> {
    fn drop(&mut self) {
        // The Box in the map will handle deallocation.
        // We just need to clear it to break cycles if any (not here, but good practice).
        self.map.clear();
        self.head = ptr::null_mut();
        self.tail = ptr::null_mut();
    }
}

// --- Application State & Logic (Functional Style) ---

struct AppState {
    db: Db,
    user_cache: LruCache<Uuid, User>,
    post_cache: LruCache<Uuid, Post>,
}

// Cache-aside read
fn fetch_user<'a>(state: &'a mut AppState, uid: Uuid) -> Option<User> {
    if let Some(user) = state.user_cache.get(&uid) {
        println!("CACHE: HIT for user {}", uid.0);
        return Some(user);
    }
    println!("CACHE: MISS for user {}", uid.0);

    println!("DB: Reading user {}", uid.0);
    thread::sleep(Duration::from_millis(50)); // Simulate latency
    if let Some(user) = state.db.users.get(&uid).cloned() {
        state.user_cache.set(uid, user.clone());
        return Some(user);
    }
    None
}

// Write-through with cache invalidation
fn update_user_email(state: &mut AppState, uid: Uuid, new_email: &str) {
    println!("DB: Updating user {}", uid.0);
    thread::sleep(Duration::from_millis(100)); // Simulate latency
    if let Some(user) = state.db.users.get_mut(&uid) {
        user.email = new_email.to_string();
    }
    
    println!("CACHE: INVALIDATE for user {}", uid.0);
    state.user_cache.delete(&uid);
}

fn main() {
    let mut state = AppState {
        db: Db::new_with_data(),
        user_cache: LruCache::new(10, Duration::from_secs(60)),
        post_cache: LruCache::new(50, Duration::from_secs(30)),
    };

    let user_id = state.db.users.keys().next().unwrap().clone();

    println!("--- First fetch ---");
    fetch_user(&mut state, user_id);

    println!("\n--- Second fetch (should hit cache) ---");
    fetch_user(&mut state, user_id);

    println!("\n--- Updating email ---");
    update_user_email(&mut state, user_id, "updated@dev.io");

    println!("\n--- Third fetch (should miss, then populate cache) ---");
    if let Some(u) = fetch_user(&mut state, user_id) {
        println!("Fetched user with new email: {}", u.email);
    }

    println!("\n--- LRU Eviction Test ---");
    let mut cache = LruCache::new(2, Duration::from_secs(10));
    let (id1, id2, id3) = (Uuid::new(), Uuid::new(), Uuid::new());
    cache.set(id1, "val1");
    cache.set(id2, "val2");
    println!("Cache has id1: {}", cache.get(&id1).is_some());
    cache.set(id3, "val3"); // Evicts id1
    println!("After adding id3, cache has id1: {}", cache.get(&id1).is_some());
    println!("Cache has id2: {}", cache.get(&id2).is_some());
    println!("Cache has id3: {}", cache.get(&id3).is_some());

    println!("\n--- TTL Expiration Test ---");
    let mut cache = LruCache::new(1, Duration::from_millis(20));
    let id_exp = Uuid::new();
    cache.set(id_exp, "short-lived");
    println!("Value is: {:?}", cache.get(&id_exp));
    thread::sleep(Duration::from_millis(30));
    println!("After 30ms, value is: {:?}", cache.get(&id_exp));
}