package com.householdledger.ledger.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Translates the three entry modes of PRD §FR-3 into balanced posting sets.
 *
 * <p>This is where the "user never sees the word posting" promise of PRD §2.3
 * is kept. The user picks a source, a destination and an amount; this class
 * turns that into the two-posting transaction the ledger actually stores.
 *
 * <p>Deliberately pure and framework-free, living in {@code domain}: the
 * translation is the part most worth property-testing, and every mode here
 * satisfies one invariant by construction — <b>the postings it returns
 * always sum to exactly zero and always number at least two</b>. That is the
 * same invariant PRD §3.2 enforces at three layers; producing it correctly
 * here means the domain and database checks downstream should never fire for
 * a well-formed request.
 *
 * <p><b>Sign convention.</b> The destination is debited (positive) and the
 * source credited (negative), matching the worked example in PRD §6.4: a
 * grocery expense paid by card produces {@code +420000} on Groceries and
 * {@code -420000} on HDFC Card. The caller supplies unsigned magnitudes;
 * signs are derived here, never typed by a user. That is what lets one mode
 * cover expenses, income, transfers and card payments alike (PRD §FR-3) —
 * only the account *types* differ, not the mechanics.
 */
public final class EntryModes {

    private EntryModes() {
        // Static factory holder.
    }

    /**
     * Simple mode: one source, one destination, one amount (PRD §FR-3).
     *
     * @param fromAccountId the account money leaves — credited (negative)
     * @param toAccountId   the account money arrives at — debited (positive)
     * @param amount        strictly positive magnitude; the sign is applied here
     * @throws IllegalArgumentException if the amount is not strictly positive,
     *         or source and destination are the same account
     */
    public static List<Posting> simple(UUID fromAccountId, UUID toAccountId, Money amount) {
        Objects.requireNonNull(fromAccountId, "fromAccountId");
        Objects.requireNonNull(toAccountId, "toAccountId");
        requireStrictlyPositive(amount, "amount");

        if (fromAccountId.equals(toAccountId)) {
            // Would net to zero on a single account: two postings that cancel,
            // recording no movement of money. Always a mistake, never intent.
            throw new IllegalArgumentException(
                    "Source and destination must be different accounts");
        }

        return List.of(
                Posting.of(toAccountId, amount),
                Posting.of(fromAccountId, amount.negate()));
    }

    /**
     * Split mode: one source funding several destinations, each with its own
     * amount — "a single bill covering several categories" (PRD §FR-3).
     *
     * <p>The source is credited with the exact sum of the allocations, so the
     * result balances no matter how many destinations there are and with no
     * remainder to distribute. (PRD §3.3 notes that division and remainder
     * allocation are not required in v1; nothing here divides.)
     *
     * @throws IllegalArgumentException if there are no allocations, any
     *         allocation is not strictly positive, or the source appears
     *         among the destinations
     */
    public static List<Posting> split(UUID fromAccountId, List<SplitAllocation> allocations) {
        Objects.requireNonNull(fromAccountId, "fromAccountId");
        Objects.requireNonNull(allocations, "allocations");

        if (allocations.isEmpty()) {
            throw new IllegalArgumentException("A split needs at least one destination allocation");
        }

        List<Posting> postings = new ArrayList<>(allocations.size() + 1);
        Money total = Money.ZERO;

        for (SplitAllocation allocation : allocations) {
            Objects.requireNonNull(allocation, "allocation");
            requireStrictlyPositive(allocation.amount(), "allocation amount");

            if (fromAccountId.equals(allocation.accountId())) {
                throw new IllegalArgumentException(
                        "The source account must not also be a split destination");
            }

            postings.add(Posting.of(allocation.accountId(), allocation.amount()));
            total = total.plus(allocation.amount());
        }

        postings.add(Posting.of(fromAccountId, total.negate()));
        return List.copyOf(postings);
    }

    /**
     * Raw mode: an arbitrary, already-signed posting list (PRD §FR-3 —
     * "exposed via API for completeness and used in tests").
     *
     * <p>Unlike the other two modes this applies no sign convention, because
     * the caller has already chosen the signs. It still enforces the two
     * structural rules from FR-3's validation list — at least two postings,
     * and none of them zero (each {@link Posting} rejects a zero amount on
     * construction). Balance itself is left to {@link Transaction}, which
     * refuses to be built unbalanced.
     *
     * @throws IllegalArgumentException if fewer than two postings are supplied
     */
    public static List<Posting> raw(List<Posting> postings) {
        Objects.requireNonNull(postings, "postings");
        if (postings.size() < 2) {
            throw new IllegalArgumentException(
                    "A transaction needs at least two postings; got " + postings.size());
        }
        return List.copyOf(postings);
    }

    /**
     * PRD §FR-3: "Amount strictly positive on each posting."
     *
     * <p>Read as applying to the magnitudes a user supplies in simple and
     * split mode — a set of postings that both summed to zero and were all
     * positive could not exist. Raw mode takes signed values instead, where
     * the corresponding rule is that no posting may be zero.
     */
    private static void requireStrictlyPositive(Money amount, String what) {
        Objects.requireNonNull(amount, what);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "The " + what + " must be strictly positive; got " + amount.minorUnits() + " minor units");
        }
    }

    /** One destination of a split: the account credited-to and its share. */
    public record SplitAllocation(UUID accountId, Money amount) {

        public SplitAllocation {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(amount, "amount");
        }
    }
}
