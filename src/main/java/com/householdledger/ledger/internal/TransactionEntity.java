package com.householdledger.ledger.internal;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA mapping for the {@code ledger_transaction} table. Deliberately has no
 * {@code amount} field and no mapped {@code postings} collection — postings
 * are persisted as their own rows via {@link PostingRepository}, inserted
 * one at a time by {@link LedgerServiceImpl} within a single database
 * transaction. This is intentional, not an oversight: it is exactly the
 * insert pattern the PRD's highest-risk item (§10) is about, and the
 * deferred constraint trigger (V1 migration) is what makes that pattern
 * safe. See {@code TransactionPersistenceIT} for the integration test that
 * proves it end-to-end through this JPA/Hibernate path.
 */
@Entity
@Table(name = "ledger_transaction")
class TransactionEntity {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    @Column(nullable = false)
    private String description;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reverses_transaction_id")
    private UUID reversesTransactionId;

    protected TransactionEntity() {
        // JPA
    }

    TransactionEntity(UUID id, UUID householdId, LocalDate occurredOn, String description,
                       UUID createdBy, UUID reversesTransactionId) {
        this.id = id;
        this.householdId = householdId;
        this.occurredOn = occurredOn;
        this.description = description;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.reversesTransactionId = reversesTransactionId;
    }

    UUID getId() {
        return id;
    }

    UUID getHouseholdId() {
        return householdId;
    }

    LocalDate getOccurredOn() {
        return occurredOn;
    }

    String getDescription() {
        return description;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    UUID getReversesTransactionId() {
        return reversesTransactionId;
    }
}
