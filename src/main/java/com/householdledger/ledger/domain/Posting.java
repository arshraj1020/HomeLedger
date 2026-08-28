package com.householdledger.ledger.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One leg of a {@link Transaction}: an account and a signed amount (PRD
 * §3.1). Postings are immutable value objects at the domain level — there is
 * no setter, no builder, nothing to mutate after construction. Persistence-
 * level immutability (no UPDATE/DELETE) is enforced separately in
 * {@code ledger.internal} and at the database layer (PRD §3.5); this class
 * enforces the value-object half of that guarantee: even in memory, a
 * {@code Posting} cannot be changed once created.
 */
public final class Posting {

    private final UUID accountId;
    private final Money amount;

    private Posting(UUID accountId, Money amount) {
        this.accountId = accountId;
        this.amount = amount;
    }

    /**
     * @throws IllegalArgumentException if {@code amount} is zero. A zero-amount
     *         leg contributes nothing to the transaction and cannot represent
     *         either side of a real movement of money.
     */
    public static Posting of(UUID accountId, Money amount) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        if (amount.isZero()) {
            throw new IllegalArgumentException("Posting amount must not be zero for account " + accountId);
        }
        return new Posting(accountId, amount);
    }

    public UUID accountId() {
        return accountId;
    }

    public Money amount() {
        return amount;
    }

    /** Returns a new posting against the same account with the sign inverted — used by {@link Transaction#reverse}. */
    public Posting inverted() {
        return new Posting(accountId, amount.negate());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Posting posting)) return false;
        return accountId.equals(posting.accountId) && amount.equals(posting.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, amount);
    }

    @Override
    public String toString() {
        return "Posting{accountId=" + accountId + ", amount=" + amount + "}";
    }
}
