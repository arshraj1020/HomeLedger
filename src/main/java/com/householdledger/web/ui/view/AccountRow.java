package com.householdledger.web.ui.view;

import com.householdledger.ledger.domain.AccountType;

import java.util.UUID;

/**
 * One account as a page renders it (PRD §FR-7's dashboard and account list).
 *
 * <p>The balance arrives as three separate things because they answer three
 * different questions and conflating them is how a balance sheet stops
 * reconciling. {@code balance} is the presentation figure of PRD §10, already
 * converted to a string — what a member reads. {@code signedBalanceMinor} is
 * the raw stored figure, which is the one that sums to zero across the
 * household. {@code signFlipped} says whether the two differ, so the page can
 * say so rather than leaving a reader to wonder why the card balance is
 * positive here and negative in the trial balance.
 *
 * <p>Money is a finished string, never a number: no template does arithmetic
 * on money (see {@code MoneyFormat}).
 */
public record AccountRow(
        UUID id,
        String name,
        AccountType type,
        boolean active,
        String balance,
        long signedBalanceMinor,
        boolean signFlipped,
        boolean negative) {
}
