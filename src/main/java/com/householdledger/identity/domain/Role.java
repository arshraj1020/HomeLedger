package com.householdledger.identity.domain;

/**
 * The two roles from PRD §FR-1.
 *
 * <p>{@link #ADMIN} has full write access and may manage accounts and
 * members; {@link #MEMBER} may record transactions and read everything.
 * Note what is deliberately absent: there is no read-only role. Every
 * member of a household can see the whole household's ledger — the
 * "viewer" persona in PRD §2.1 is a description of behaviour, not a
 * permission level.
 */
public enum Role {
    ADMIN,
    MEMBER;

    /**
     * Spring Security convention: authorities are prefixed {@code ROLE_} so
     * that {@code hasRole("ADMIN")} works in expressions.
     */
    public String authority() {
        return "ROLE_" + name();
    }

    public boolean canManageAccountsAndMembers() {
        return this == ADMIN;
    }
}
