package com.householdledger.ledger.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    Optional<TransactionEntity> findByIdAndHouseholdId(UUID id, UUID householdId);

    boolean existsByReversesTransactionId(UUID transactionId);
}
