package com.householdledger.identity.internal;

import com.householdledger.identity.domain.Member;
import com.householdledger.identity.domain.Role;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping for the {@code member} table (PRD §6.3).
 *
 * <p>The bcrypt hash is readable only within {@code identity.internal}: the
 * getter is package-private and {@link #toDomain()} does not copy it, so the
 * hash cannot reach a DTO, a template, or another module.
 */
@Entity
@Table(name = "member")
class MemberEntity {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MemberEntity() {
        // JPA
    }

    MemberEntity(UUID id, UUID householdId, String name, String email, String passwordHash, Role role) {
        this.id = id;
        this.householdId = householdId;
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getHouseholdId() {
        return householdId;
    }

    String getEmail() {
        return email;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    Role getRole() {
        return role;
    }

    Member toDomain() {
        return new Member(id, householdId, name, email, role);
    }
}
