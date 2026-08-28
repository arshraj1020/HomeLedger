package com.householdledger.ledger.internal;

import com.householdledger.ledger.domain.Account;
import com.householdledger.ledger.domain.AccountType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping for the {@code account} table (PRD §6.3). Package-private:
 * nothing outside {@code ledger.internal} may reference this class directly
 * (enforced by {@code ModuleBoundaryArchTest}) — other modules see accounts
 * only through {@link com.householdledger.ledger.api.LedgerService} and the
 * framework-free {@link Account} domain snapshot it returns.
 */
@Entity
@Table(name = "account", uniqueConstraints = @UniqueConstraint(columnNames = {"household_id", "name"}))
class AccountEntity {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(nullable = false)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountEntity() {
        // JPA
    }

    AccountEntity(UUID id, UUID householdId, AccountType type, String name) {
        this.id = id;
        this.householdId = householdId;
        this.type = type;
        this.name = name;
        this.active = true;
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getHouseholdId() {
        return householdId;
    }

    AccountType getType() {
        return type;
    }

    String getName() {
        return name;
    }

    boolean isActive() {
        return active;
    }

    void rename(String newName) {
        this.name = newName;
    }

    void deactivate() {
        this.active = false;
    }

    void activate() {
        this.active = true;
    }

    /** Maps to the framework-free domain snapshot exposed outside this module. */
    Account toDomain() {
        return new Account(id, householdId, type, name, active);
    }
}
