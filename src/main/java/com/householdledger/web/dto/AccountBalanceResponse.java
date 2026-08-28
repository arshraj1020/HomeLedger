package com.householdledger.web.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Response for {@code GET /api/accounts/{id}/balance?asOf=} (PRD §6.4).
 *
 * <p>The balance is reported in signed minor units (paise), consistent with
 * PRD §3.3 — the API never invents a decimal representation, and never a
 * float. Presentation-layer sign conventions per account type belong to the
 * reporting layer (PRD §10), not here.
 *
 * <p>{@code asOf} echoes back the date the balance was computed at, or null
 * when unbounded, so a client can tell a stale cached response from a
 * current one. The value is always derived by summing postings, never read
 * from a stored column (PRD §3.4).
 */
public record AccountBalanceResponse(
        UUID accountId,
        String accountName,
        long balanceMinor,
        LocalDate asOf) {
}
