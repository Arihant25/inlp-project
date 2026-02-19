package com.example.functional

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import jakarta.persistence.*
import jakarta.persistence.criteria.Predicate
import java.time.Instant
import java.util.*

// --- Domain Model ---
enum class UserRole { ADMIN, USER }

@Entity
@Table(name = "users")
class User(
    @Id
    val id: UUID = UUID.randomUUID(),
    var email: String,
    var passwordHash: String,
    @Enumerated(EnumType.STRING)
    var role: UserRole,
    var isActive: Boolean = true,
    val createdAt: Instant = Instant.now()
)

// --- DTOs (Representations & Payloads) ---
data class UserRepresentation(
    val id: UUID,
    val email: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: String
)

data class UserCreatePayload(val email: String, val password: String, val role: UserRole)
data class UserUpdatePayload(val email: String?, val role: UserRole?, val isActive: Boolean?)

// --- Kotlin Extension for Mapping ---
fun User.toRepresentation(): UserRepresentation = UserRepresentation(
    id = this.id,
    email = this.email,
    role = this.role,
    isActive = this.isActive,
    createdAt = this.createdAt.toString()
)

// --- Repository with Specification Executor ---
@Repository
interface UserRepository : JpaRepository<User, UUID>, JpaSpecificationExecutor<User>

// --- Dynamic Specification Builder ---
object UserSpecification {
    fun withFilter(email: String?, role: UserRole?, isActive: Boolean?): Specification<User> {
        return Specification { root, _, criteriaBuilder ->
            val predicates = mutableListOf<Predicate>()
            email?.let {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("email")), "%${it.lowercase()}%"))
            }
            role?.let {
                predicates.add(criteriaBuilder.equal(root.get<UserRole>("role"), it))
            }
            isActive?.let {
                predicates.add(criteriaBuilder.equal(root.get<Boolean>("isActive"), it))
            }
            criteriaBuilder.and(*predicates.toTypedArray())
        }
    }
}

// --- Service Layer ---
@Service
class UserHandlerService(private val userRepository: UserRepository) {

    fun createNewUser(payload: UserCreatePayload): User {
        // Dummy password hashing
        val hash = Base64.getEncoder().encodeToString(payload.password.toByteArray())
        val user = User(email = payload.email, passwordHash = hash, role = payload.role)
        return userRepository.save(user)
    }

    fun findById(id: UUID): User? = userRepository.findById(id).orElse(null)

    fun findAll(pageable: Pageable): Page<User> = userRepository.findAll(pageable)

    fun search(email: String?, role: UserRole?, isActive: Boolean?, pageable: Pageable): Page<User> {
        val spec = UserSpecification.withFilter(email, role, isActive)
        return userRepository.findAll(spec, pageable)
    }

    fun partiallyUpdate(id: UUID, payload: UserUpdatePayload): Result<User> {
        return findById(id)?.let { user ->
            user.apply {
                payload.email?.let { email = it }
                payload.role?.let { role = it }
                payload.isActive?.let { isActive = it }
            }
            Result.success(userRepository.save(user))
        } ?: Result.failure(NoSuchElementException("User $id not found"))
    }

    fun delete(id: UUID): Boolean {
        return if (userRepository.existsById(id)) {
            userRepository.deleteById(id)
            true
        } else false
    }
}

// --- API Resource ---
@RestController
@RequestMapping("/api/functional/users")
class UserResource(private val service: UserHandlerService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun postUser(@RequestBody payload: UserCreatePayload): UserRepresentation =
        service.createNewUser(payload).toRepresentation()

    @GetMapping("/{id}")
    fun getUser(@PathVariable id: UUID): ResponseEntity<UserRepresentation> =
        service.findById(id)
            ?.toRepresentation()
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @GetMapping
    fun getUsers(
        @RequestParam email: String?,
        @RequestParam role: UserRole?,
        @RequestParam isActive: Boolean?,
        pageable: Pageable
    ): Page<UserRepresentation> = service.search(email, role, isActive, pageable).map { it.toRepresentation() }

    @PutMapping("/{id}")
    fun putUser(@PathVariable id: UUID, @RequestBody payload: UserUpdatePayload): ResponseEntity<UserRepresentation> =
        updateUser(id, payload)

    @PatchMapping("/{id}")
    fun patchUser(@PathVariable id: UUID, @RequestBody payload: UserUpdatePayload): ResponseEntity<UserRepresentation> =
        updateUser(id, payload)

    private fun updateUser(id: UUID, payload: UserUpdatePayload): ResponseEntity<UserRepresentation> {
        return service.partiallyUpdate(id, payload)
            .fold(
                onSuccess = { ResponseEntity.ok(it.toRepresentation()) },
                onFailure = { ResponseEntity.notFound().build() }
            )
    }

    @DeleteMapping("/{id}")
    fun deleteUser(@PathVariable id: UUID): ResponseEntity<Unit> =
        if (service.delete(id)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()
}