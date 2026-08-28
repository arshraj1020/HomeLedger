package com.householdledger.ledger.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An event that moved money (PRD §3.1). Deliberately has no amount field —
 * the amount is an emergent property of its postings, never stored
 * directly. This is the domain-layer half of the three-layer invariant
 * enforcement described in PRD §3.2: this class physically cannot be
 * constructed in an unbalanced state. The other two layers (service-layer
 * validation producing a clean API error, and the Postgres deferred
 * constraint trigger) are implemented in later files/migrations, but this
 * one is the reason a caller cannot even build a bad {@code Transaction}
 * object in memory, regardless of what happens after that.
 *
 * <p>Immutable: the posting list is defensively copied and exposed as an
 * unmodifiable view. There is no method that mutates an existing
 * {@code Transaction} — correction is always "reverse, then record a new
 * one" (PRD §3.5), modeled here as {@link #reverse}, which returns a brand
 * new instance rather than changing this one.
 */
public final class Transaction {

    private final UUID id;
    private final UUID householdId;
    private final LocalDate occurredOn;
    private final String description;
    private final UUID createdBy;
    private final List<Posting> postings;
    private final UUID reversesTransactionId;

    private Transaction(UUID id, UUID householdId, LocalDate occurredOn, String description,
                         UUID createdBy, List<Posting> postings, UUID reversesTransactionId) {
        this.id = id;
        this.householdId = householdId;
        this.occurredOn = occurredOn;
        this.description = description;
        this.createdBy = createdBy;
        this.postings = postings;
        this.reversesTransactionId = reversesTransactionId;
    }

    /**
     * Constructs a new, balanced transaction. This is the sole entry point
     * for creating a {@code Transaction} — there is no other constructor and
     * no setter, so every instance that exists has already passed this
     * validation.
     *
     * @throws UnbalancedTransactionException if there are fewer than two
     *         postings, or the signed amounts do not sum to exactly zero
     *         (PRD §3.2's invariant).
     */
    public static Transaction of(UUID id, UUID householdId, LocalDate occurredOn, String description,
                                  UUID createdBy, List<Posting> postings) {
        return construct(id, householdId, occurredOn, description, createdBy, postings, null);
    }

    private static Transaction construct(UUID id, UUID householdId, LocalDate occurredOn, String description,
                                          UUID createdBy, List<Posting> postings, UUID reversesTransactionId) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(occurredOn, "occurredOn");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(postings, "postings");

        List<Posting> copy = List.copyOf(postings);

        if (copy.size() < 2) {
            throw new UnbalancedTransactionException(
                    "Transaction must have at least two postings; got " + copy.size());
        }

        Money total = Money.ZERO;
        for (Posting p : copy) {
            total = total.plus(p.amount());
        }
        if (!total.isZero()) {
            throw new UnbalancedTransactionException(
                    "Postings sum to " + total.minorUnits() + " minor units; expected 0");
        }

        return new Transaction(id, householdId, occurredOn, description, createdBy, copy, reversesTransactionId);
    }

    /**
     * Produces the mirror transaction described in PRD §3.5: a new
     * transaction whose postings are the exact sign-inverse of this one's,
     * linked back via {@code reversesTransactionId}. Does not mutate this
     * instance or touch persistence — the caller (service layer) is
     * responsible for enforcing "reversed exactly once" and "a reversal
     * cannot itself be reversed" (PRD §FR-4), which require looking at other
     * rows in the database and so cannot be domain-level concerns.
     */
    public Transaction reverse(UUID reversalId, LocalDate reversalOccurredOn, String reversalDescription, UUID reversedBy) {
        List<Posting> inverted = new ArrayList<>(postings.size());
        for (Posting p : postings) {
            inverted.add(p.inverted());
        }
        return construct(reversalId, householdId, reversalOccurredOn, reversalDescription, reversedBy, inverted, this.id);
    }

    public UUID id() {
        return id;
    }

    public UUID householdId() {
        return householdId;
    }

    public LocalDate occurredOn() {
        return occurredOn;
    }

    public String description() {
        return description;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public List<Posting> postings() {
        return Collections.unmodifiableList(postings);
    }

    public UUID reversesTransactionId() {
        return reversesTransactionId;
    }

    public boolean isReversal() {
        return reversesTransactionId != null;
    }

    /** The transaction's total magnitude — the sum of its positive-side postings. Never stored; always derived. */
    public Money amount() {
        Money total = Money.ZERO;
        for (Posting p : postings) {
            if (p.amount().isPositive()) {
                total = total.plus(p.amount());
            }
        }
        return total;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transaction that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Transaction{id=" + id + ", occurredOn=" + occurredOn + ", description='" + description
                + "', postings=" + postings + ", reversesTransactionId=" + reversesTransactionId + "}";
    }
}
