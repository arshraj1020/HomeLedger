package com.householdledger.ledger.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface PostingRepository extends JpaRepository<PostingEntity, UUID> {

    List<PostingEntity> findByTransactionId(UUID transactionId);

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
