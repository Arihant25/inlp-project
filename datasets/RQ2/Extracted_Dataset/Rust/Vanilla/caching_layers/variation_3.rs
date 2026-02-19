use std::collections::HashMap;
use std::rc::Rc;
use std::cell::RefCell;
use std::time::{Duration, Instant, SystemTime};
use std::hash::Hash;
use std::fmt::Debug;
use std::thread;

// --- Domain Schema ---

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Uuid(u128);

impl Uuid {
    // Simple pseudo-random generator for demonstration
    pub fn new() -> Self {
        Uuid(SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap().as_nanos())
    }
}

#[derive(Debug, Clone, Copy)]
pub enum UserRole { ADMIN, USER }

#[derive(Debug, Clone)]
pub struct User {
    pub id: Uuid,
    pub email: String,
    pub password_hash: String,
    pub role: UserRole,
    pub is_active: bool,
    pub created_at: SystemTime,
}

#[derive(Debug, Clone, Copy)]
pub enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug, Clone)]
pub struct Post {
    pub id: Uuid,
    pub user_id: Uuid,
    pub title: String,
    pub content: String,
    pub status: PostStatus,
}

// --- Generic Abstractions ---

pub trait Cacheable: Clone {
    type Key: Hash + Eq + Clone + Debug;
    fn key(&self) -> Self::Key;
}

impl Cacheable for User {
    type Key = Uuid;
    fn key(&self) -> Self::Key {
        self.id
    }
}

impl Cacheable for Post {
    type Key = Uuid;
    fn key(&self) -> Self::Key {
        self.id
    }
}

// --- Generic Data Store (Mock DB) ---

pub struct DataStore<T: Cacheable> {
    data: HashMap<T::Key, T>,
}

impl<T: Cacheable> DataStore<T> {
    pub fn new() -> Self {
        DataStore { data: HashMap::new() }
    }

    pub fn find(&self, key: &T::Key) -> Result<Option<T>, String> {
        println!("DATASTORE: Querying for key: {:?}", key);
        thread::sleep(Duration::from_millis(50)); // Simulate latency
        Ok(self.data.get(key).cloned())
    }

    pub fn save(&mut self, entity: T) -> Result<(), String> {
        println!("DATASTORE: Saving entity with key: {:?}", entity.key());
        thread::sleep(Duration::from_millis(100)); // Simulate latency
        self.data.insert(entity.key(), entity);
        Ok(())
    }
}

// --- Generic LRU Cache Implementation ---

struct CacheEntry<V> {
    value: V,
    expires_at: Instant,
}

type NodePtr<K, V> = Rc<RefCell<Node<K, V>>>;
type Link<K, V> = Option<NodePtr<K, V>>;

struct Node<K, V> {
    key: K,
    entry: CacheEntry<V>,
    prev: Link<K, V>,
    next: Link<K, V>,
}

pub struct LruCache<K: Hash + Eq + Clone, V: Clone> {
    capacity: usize,
    map: HashMap<K, NodePtr<K, V>>,
    head: Link<K, V>,
    tail: Link<K, V>,
}

impl<K: Hash + Eq + Clone, V: Clone> LruCache<K, V> {
    pub fn new(capacity: usize) -> Self {
        Self { capacity, map: HashMap::new(), head: None, tail: None }
    }

    pub fn get(&mut self, key: &K) -> Option<V> {
        let node_ptr = self.map.get(key)?.clone();
        if Instant::now() > node_ptr.borrow().entry.expires_at {
            self.remove_node(key);
            return None;
        }
        let value = node_ptr.borrow().entry.value.clone();
        self.detach(&node_ptr);
        self.attach_to_head(node_ptr);
        Some(value)
    }

    pub fn put(&mut self, key: K, value: V, ttl: Duration) {
        if let Some(node_ptr) = self.map.get(&key) {
            node_ptr.borrow_mut().entry = CacheEntry { value, expires_at: Instant::now() + ttl };
            self.detach(node_ptr);
            self.attach_to_head(node_ptr.clone());
        } else {
            if self.map.len() >= self.capacity {
                if let Some(tail_ptr) = self.tail.clone() {
                    self.remove_node(&tail_ptr.borrow().key.clone());
                }
            }
            let new_node = Rc::new(RefCell::new(Node {
                key: key.clone(),
                entry: CacheEntry { value, expires_at: Instant::now() + ttl },
                prev: None,
                next: None,
            }));
            self.attach_to_head(new_node.clone());
            self.map.insert(key, new_node);
        }
    }

    pub fn invalidate(&mut self, key: &K) {
        self.remove_node(key);
    }

    fn attach_to_head(&mut self, node_ptr: NodePtr<K, V>) {
        match self.head.take() {
            Some(old_head) => {
                old_head.borrow_mut().prev = Some(node_ptr.clone());
                node_ptr.borrow_mut().next = Some(old_head);
            }
            None => {
                self.tail = Some(node_ptr.clone());
            }
        }
        self.head = Some(node_ptr);
    }

    fn detach(&mut self, node_ptr: &NodePtr<K, V>) {
        let mut node = node_ptr.borrow_mut();
        let prev = node.prev.take();
        let next = node.next.take();

        if let Some(prev_ptr) = &prev {
            prev_ptr.borrow_mut().next = next.clone();
        } else {
            self.head = next.clone();
        }

        if let Some(next_ptr) = &next {
            next_ptr.borrow_mut().prev = prev;
        } else {
            self.tail = prev;
        }
    }

    fn remove_node(&mut self, key: &K) {
        if let Some(node_ptr) = self.map.remove(key) {
            self.detach(&node_ptr);
        }
    }
}

// --- Generic Caching Repository (Service Layer) ---

pub struct CachingRepository<T: Cacheable> {
    datastore: DataStore<T>,
    cache: LruCache<T::Key, T>,
    default_ttl: Duration,
}

impl<T: Cacheable> CachingRepository<T> {
    pub fn new(datastore: DataStore<T>, cache_capacity: usize, default_ttl: Duration) -> Self {
        Self {
            datastore,
            cache: LruCache::new(cache_capacity),
            default_ttl,
        }
    }

    // Cache-aside read
    pub fn find_by_id(&mut self, key: &T::Key) -> Result<Option<T>, String> {
        if let Some(entity) = self.cache.get(key) {
            println!("CACHE HIT for key: {:?}", key);
            return Ok(Some(entity));
        }

        println!("CACHE MISS for key: {:?}", key);
        let entity_from_db = self.datastore.find(key)?;
        if let Some(ref entity) = entity_from_db {
            self.cache.put(entity.key(), entity.clone(), self.default_ttl);
        }
        Ok(entity_from_db)
    }

    // Write-through with cache invalidation
    pub fn save(&mut self, entity: T) -> Result<(), String> {
        self.datastore.save(entity.clone())?;
        println!("INVALIDATING CACHE for key: {:?}", entity.key());
        self.cache.invalidate(&entity.key());
        Ok(())
    }
}

fn main() {
    // Setup for Users
    let mut user_store = DataStore::<User>::new();
    let user_id = Uuid::new();
    let user = User {
        id: user_id,
        email: "generic@example.com".to_string(),
        password_hash: "generic_hash".to_string(),
        role: UserRole::ADMIN,
        is_active: true,
        created_at: SystemTime::now(),
    };
    user_store.save(user.clone()).unwrap();
    let mut user_repo = CachingRepository::new(user_store, 10, Duration::from_secs(60));

    println!("--- Fetching User ---");
    let _ = user_repo.find_by_id(&user_id); // Miss
    let _ = user_repo.find_by_id(&user_id); // Hit

    println!("\n--- Updating User ---");
    let mut updated_user = user.clone();
    updated_user.email = "updated.generic@example.com".to_string();
    user_repo.save(updated_user).unwrap();

    println!("\n--- Fetching User After Update ---");
    let fetched_user = user_repo.find_by_id(&user_id).unwrap().unwrap(); // Miss
    println!("Fetched user with new email: {}", fetched_user.email);

    println!("\n--- LRU Eviction Demo ---");
    let mut cache = LruCache::new(2);
    cache.put(1, "A", Duration::from_secs(10));
    cache.put(2, "B", Duration::from_secs(10));
    println!("Cache has 1: {}", cache.get(&1).is_some());
    cache.put(3, "C", Duration::from_secs(10)); // Evicts 1
    println!("After adding 3, cache has 1: {}", cache.get(&1).is_some());
    println!("Cache has 2: {}", cache.get(&2).is_some());
    println!("Cache has 3: {}", cache.get(&3).is_some());

    println!("\n--- TTL Expiration Demo ---");
    let mut cache = LruCache::new(1);
    cache.put(1, "short", Duration::from_millis(20));
    println!("Value is: {:?}", cache.get(&1));
    thread::sleep(Duration::from_millis(30));
    println!("After 30ms, value is: {:?}", cache.get(&1));
}