package com.example.caching.pragmatic

import java.time.Instant
import java.util.UUID

// 1. DOMAIN (Concise Naming)
enum class UsrRole { ADMIN, USER }
enum class PstStatus { DRAFT, PUBLISHED }

data class Usr(
    val id: UUID,
    var email: String,
    val pwdHash: String,
    val role: UsrRole,
    var active: Boolean,
    val created: Instant
)

data class Pst(
    val id: UUID,
    val usrId: UUID,
    var title: String,
    var content: String,
    var status: PstStatus
)

// 2. COMBINED REPOSITORY WITH EMBEDDED CACHE
// This class manages its own cache and data persistence logic.
class UserRepository(cacheCapacity: Int) {

    // Private inner LRU cache implementation
    private class Lru<K, V>(private val cap: Int) {
        private inner class Node(val k: K, var v: V, var exp: Long) {
            var prev: Node? = null
            var next: Node? = null
        }
        private val store = hashMapOf<K, Node>()
        private val head = Node(Any() as K, Any() as V, 0)
        private val tail = Node(Any() as K, Any() as V, 0)

        init {
            head.next = tail
            tail.prev = head
        }

        fun get(k: K): V? {
            val node = store[k] ?: return null
            if (System.currentTimeMillis() > node.exp) {
                del(k)
                return null
            }
            detach(node)
            attach(node)
            return node.v
        }

        fun put(k: K, v: V, ttlMs: Long) {
            val node = store[k]
            val exp = System.currentTimeMillis() + ttlMs
            if (node != null) {
                node.v = v
                node.exp = exp
                detach(node)
                attach(node)
            } else {
                if (store.size >= cap) {
                    store.remove(tail.prev!!.k)?.let { detach(it) }
                }
                val newNode = Node(k, v, exp)
                store[k] = newNode
                attach(newNode)
            }
        }

        fun del(k: K) {
            store.remove(k)?.let { detach(it) }
        }

        private fun attach(node: Node) {
            node.next = head.next
            head.next?.prev = node
            head.next = node
            node.prev = head
        }

        private fun detach(node: Node) {
            node.prev?.next = node.next
            node.next?.prev = node.prev
        }
    }

    // Embedded cache and mock DB table
    private val cache = Lru<UUID, Usr>(cacheCapacity)
    private val dbTable = mutableMapOf<UUID, Usr>()

    // Public API implementing cache-aside
    fun findById(id: UUID): Usr? {
        cache.get(id)?.let {
            println("REPO: HIT user $id")
            return it
        }
        println("REPO: MISS user $id")
        val userFromDb = dbTable[id]
        userFromDb?.let {
            cache.put(id, it, 60_000) // 60 sec TTL
            println("REPO: SET user $id in cache")
        }
        return userFromDb
    }

    fun save(usr: Usr) {
        println("REPO: SAVING user ${usr.id} to DB")
        dbTable[usr.id] = usr.copy() // Save a copy
        cache.del(usr.id)
        println("REPO: INVALIDATED user ${usr.id} from cache")
    }

    fun delete(id: UUID) {
        println("REPO: DELETING user $id from DB")
        dbTable.remove(id)
        cache.del(id)
        println("REPO: INVALIDATED user $id from cache")
    }
    
    // Helper to populate DB for demo
    fun primeDb(usr: Usr) {
        dbTable[usr.id] = usr
    }
}

// 3. DEMONSTRATION
fun main() {
    println("--- Setup ---")
    val userRepo = UserRepository(cacheCapacity = 2)
    val u1 = Usr(UUID.randomUUID(), "test1@dev.com", "h1", UsrRole.USER, true, Instant.now())
    val u2 = Usr(UUID.randomUUID(), "test2@dev.com", "h2", UsrRole.USER, false, Instant.now())
    val u3 = Usr(UUID.randomUUID(), "test3@dev.com", "h3", UsrRole.ADMIN, true, Instant.now())
    userRepo.primeDb(u1)
    userRepo.primeDb(u2)
    userRepo.primeDb(u3)

    println("\n--- Cache Miss & Set ---")
    userRepo.findById(u1.id)
    userRepo.findById(u2.id)

    println("\n--- Cache Hit ---")
    userRepo.findById(u1.id)

    println("\n--- LRU Eviction ---")
    userRepo.findById(u3.id) // Miss, sets u3, evicts u2
    userRepo.findById(u2.id) // Miss again

    println("\n--- Invalidation on Update ---")
    val updatedU1 = u1.copy(email = "updated.test1@dev.com")
    userRepo.save(updatedU1)
    val fetchedU1 = userRepo.findById(u1.id) // Miss, then set
    println("Fetched updated email: ${fetchedU1?.email}")

    println("\n--- Time-based Expiration ---")
    val shortTtlRepo = UserRepository(cacheCapacity = 2)
    shortTtlRepo.primeDb(u1)
    shortTtlRepo.cache.put(u1.id, u1, 1000) // Manually put with short TTL for demo
    println("Immediate fetch: ${shortTtlRepo.findById(u1.id) != null}") // Hit
    Thread.sleep(1100)
    println("Fetch after 1.1s: ${shortTtlRepo.findById(u1.id) != null}") // Miss
}