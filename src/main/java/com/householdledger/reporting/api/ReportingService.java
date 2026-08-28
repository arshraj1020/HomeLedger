package com.householdledger.reporting.api;

import com.householdledger.reporting.domain.DateRange;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The three reports of PRD §FR-5 and §FR-6, exposed to the web layer.
 *
 * <p>Every method takes the household id explicitly, and callers must pass
 * the value from the verified JWT rather than anything a client supplied
 * (PRD §FR-1). This matters more for reports than for row queries: an
 * aggregate with a missing or wrong household predicate leaks a *total*
 * without ever showing a row, so the leak would look like a slightly wrong
 * number rather than like someone else's data.
 *
 * <p>Reports never 404. A household with no accounts, or no spending in the
 * period, gets a successful empty report — "nothing here" is an answer, and
 * a 404 would additionally be a poor one, since it would imply the household
 * itself was missing.
 */
public interface ReportingService {

    /**
     * All accounts grouped by type with balances (PRD §FR-5).
     *
     * @param asOf include only postings on transactions dated on or before
     *             this date, inclusive; {@code null} for the current position.
     *             A future date is accepted and simply includes everything —
     *             the ledger refuses far-future transactions at write time
     *             (PRD §FR-3), so there is nothing beyond today to exclude.
     */
    BalanceSheet balanceSheet(UUID householdId, LocalDate asOf);

    /** Totals grouped by expense account over an inclusive date range (PRD §FR-5). */
    ExpenseSummary expenseSummary(UUID householdId, DateRange range);

    /** The household's trial balance, which must be zero (PRD §FR-6). */
    TrialBalance trialBalance(UUID householdId);
}
