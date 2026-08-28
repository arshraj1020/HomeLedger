package com.householdledger.reporting.domain;

import com.householdledger.ledger.domain.AccountType;

/**
 * Converts a stored signed balance into the figure a human should read.
 *
 * <p>This is the single place the project applies an account-type sign
 * convention, and it exists because PRD §10 names exactly this as a medium
 * risk: <i>"Account-type sign conventions confuse the implementation —
 * store signed amounts only; apply presentation sign at the reporting layer,
 * never in the domain."</i> Everything beneath reporting deals in plain
 * signed minor units where debits are positive and credits negative; nothing
 * beneath reporting knows or cares that a credit card's balance reads
 * positive to its owner.
 *
 * <p><b>The conversion.</b> Debit-normal accounts (ASSET, EXPENSE) already
 * read naturally: ₹4,200 of groceries is stored +420000 and displayed
 * +420000. Credit-normal accounts (LIABILITY, INCOME, EQUITY) accumulate
 * negatively — a ₹4,200 card balance is stored -420000, because the money
 * came *from* the card — and a household member asked "what's on the card?"
 * expects "4,200", not "-4,200". So the presentation figure is the stored
 * amount negated for credit-normal types.
 *
 * <p><b>What this deliberately does not do</b> is change any total's
 * meaning. The raw signed figures still sum to zero across a household
 * (that is the trial balance); the presentation figures do not, and are not
 * supposed to. Both are carried through the API so a reader can check one
 * against the other.
 *
 * <p>Pure and framework-free, so the convention is unit- and
 * property-testable without a database.
 */
public final class PresentationSign {

    private PresentationSign() {
        // Conversion holder.
    }

    /**
     * @param signedMinor the stored signed balance, debits positive
     * @return the same amount as a reader of a statement would expect it
     */
    public static long forDisplay(AccountType type, long signedMinor) {
        return switch (type.normalBalance()) {
            case DEBIT -> signedMinor;
            // Math.negateExact rather than unary minus: Long.MIN_VALUE has no
            // positive counterpart, and silently wrapping a balance to a
            // negative number would be the worst possible failure mode for a
            // ledger. Consistent with Money's overflow stance (PRD §3.3).
            case CREDIT -> Math.negateExact(signedMinor);
        };
    }

    /**
     * Whether this account type is presented with its sign flipped. Exposed
     * so a report can label a column honestly rather than a reader having to
     * infer it.
     */
    public static boolean isFlipped(AccountType type) {
        return type.normalBalance() == AccountType.NormalBalance.CREDIT;
    }
}
