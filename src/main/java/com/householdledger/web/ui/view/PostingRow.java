package com.householdledger.web.ui.view;

import java.util.UUID;

/**
 * One leg of a transaction on the detail page, presented as a debit or a
 * credit rather than as a signed number.
 *
 * <p>Signed amounts are how the ledger stores postings and how the API
 * publishes them (PRD §3.3), but a two-column debit/credit table is how
 * double entry is read, and it makes the invariant visible: the two columns
 * have to total the same. Only one of {@code debit} and {@code credit} is
 * ever set; the other is blank.
 */
public record PostingRow(
        UUID accountId,
        String accountName,
        String debit,
        String credit,
        boolean isDebit) {
}
