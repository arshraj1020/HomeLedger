package com.householdledger.reporting.api;

import com.householdledger.ledger.domain.AccountType;

import java.util.UUID;

/**
 * One account's line on the balance sheet (PRD §FR-5).
 *
 * <p>Both figures are carried on purpose. {@code balanceMinor} is what a
 * reader should see — the presentation sign of PRD §10 already applied.
 * {@code signedBalanceMinor} is the raw stored figure, debits positive,
 * which is what actually sums to zero across the household. Publishing only
 * the presentation figure would make the balance sheet impossible to
 * reconcile against the trial balance; publishing only the raw one would
 * show a household a credit-card balance of -4,200.
 *
 * @param signFlipped whether presentation negated the stored figure, so a
 *                    client can label the column rather than guess
 */
public record AccountBalanceLine(
        UUID accountId,
        String accountName,
        AccountType type,
        boolean active,
        long balanceMinor,
        long signedBalanceMinor,
        boolean signFlipped) {
}
