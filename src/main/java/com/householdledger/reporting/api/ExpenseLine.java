package com.householdledger.reporting.api;

import java.util.UUID;

/**
 * Total spent on one expense category over the range (PRD §FR-5).
 *
 * <p>Expenses are debit-normal, so ordinary spending is positive and the
 * presentation figure equals the stored one. It can still legitimately be
 * zero (a category whose only transaction was reversed) or negative (a
 * refund exceeding spend in the period), and the report reports that rather
 * than hiding it.
 */
public record ExpenseLine(UUID accountId, String accountName, long totalMinor) {
}
