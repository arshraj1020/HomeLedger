package com.householdledger.identity.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The top-level container; one per deployment in v1 (PRD §3.1, §1.4).
 * Currency is fixed to INR in v1 but stored per household for forward
 * compatibility (PRD §3.3).
 */
public final class Household {

    private final UUID id;
    private final String name;
    private final String currency;

    public Household(UUID id, String name, String currency) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.currency = Objects.requireNonNull(currency, "currency");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Household name must not be blank");
        }
        if (currency.length() != 3) {
            throw new IllegalArgumentException("Currency must be a 3-letter ISO code; got '" + currency + "'");
        }
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String currency() {
        return currency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Household household)) return false;
        return id.equals(household.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Household{id=" + id + ", name='" + name + "', currency='" + currency + "'}";
    }
}
