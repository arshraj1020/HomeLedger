package com.householdledger.identity.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MemberRepository extends JpaRepository<MemberEntity, UUID> {

    /** Login lookup. Case-insensitive: email is an identifier, not a password. */
    Optional<MemberEntity> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<MemberEntity> findByHouseholdId(UUID householdId);
}
