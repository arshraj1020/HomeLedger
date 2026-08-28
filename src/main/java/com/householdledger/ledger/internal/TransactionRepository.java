package com.householdledger.ledger.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code JpaSpecificationExecutor} is what lets Phase 5 compose the FR-5
 * filters dynamically (see {@link TransactionSpecifications}) rather than
 * writing one finder method per combination of criteria — there are 32 of
 * them for five optional filters.
 */
interface TransactionRepository extends JpaRepository<TransactionEntity, UUID>,
        JpaSpecificationExecutor<TransactionEntity> {

    Optional<TransactionEntity> findByIdAndHouseholdId(UUID id, UUID householdId);

    boolean existsByReversesTransactionId(UUID transactionId);

    /**
     * Which of the given transactions have been reversed, answered in one
     * query instead of one per row.
     *
     * <p>Listing a page of 25 transactions would otherwise issue 25 separate
     * {@code existsByReversesTransactionId} calls — the N+1 pattern that puts
     * the PRD §5 target ("p95 under 200ms for reads at 10k postings") at risk
     * as history accumulates.
     */
    @Query("select t.reversesTransactionId from TransactionEntity t "
            + "where t.reversesTransactionId in :transactionIds")
    List<UUID> findReversedTransactionIdsAmong(@Param("transactionIds") Collection<UUID> transactionIds);
}
