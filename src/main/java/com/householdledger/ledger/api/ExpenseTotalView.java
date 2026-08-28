package com.householdledger.ledger.api;

import java.util.UUID;

/**
 * Total posted to one expense account over a date range (PRD §FR-5's
 * expense summary).
 *
 * <p>Signed, like {@link AccountBalanceView} — expenses are debit-normal so
 * ordinary spending arrives positive, but a category whose only activity was
 * reversed will legitimately total zero, and one carrying a refund can go
 * negative. The reporting layer applies presentation sign; this is the raw
 * arithmetic.
 */
public record ExpenseTotalView(UUID accountId, String accountName, long signedTotalMinor) {
}
