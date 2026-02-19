// --- pom.xml ---
/*
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
*/

// --- src/main/resources/application.properties ---
/*
spring.datasource.url=jdbc:h2:mem:specdb
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=password
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
*/

// --- src/main/java/com/example/specs/SpecsApplication.java ---
package com.example.specs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SpecsApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpecsApplication.class, args);
    }
}

// --- src/main/java/com/example/specs/model/PostStatus.java ---
package com.example.specs.model;

public enum PostStatus { DRAFT, PUBLISHED }

// --- src/main/java/com/example/specs/model/Role.java ---
package com.example.specs.model;

import jakarta.persistence.*;
import java.util.Set;

@Entity
@Table(name = "roles")
public class Role {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @ManyToMany(mappedBy = "roles")
    private Set<User> users;
    // Getters/Setters omitted for brevity
}

// --- src/main/java/com/example/specs/model/User.java ---
package com.example.specs.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "app_users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(unique = true, nullable = false)
    private String email;
    private String passwordHash;
    private boolean isActive;
    @CreationTimestamp
    private Instant createdAt;
    @OneToMany(mappedBy = "author", cascade = CascadeType.ALL)
    private Set<Post> posts;
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles;
    // Getters/Setters omitted for brevity
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public void setPasswordHash(String s) { this.passwordHash = s; }
    public void setActive(boolean b) { this.isActive = b; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }
}

// --- src/main/java/com/example/specs/model/Post.java ---
package com.example.specs.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "posts")
public class Post {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String title;
    @Lob
    private String content;
    @Enumerated(EnumType.STRING)
    private PostStatus status;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User author;
    // Getters/Setters omitted for brevity
}

// --- src/main/java/com/example/specs/repository/UserRepository.java ---
package com.example.specs.repository;

import com.example.specs.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {}

// --- src/main/java/com/example/specs/repository/PostRepository.java ---
package com.example.specs.repository;

import com.example.specs.model.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.UUID;

public interface PostRepository extends JpaRepository<Post, UUID>, JpaSpecificationExecutor<Post> {}

// --- src/main/java/com/example/specs/service/UserSearchCriteria.java ---
package com.example.specs.service;

public class UserSearchCriteria {
    private String emailContains;
    private Boolean isActive;
    private String roleName;
    // Getters/Setters
    public String getEmailContains() { return emailContains; }
    public void setEmailContains(String emailContains) { this.emailContains = emailContains; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }
    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
}

// --- src/main/java/com/example/specs/repository/UserSpecifications.java ---
package com.example.specs.repository;

import com.example.specs.model.Role;
import com.example.specs.model.User;
import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

public final class UserSpecifications {

    public static Specification<User> emailContains(String email) {
        return (root, query, criteriaBuilder) -> 
            email == null ? criteriaBuilder.conjunction() : criteriaBuilder.like(root.get("email"), "%" + email + "%");
    }

    public static Specification<User> isActive(Boolean active) {
        return (root, query, criteriaBuilder) -> 
            active == null ? criteriaBuilder.conjunction() : criteriaBuilder.equal(root.get("isActive"), active);
    }

    public static Specification<User> hasRole(String roleName) {
        return (root, query, criteriaBuilder) -> {
            if (roleName == null || roleName.isEmpty()) {
                return criteriaBuilder.conjunction();
            }
            Join<User, Role> roles = root.join("roles");
            return criteriaBuilder.equal(roles.get("name"), roleName);
        };
    }
}

// --- src/main/java/com/example/specs/service/UserManagementService.java ---
package com.example.specs.service;

import com.example.specs.model.Role;
import com.example.specs.model.User;
import com.example.specs.repository.UserRepository;
import com.example.specs.repository.UserSpecifications;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class UserManagementService {

    private final UserRepository userRepo;

    public UserManagementService(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    public User createNewUser(String email, String password, String roleName) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(password); // Hashing omitted
        user.setActive(true);
        
        Role role = new Role();
        role.setName(roleName);
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        user.setRoles(roles);

        return userRepo.save(user);
    }

    @Transactional(readOnly = true)
    public User findById(UUID id) {
        return userRepo.findById(id).orElseThrow(EntityNotFoundException::new);
    }

    public void deleteUser(UUID id) {
        userRepo.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<User> findUsersByCriteria(UserSearchCriteria criteria) {
        Specification<User> spec = Specification.where(UserSpecifications.emailContains(criteria.getEmailContains()))
                .and(UserSpecifications.isActive(criteria.getIsActive()))
                .and(UserSpecifications.hasRole(criteria.getRoleName()));
        return userRepo.findAll(spec);
    }
    
    // Transactional rollback example
    public void registerMultipleUsers(List<User> users) {
        for (User user : users) {
            if (user.getEmail() == null || user.getEmail().isBlank()) {
                throw new RuntimeException("User email cannot be blank. Rolling back transaction.");
            }
            userRepo.save(user);
        }
    }
}