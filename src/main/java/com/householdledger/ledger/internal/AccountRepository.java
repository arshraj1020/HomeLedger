package com.householdledger.ledger.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    List<AccountEntity> findByHouseholdId(UUID householdId);

    Optional<AccountEntity> findByIdAndHouseholdId(UUID id, UUID householdId);

    boolean existsByHouseholdIdAndNameIgnoreCase(UUID householdId, String name);
}
