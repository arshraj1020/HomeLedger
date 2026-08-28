package com.householdledger.identity.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface HouseholdRepository extends JpaRepository<HouseholdEntity, UUID> {
}
