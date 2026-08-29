package com.householdledger.web.ui.view;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of the transaction list (PRD §FR-7).
 *
 * <p>{@code amount} is the sum of the transaction's positive postings — the
 * headline figure a person means by "how much was it". A transaction has no
 * amount of its own (PRD §3.1: the amount is emergent from the postings), so
 * this is derived for display and nowhere else; nothing in the ledger stores
 * or trusts it.
 *
 * <p>{@code summary} names the accounts the money moved between, so the list
 * is readable without opening every row.
 */
public record TransactionRow(
        UUID id,
        LocalDate occurredOn,
        String description,
        String amount,
        String summary,
        boolean reversed,
        boolean reversal) {
}
