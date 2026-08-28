package com.householdledger.ledger.internal;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping for the {@code posting} table. Deliberately has no setters at
 * all, not even package-private ones, beyond what the constructor sets —
 * mirrors the domain-level {@link com.householdledger.ledger.domain.Posting}
 * immutability (PRD §3.5) at the persistence layer too. The database itself
 * additionally rejects UPDATE/DELETE via trigger (V1 migration), so this
 * class matching that behavior in Java is defense in depth, not the primary
 * guarantee.
 */
@Entity
@Table(name = "posting")
class PostingEntity {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PostingEntity() {
        // JPA
    }

    PostingEntity(UUID id, UUID transactionId, UUID accountId, long amountMinor) {
        this.id = id;
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.amountMinor = amountMinor;
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getTransactionId() {
        return transactionId;
    }

    UUID getAccountId() {
        return accountId;
    }

    long getAmountMinor() {
        return amountMinor;
    }
}
