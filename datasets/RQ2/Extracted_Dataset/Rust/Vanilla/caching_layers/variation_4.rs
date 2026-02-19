use std::collections::HashMap;
use std::rc::Rc;
use std::cell::RefCell;
use std::time::{Duration, Instant, SystemTime};
use std::hash::Hash;
use std::thread;

// --- Domain Schema ---

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Uuid(u128);

impl Uuid {
    fn new() -> Self {
        // Simple pseudo-random generator for demonstration
        Uuid(SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap().as_nanos())
    }
}

#[derive(Debug, Clone, Copy)]
pub enum UserRole { ADMIN, USER }

#[derive(Debug, Clone)]
pub struct User {
    id: Uuid,
    email: String,
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: SystemTime,
}

#[derive(Debug, Clone, Copy)]
pub enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug, Clone)]
pub struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Unified Cache Key/Value Types ---

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum CacheKey {
    User(Uuid),
    Post(Uuid),
}

#[derive(Debug, Clone)]
pub enum CacheValue {
    User(User),
    Post(Post),
}

// --- Mock Database ---

pub struct MockDb {
    users: HashMap<Uuid, User>,
    posts: HashMap<Uuid, Post>,
}

impl MockDb {
    fn new() -> Self {
        let mut users = HashMap::new();
        let mut posts = HashMap::new();
        let user_id = Uuid::new();
        users.insert(user_id, User {
            id: user_id,
            email: "pragmatic@dev.com".to_string(),
            password_hash: "secret".to_string(),
            role: UserRole::USER,
            is_active: true,
            created_at: SystemTime::now(),
        });
        let post_id = Uuid::new();
        posts.insert(post_id, Post {
            id: post_id,
            user_id,
            title: "A Pragmatic Post".to_string(),
            content: "Content...".to_string(),
            status: PostStatus::PUBLISHED,
        });
        MockDb { users, posts }
    }
}

// --- LRU Cache Implementation (Minimalist Style) ---

struct CacheNode {
    key: CacheKey,
    value: CacheValue,
    expires: Instant,
    prev: Option<Rc<RefCell<CacheNode>>>,
    next: Option<Rc<RefCell<CacheNode>>>,
}

pub struct UnifiedLruCache {
    capacity: usize,
    map: HashMap<CacheKey, Rc<RefCell<CacheNode>>>,
    head: Option<Rc<RefCell<CacheNode>>>,
    tail: Option<Rc<RefCell<CacheNode>>>,
}

impl UnifiedLruCache {
    fn new(capacity: usize) -> Self {
        UnifiedLruCache { capacity, map: HashMap::new(), head: None, tail: None }
    }

    fn get(&mut self, key: &CacheKey) -> Option<CacheValue> {
        let node_rc = self.map.get(key)?.clone();
        if Instant::now() > node_rc.borrow().expires {
            self.remove(key);
            return None;
        }
        let value = node_rc.borrow().value.clone();
        self.move_to_front(node_rc);
        Some(value)
    }

    fn put(&mut self, key: CacheKey, value: CacheValue, ttl: Duration) {
        if let Some(node_rc) = self.map.get(&key) {
            let mut node = node_rc.borrow_mut();
            node.value = value;
            node.expires = Instant::now() + ttl;
            drop(node);
            self.move_to_front(node_rc.clone());
            return;
        }

        if self.map.len() >= self.capacity {
            if let Some(tail_rc) = self.tail.clone() {
                self.remove(&tail_rc.borrow().key);
            }
        }

        let new_node = Rc::new(RefCell::new(CacheNode {
            key: key.clone(),
            value,
            expires: Instant::now() + ttl,
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

    fn remove(&mut self, key: &CacheKey) {
        if let Some(node_rc) = self.map.remove(key) {
            let node = node_rc.borrow();
            let prev = node.prev.clone();
            let next = node.next.clone();
            if let Some(p) = &prev { p.borrow_mut().next = next.clone(); } else { self.head = next.clone(); }
            if let Some(n) = &next { n.borrow_mut().prev = prev; } else { self.tail = prev; }
        }
    }

    fn move_to_front(&mut self, node_rc: Rc<RefCell<CacheNode>>) {
        if self.head.as_ref().map_or(false, |h| Rc::ptr_eq(h, &node_rc)) { return; }
        
        let (prev, next) = {
            let node = node_rc.borrow();
            (node.prev.clone(), node.next.clone())
        };

        if let Some(p) = &prev { p.borrow_mut().next = next.clone(); }
        if let Some(n) = &next { n.borrow_mut().prev = prev.clone(); }

        if self.tail.as_ref().map_or(false, |t| Rc::ptr_eq(t, &node_rc)) {
            self.tail = prev;
        }

        node_rc.borrow_mut().prev = None;
        node_rc.borrow_mut().next = self.head.clone();
        if let Some(h) = &self.head { h.borrow_mut().prev = Some(node_rc.clone()); }
        self.head = Some(node_rc);
    }
}

// --- Application Logic (Module-based) ---

mod data_access {
    use super::*;

    // Cache-aside pattern for User
    pub fn get_user(db: &MockDb, cache: &mut UnifiedLruCache, id: Uuid) -> Option<User> {
        let key = CacheKey::User(id);
        if let Some(CacheValue::User(user)) = cache.get(&key) {
            println!("CACHE HIT: User {:?}", id);
            return Some(user);
        }
        println!("CACHE MISS: User {:?}", id);

        thread::sleep(Duration::from_millis(50)); // Simulate DB
        if let Some(user) = db.users.get(&id).cloned() {
            cache.put(key, CacheValue::User(user.clone()), Duration::from_secs(300));
            return Some(user);
        }
        None
    }
    
    // Cache-aside pattern for Post
    pub fn get_post(db: &MockDb, cache: &mut UnifiedLruCache, id: Uuid) -> Option<Post> {
        let key = CacheKey::Post(id);
        if let Some(CacheValue::Post(post)) = cache.get(&key) {
            println!("CACHE HIT: Post {:?}", id);
            return Some(post);
        }
        println!("CACHE MISS: Post {:?}", id);

        thread::sleep(Duration::from_millis(50)); // Simulate DB
        if let Some(post) = db.posts.get(&id).cloned() {
            cache.put(key, CacheValue::Post(post.clone()), Duration::from_secs(60));
            return Some(post);
        }
        None
    }

    // Write-around with cache invalidation
    pub fn update_user(db: &mut MockDb, cache: &mut UnifiedLruCache, user: User) {
        let key = CacheKey::User(user.id);
        println!("DB: Updating user {:?}", user.id);
        thread::sleep(Duration::from_millis(100)); // Simulate DB
        db.users.insert(user.id, user);
        println!("CACHE INVALIDATE: User {:?}", key);
        cache.remove(&key);
    }
}

fn main() {
    let mut db = MockDb::new();
    let mut cache = UnifiedLruCache::new(100);

    let user_id = db.users.keys().next().unwrap().clone();
    let post_id = db.posts.keys().next().unwrap().clone();

    println!("--- Fetching user and post for the first time ---");
    data_access::get_user(&db, &mut cache, user_id);
    data_access::get_post(&db, &mut cache, post_id);

    println!("\n--- Fetching them again (should be cached) ---");
    data_access::get_user(&db, &mut cache, user_id);
    data_access::get_post(&db, &mut cache, post_id);

    println!("\n--- Updating user ---");
    let mut user_to_update = data_access::get_user(&db, &mut cache, user_id).unwrap();
    user_to_update.email = "updated.pragmatic@dev.com".to_string();
    data_access::update_user(&mut db, &mut cache, user_to_update);

    println!("\n--- Fetching user after update ---");
    let updated_user = data_access::get_user(&db, &mut cache, user_id).unwrap();
    println!("Fetched user with new email: {}", updated_user.email);

    println!("\n--- LRU Eviction Test ---");
    let mut small_cache = UnifiedLruCache::new(2);
    let key1 = CacheKey::User(Uuid::new());
    let key2 = CacheKey::Post(Uuid::new());
    let key3 = CacheKey::User(Uuid::new());
    small_cache.put(key1.clone(), CacheValue::User(db.users[&user_id].clone()), Duration::from_secs(10));
    small_cache.put(key2.clone(), CacheValue::Post(db.posts[&post_id].clone()), Duration::from_secs(10));
    println!("Cache has key1: {}", small_cache.get(&key1).is_some());
    small_cache.put(key3.clone(), CacheValue::User(db.users[&user_id].clone()), Duration::from_secs(10)); // Evicts key1
    println!("After adding key3, cache has key1: {}", small_cache.get(&key1).is_some());
    println!("Cache has key2: {}", small_cache.get(&key2).is_some());
    println!("Cache has key3: {}", small_cache.get(&key3).is_some());
}