use std::collections::HashMap;
use std::rc::Rc;
use std::cell::RefCell;
use std::time::{Duration, Instant};
use std::thread;

// --- Domain Schema ---

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Uuid(u128);

impl Uuid {
    pub fn new() -> Self {
        // In a real app, use the `uuid` crate. For this example, we simulate.
        Uuid(rand::random())
    }
}

// A simple random number generator for Uuid simulation without external crates.
mod rand {
    use std::time::{SystemTime, UNIX_EPOCH};
    static mut SEED: u64 = 0;

    pub fn random<T>() -> T where T: From<u64> {
        unsafe {
            if SEED == 0 {
                SEED = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos() as u64;
            }
            SEED = SEED.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            T::from(SEED)
        }
    }
}


#[derive(Debug, Clone, Copy)]
pub enum UserRole {
    ADMIN,
    USER,
}

#[derive(Debug, Clone)]
pub struct User {
    id: Uuid,
    email: String,
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: std::time::SystemTime,
}

#[derive(Debug, Clone, Copy)]
pub enum PostStatus {
    DRAFT,
    PUBLISHED,
}

#[derive(Debug, Clone)]
pub struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Mock Database ---

pub struct Database {
    users: HashMap<Uuid, User>,
    posts: HashMap<Uuid, Post>,
}

impl Database {
    fn new() -> Self {
        let mut users = HashMap::new();
        let mut posts = HashMap::new();

        let user_id = Uuid::new();
        let user = User {
            id: user_id,
            email: "admin@example.com".to_string(),
            password_hash: "hashed_password".to_string(),
            role: UserRole::ADMIN,
            is_active: true,
            created_at: std::time::SystemTime::now(),
        };
        users.insert(user_id, user);

        let post_id = Uuid::new();
        let post = Post {
            id: post_id,
            user_id,
            title: "First Post".to_string(),
            content: "This is the first post content.".to_string(),
            status: PostStatus::PUBLISHED,
        };
        posts.insert(post_id, post);

        Database { users, posts }
    }

    fn get_user(&self, id: Uuid) -> Option<User> {
        println!("DATABASE: Querying for user with ID: {:?}", id);
        thread::sleep(Duration::from_millis(50)); // Simulate DB latency
        self.users.get(&id).cloned()
    }

    fn update_user(&mut self, user: &User) {
        println!("DATABASE: Updating user with ID: {:?}", user.id);
        thread::sleep(Duration::from_millis(100)); // Simulate DB latency
        self.users.insert(user.id, user.clone());
    }
}

// --- Caching Implementation (OOP Style) ---

struct CacheEntry<V> {
    value: V,
    expires_at: Instant,
}

type NodeLink<K, V> = Option<Rc<RefCell<Node<K, V>>>>;

struct Node<K, V> {
    key: K,
    value: CacheEntry<V>,
    prev: NodeLink<K, V>,
    next: NodeLink<K, V>,
}

pub struct LruCache<K, V> {
    capacity: usize,
    map: HashMap<K, Rc<RefCell<Node<K, V>>>>,
    head: NodeLink<K, V>,
    tail: NodeLink<K, V>,
    default_ttl: Duration,
}

impl<K: std::hash::Hash + Eq + Clone, V: Clone> LruCache<K, V> {
    fn new(capacity: usize, default_ttl: Duration) -> Self {
        LruCache {
            capacity,
            map: HashMap::with_capacity(capacity),
            head: None,
            tail: None,
            default_ttl,
        }
    }

    fn get(&mut self, key: &K) -> Option<V> {
        let node_rc = self.map.get(key)?.clone();
        let mut node = node_rc.borrow_mut();

        if Instant::now() > node.value.expires_at {
            // Entry expired, drop it.
            drop(node); // Release borrow before remove
            self.remove(key);
            return None;
        }

        let value = node.value.value.clone();
        drop(node); // Release borrow before move_to_front
        self.move_to_front(&node_rc);
        Some(value)
    }

    fn put(&mut self, key: K, value: V) {
        self.put_with_ttl(key, value, self.default_ttl);
    }

    fn put_with_ttl(&mut self, key: K, value: V, ttl: Duration) {
        if let Some(node_rc) = self.map.get(&key) {
            let mut node = node_rc.borrow_mut();
            node.value = CacheEntry {
                value,
                expires_at: Instant::now() + ttl,
            };
            drop(node);
            self.move_to_front(node_rc);
        } else {
            if self.map.len() >= self.capacity {
                if let Some(tail_rc) = self.tail.clone() {
                    let tail_key = tail_rc.borrow().key.clone();
                    self.remove(&tail_key);
                }
            }

            let new_node = Rc::new(RefCell::new(Node {
                key: key.clone(),
                value: CacheEntry {
                    value,
                    expires_at: Instant::now() + ttl,
                },
                prev: None,
                next: self.head.clone(),
            }));

            if let Some(head_rc) = &self.head {
                head_rc.borrow_mut().prev = Some(new_node.clone());
            } else {
                self.tail = Some(new_node.clone());
            }

            self.head = Some(new_node.clone());
            self.map.insert(key, new_node);
        }
    }

    fn remove(&mut self, key: &K) {
        if let Some(node_rc) = self.map.remove(key) {
            let node = node_rc.borrow();
            let prev = node.prev.clone();
            let next = node.next.clone();

            if let Some(prev_rc) = &prev {
                prev_rc.borrow_mut().next = next.clone();
            } else {
                self.head = next.clone();
            }

            if let Some(next_rc) = &next {
                next_rc.borrow_mut().prev = prev;
            } else {
                self.tail = prev;
            }
        }
    }

    fn move_to_front(&mut self, node_rc: &Rc<RefCell<Node<K, V>>>) {
        let mut node = node_rc.borrow_mut();
        if let Some(prev) = node.prev.clone() {
            prev.borrow_mut().next = node.next.clone();
        } else {
            // Already at the head
            return;
        }

        if let Some(next) = node.next.clone() {
            next.borrow_mut().prev = node.prev.clone();
        } else {
            self.tail = node.prev.clone();
        }

        node.prev = None;
        node.next = self.head.clone();

        if let Some(head_rc) = &self.head {
            head_rc.borrow_mut().prev = Some(node_rc.clone());
        }
        self.head = Some(node_rc.clone());
    }
}

// --- Service Layer implementing Cache-Aside ---

pub struct AppService {
    db: Database,
    user_cache: LruCache<Uuid, User>,
    post_cache: LruCache<Uuid, Post>,
}

impl AppService {
    fn new(db: Database) -> Self {
        AppService {
            db,
            user_cache: LruCache::new(100, Duration::from_secs(300)),
            post_cache: LruCache::new(500, Duration::from_secs(60)),
        }
    }

    // Cache-aside read
    fn get_user_by_id(&mut self, id: Uuid) -> Option<User> {
        // 1. Try to get from cache
        if let Some(user) = self.user_cache.get(&id) {
            println!("CACHE HIT for user ID: {:?}", id);
            return Some(user);
        }

        println!("CACHE MISS for user ID: {:?}", id);
        // 2. On miss, get from DB
        let user_from_db = self.db.get_user(id);

        // 3. Put DB result into cache
        if let Some(ref user) = user_from_db {
            self.user_cache.put(id, user.clone());
        }

        // 4. Return result
        user_from_db
    }

    // Write-around with cache invalidation
    fn update_user_email(&mut self, id: Uuid, new_email: String) {
        if let Some(mut user) = self.db.get_user(id) {
            user.email = new_email;
            // 1. Update the database first
            self.db.update_user(&user);
            // 2. Invalidate the cache
            println!("INVALIDATING CACHE for user ID: {:?}", id);
            self.user_cache.remove(&id);
        }
    }
}

fn main() {
    let db = Database::new();
    let mut service = AppService::new(db);
    let user_id = service.db.users.keys().next().unwrap().clone();

    println!("--- First fetch ---");
    let _ = service.get_user_by_id(user_id);

    println!("\n--- Second fetch (should be cached) ---");
    let _ = service.get_user_by_id(user_id);

    println!("\n--- Updating user's email ---");
    service.update_user_email(user_id, "new.admin@example.com".to_string());

    println!("\n--- Third fetch (should be a miss, then cached) ---");
    let user = service.get_user_by_id(user_id);
    println!("Fetched user with new email: {}", user.unwrap().email);

    println!("\n--- Demonstrating LRU eviction ---");
    let mut small_cache = LruCache::new(2, Duration::from_secs(60));
    let key1 = Uuid::new();
    let key2 = Uuid::new();
    let key3 = Uuid::new();
    small_cache.put(key1, "value1".to_string());
    small_cache.put(key2, "value2".to_string());
    println!("Cache has key1: {}", small_cache.get(&key1).is_some());
    small_cache.put(key3, "value3".to_string()); // This should evict key1
    println!("After adding key3, cache has key1: {}", small_cache.get(&key1).is_some());
    println!("Cache has key2: {}", small_cache.get(&key2).is_some());
    println!("Cache has key3: {}", small_cache.get(&key3).is_some());

    println!("\n--- Demonstrating time-based expiration ---");
    let mut expiring_cache = LruCache::new(1, Duration::from_millis(10));
    let expiring_key = Uuid::new();
    expiring_cache.put_with_ttl(expiring_key, "short-lived".to_string(), Duration::from_millis(20));
    println!("Immediately after put, value is: {:?}", expiring_cache.get(&expiring_key));
    thread::sleep(Duration::from_millis(30));
    println!("After 30ms, value is: {:?}", expiring_cache.get(&expiring_key));
}