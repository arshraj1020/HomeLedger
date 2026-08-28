package com.householdledger.ledger.api;

import com.householdledger.ledger.domain.AccountType;

import java.util.UUID;

/**
 * One account with its derived balance, as read from the ledger for
 * reporting (PRD §FR-5's balance sheet).
 *
 * <p>{@code signedBalanceMinor} is the raw signed sum of the account's
 * postings — exactly what the database stores, with no presentation
 * convention applied. PRD §10 is explicit that sign conventions belong to
 * the reporting layer and "never in the domain", so this type deliberately
 * hands over the unmodified figure and lets the reporting module decide how
 * a human should read it.
 *
 * <p>Includes deactivated accounts and accounts with no postings at all
 * (balance zero) — PRD §FR-2 keeps deactivated accounts "in historical
 * queries", and a chart of accounts that silently drops empty categories
 * would misrepresent the household's structure.
 */
public record AccountBalanceView(
        UUID accountId,
        String accountName,
        AccountType type,
        boolean active,
        long signedBalanceMinor) {
}
