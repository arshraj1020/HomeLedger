package com.householdledger.reporting.api;

import com.householdledger.ledger.domain.AccountType;

import java.util.List;

/**
 * All accounts of one type, with a section total (PRD §FR-5: "all accounts
 * grouped by type with balances").
 *
 * <p>{@code sectionTotalMinor} is the sum of the presentation figures, so it
 * reads the way the lines beneath it do; {@code signedSectionTotalMinor}
 * sums the raw figures, and it is the signed totals across all five sections
 * that add to zero.
 */
public record BalanceSheetSection(
        AccountType type,
        List<AccountBalanceLine> accounts,
        long sectionTotalMinor,
        long signedSectionTotalMinor) {

    public BalanceSheetSection {
        accounts = List.copyOf(accounts);
    }

    public boolean isEmpty() {
        return accounts.isEmpty();
    }
}
