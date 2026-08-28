package com.householdledger.ledger.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only aggregate queries the ledger exposes for reporting (PRD §FR-5,
 * §FR-6).
 *
 * <p>This exists because PRD §6.1 makes {@code reporting} its own module,
 * while JPA entities and SQL belong behind {@code ledger.internal}. Rather
 * than the reporting module growing a duplicate set of mappings for the same
 * tables — two definitions of one schema, free to drift — the ledger
 * publishes the aggregates and reporting composes them into reports. The
 * arithmetic happens in the database; the presentation happens in
 * {@code reporting}.
 *
 * <p><b>Every method takes an explicit {@code householdId} and applies it as
 * a mandatory predicate.</b> That matters more here than on a row-returning
 * query: a missing household predicate on an aggregate leaks a *total*
 * without exposing a single row, so it would not show up as someone else's
 * transaction appearing in a list — just a number that is quietly wrong, and
 * wrong in a way that reveals another household's spending. The one method
 * that is deliberately global, {@link #findAllUnbalancedTransactions()}, is
 * a system integrity check with no API surface.
 */
public interface LedgerReportQueries {

    /**
     * Every account in the household with its derived balance, including
     * deactivated accounts and accounts with no postings (PRD §FR-2, §3.4).
     *
     * @param asOf include only postings on transactions dated on or before
     *             this date, inclusive; {@code null} for no upper bound
     */
    List<AccountBalanceView> accountBalances(UUID householdId, LocalDate asOf);

    /**
     * Totals per expense account over an inclusive date range (PRD §FR-5).
     *
     * <p>Only expense accounts, and only those with at least one posting in
     * the range — a summary of what was spent, not a roll-call of every
     * category that exists.
     */
    List<ExpenseTotalView> expenseTotals(UUID householdId, LocalDate from, LocalDate to);

    /**
     * The sum of every posting in the household, which must be zero
     * (PRD §FR-6). Computed in the database rather than by summing postings
     * in Java — at the PRD §5 scale of 10k postings, pulling them all into
     * memory to add them up would be both slower and pointless.
     */
    long trialBalanceMinor(UUID householdId);

    /** How many postings the household has, so a trial balance of zero can be distinguished from an empty ledger. */
    long postingCount(UUID householdId);

    /**
     * Transactions in this household whose postings do not sum to zero.
     * Should always be empty; returned so the trial-balance endpoint can name
     * the culprits rather than merely reporting a non-zero total.
     */
    List<UnbalancedTransaction> findUnbalancedTransactions(UUID householdId);

    /**
     * Every unbalanced transaction across every household — the daily
     * integrity check of PRD §FR-6.
     *
     * <p>Deliberately not household-scoped, and deliberately not reachable
     * from any endpoint: it answers "is the database as a whole still
     * consistent", which is an operator question, not a member's.
     */
    List<UnbalancedTransaction> findAllUnbalancedTransactions();
}
