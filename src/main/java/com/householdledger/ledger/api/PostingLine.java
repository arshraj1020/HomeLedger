package com.householdledger.ledger.api;

import java.util.UUID;

/**
 * One requested leg of a transaction to be recorded: an account and a
 * signed amount in minor units. This is the "raw" entry mode from PRD
 * §FR-3 ("arbitrary posting list... exposed via API for completeness and
 * used in tests") — the primitive that Phase 4's friendlier simple/split
 * entry modes will be built on top of.
 */
public record PostingLine(UUID accountId, long amountMinor) {
}
