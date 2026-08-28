package com.householdledger.ledger.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface PostingRepository extends JpaRepository<PostingEntity, UUID> {

    List<PostingEntity> findByTransactionId(UUID transactionId);

    /**
     * All postings for a page of transactions, in one query.
     *
     * <p>Fetching postings per transaction while rendering a list is the
     * classic N+1: a page of 25 split transactions would issue 25 queries
     * here and 25 more resolving account names. Both are batched instead —
     * see {@code LedgerServiceImpl.toDetails} — which is what keeps listing
     * inside the PRD §5 latency target.
     */
    List<PostingEntity> findByTransactionIdIn(Collection<UUID> transactionIds);

    /**
     * Derives an account's balance by summing its postings (PRD §3.4:
     * "Balances are never stored"). {@code COALESCE} handles an account with
     * no postings yet, which would otherwise return {@code null} rather
     * than zero.
     */
    @Query("select coalesce(sum(p.amountMinor), 0) from PostingEntity p where p.accountId = :accountId")
    long sumAmountMinorByAccountId(@Param("accountId") UUID accountId);

    @Query("select coalesce(sum(p.amountMinor), 0) from PostingEntity p " +
            "join TransactionEntity t on t.id = p.transactionId " +
            "where p.accountId = :accountId and t.occurredOn <= :asOf")
    long sumAmountMinorByAccountIdAsOf(@Param("accountId") UUID accountId, @Param("asOf") java.time.LocalDate asOf);
}
