package com.householdledger.ledger.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A bucket that money flows into or out of (PRD §3.1). This is the
 * framework-free domain snapshot of an account — used by domain/service
 * validation (e.g. "is this account active and does it belong to this
 * household") — as distinct from the JPA entity that persists it, which
 * lives in {@code ledger.internal} (PRD §6.1: entities stay behind the
 * module's internal boundary).
 */
public final class Account {

    private final UUID id;
    private final UUID householdId;
    private final AccountType type;
    private final String name;
    private final boolean active;

    public Account(UUID id, UUID householdId, AccountType type, String name, boolean active) {
        this.id = Objects.requireNonNull(id, "id");
        this.householdId = Objects.requireNonNull(householdId, "householdId");
        this.type = Objects.requireNonNull(type, "type");
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Account name must not be blank");
        }
        this.active = active;
    }

    public UUID id() {
        return id;
    }

    public UUID householdId() {
        return householdId;
    }

    public AccountType type() {
        return type;
    }

    public String name() {
        return name;
    }

    public boolean active() {
        return active;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Account account)) return false;
        return id.equals(account.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Account{id=" + id + ", type=" + type + ", name='" + name + "', active=" + active + "}";
    }
}
