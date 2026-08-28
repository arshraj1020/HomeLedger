package com.householdledger.identity.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A person in the household with login credentials and a role (PRD §3.1).
 *
 * <p>Members do not hold balances — they are actors, not accounts. This is
 * the framework-free snapshot other modules see; the password hash is
 * deliberately NOT a field here, so a member object can never leak
 * credential material into a DTO, a log line, or a template. The hash lives
 * only on the JPA entity inside {@code identity.internal}.
 */
public final class Member {

    private final UUID id;
    private final UUID householdId;
    private final String name;
    private final String email;
    private final Role role;

    public Member(UUID id, UUID householdId, String name, String email, Role role) {
        this.id = Objects.requireNonNull(id, "id");
        this.householdId = Objects.requireNonNull(householdId, "householdId");
        this.name = Objects.requireNonNull(name, "name");
        this.email = Objects.requireNonNull(email, "email");
        this.role = Objects.requireNonNull(role, "role");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Member name must not be blank");
        }
        if (email.isBlank()) {
            throw new IllegalArgumentException("Member email must not be blank");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID householdId() {
        return householdId;
    }

    public String name() {
        return name;
    }

    public String email() {
        return email;
    }

    public Role role() {
        return role;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Member member)) return false;
        return id.equals(member.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        // Email included (it is the login identifier and useful in logs);
        // there is no credential material on this type to leak.
        return "Member{id=" + id + ", email='" + email + "', role=" + role + "}";
    }
}
