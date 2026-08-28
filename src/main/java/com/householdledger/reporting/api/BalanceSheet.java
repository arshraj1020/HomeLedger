package com.householdledger.reporting.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Every account in the household grouped by type, with balances (PRD §FR-5).
 *
 * <p>Includes deactivated accounts and accounts with no postings: PRD §FR-2
 * keeps deactivated accounts in historical queries, and an account at zero
 * is part of the household's structure whether or not it has been used.
 *
 * @param asOf     the cut-off the balances were computed at, echoed back so a
 *                 stored or cached report is self-describing; null means
 *                 unbounded
 * @param sections one per account type, in the PRD §3.1 order, including
 *                 types the household happens to have no accounts of — an
 *                 absent section and an empty one would otherwise be
 *                 indistinguishable to a client
 * @param signedTotalMinor sum of every raw signed balance. This is the same
 *                 quantity the trial balance reports and must be zero for a
 *                 consistent ledger, which makes the balance sheet
 *                 self-checking.
 */
public record BalanceSheet(
        LocalDate asOf,
        List<BalanceSheetSection> sections,
        long signedTotalMinor) {

    public BalanceSheet {
        sections = List.copyOf(sections);
    }

    /** True when the raw signed balances cancel out, as they must (PRD §3.2). */
    public boolean balanced() {
        return signedTotalMinor == 0L;
    }
}
