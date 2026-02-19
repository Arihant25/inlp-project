package com.example.pragmatic

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.PagingAndSortingRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import jakarta.persistence.*
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.*

// --- Domain Model ---
enum class UserRole { ADMIN, USER }

@Entity(name = "app_user")
data class User(
    @Id @GeneratedValue
    var id: UUID = UUID.randomUUID(),
    var email: String,
    var passwordHash: String,
    @Enumerated(EnumType.STRING)
    var role: UserRole,
    var isActive: Boolean = true,
    val createdAt: Instant = Instant.now()
)

// --- DTOs ---
data class UserDto(
    val id: UUID,
    val email: String,
    val role: UserRole,
    val isActive: Boolean
)

data class UserRequest(
    @field:Email val email: String?,
    @field:NotBlank val password: String?,
    val role: UserRole?,
    val isActive: Boolean?
)

// --- Data Access ---
@Repository
interface UsersRepo : PagingAndSortingRepository<User, UUID> {
    fun findAllByRoleAndIsActive(role: UserRole, isActive: Boolean, pageable: Pageable): Page<User>
}

// --- Business Logic ---
@Service
class UserManagementService(private val usersRepo: UsersRepo) {

    fun saveUser(req: UserRequest): User {
        val user = User(
            email = req.email!!,
            passwordHash = req.password!!.hashCode().toString(), // Dummy hashing
            role = req.role!!,
            isActive = req.isActive ?: true
        )
        return usersRepo.save(user)
    }

    fun findUser(id: UUID): Optional<User> = usersRepo.findById(id)

    fun listAll(pageable: Pageable): Page<User> = usersRepo.findAll(pageable)

    fun updateUser(id: UUID, req: UserRequest): User? {
        return findUser(id).map { existingUser ->
            existingUser.email = req.email ?: existingUser.email
            existingUser.role = req.role ?: existingUser.role
            existingUser.isActive = req.isActive ?: existingUser.isActive
            // Password update would require a separate flow in a real app
            usersRepo.save(existingUser)
        }.orElse(null)
    }

    fun removeUser(id: UUID): Boolean {
        return if (usersRepo.existsById(id)) {
            usersRepo.deleteById(id)
            true
        } else {
            false
        }
    }

    fun filterUsers(role: UserRole?, isActive: Boolean?, pageable: Pageable): Page<User> {
        if (role != null && isActive != null) {
            return usersRepo.findAllByRoleAndIsActive(role, isActive, pageable)
        }
        // In a real scenario, more complex filtering logic would be here
        return usersRepo.findAll(pageable)
    }
}

// --- API Endpoints ---
@RestController
@RequestMapping("/users")
class UsersController(private val userService: UserManagementService) {

    private fun User.toDto() = UserDto(this.id, this.email, this.role, this.isActive)

    @PostMapping
    fun createUser(@Valid @RequestBody body: UserRequest): ResponseEntity<UserDto> {
        if (body.email == null || body.password == null || body.role == null) {
            return ResponseEntity.badRequest().build()
        }
        val newUser = userService.saveUser(body)
        return ResponseEntity(newUser.toDto(), HttpStatus.CREATED)
    }

    @GetMapping("/{userId}")
    fun getUser(@PathVariable userId: UUID): ResponseEntity<UserDto> {
        return userService.findUser(userId)
            .map { ResponseEntity.ok(it.toDto()) }
            .orElse(ResponseEntity.notFound().build())
    }

    @GetMapping
    fun getUsers(
        @RequestParam role: UserRole?,
        @RequestParam active: Boolean?,
        pageable: Pageable
    ): ResponseEntity<Page<UserDto>> {
        val page = userService.filterUsers(role, active, pageable).map { it.toDto() }
        return ResponseEntity.ok(page)
    }

    @PutMapping("/{userId}")
    fun updateUser(@PathVariable userId: UUID, @RequestBody body: UserRequest): ResponseEntity<UserDto> {
        val updatedUser = userService.updateUser(userId, body)
        return if (updatedUser != null) {
            ResponseEntity.ok(updatedUser.toDto())
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @PatchMapping("/{userId}")
    fun patchUser(@PathVariable userId: UUID, @RequestBody body: UserRequest): ResponseEntity<UserDto> {
        // Reusing the same logic as PUT for simplicity in this pragmatic approach
        return updateUser(userId, body)
    }

    @DeleteMapping("/{userId}")
    fun deleteUser(@PathVariable userId: UUID): ResponseEntity<Void> {
        return if (userService.removeUser(userId)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }
}