package com.householdledger.ledger.api;

import java.util.UUID;

/**
 * One destination of a split entry: an account and its share of the bill,
 * as an unsigned magnitude in minor units (PRD §FR-3, §3.3).
 *
 * <p>Distinct from {@link PostingLine}, which carries an already-signed
 * amount for raw mode. Here the sign is derived by
 * {@code EntryModes.split} — the caller states how much of the bill this
 * category took, not which side of the ledger it lands on.
 */
public record SplitLine(UUID accountId, long amountMinor) {
}
