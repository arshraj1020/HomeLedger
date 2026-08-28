package com.householdledger.ledger.api;

import java.util.UUID;

/**
 * Thrown when an account id does not resolve within the caller's household —
 * including when the id belongs to a real account in a *different*
 * household. Deliberately does not distinguish "doesn't exist" from
 * "belongs to someone else": PRD §FR-1 requires cross-household access to be
 * impossible even with a forged id, and PRD §9 requires a 404 (not 403) so
 * as not to leak existence. The web layer (Phase 4+) maps this to 404.
 */
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(UUID accountId) {
        super("No account " + accountId + " in this household");
    }
}
