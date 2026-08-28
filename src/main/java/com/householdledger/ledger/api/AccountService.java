package com.householdledger.ledger.api;

import com.householdledger.ledger.domain.Account;
import com.householdledger.ledger.domain.AccountType;

import java.util.List;
import java.util.UUID;

/**
 * Account management (PRD §FR-2), exposed to other modules alongside
 * {@link LedgerService}.
 *
 * <p>Kept as a separate interface rather than growing {@code LedgerService}:
 * recording transactions and administering the chart of accounts are
 * different concerns with different authorisation rules — PRD §FR-1 lets any
 * MEMBER record transactions but reserves account management to ADMIN.
 *
 * <p>Every method takes {@code householdId} as its first argument, and
 * callers must pass the value from the verified JWT rather than anything
 * user-supplied (PRD §FR-1). An id belonging to another household simply
 * fails to resolve, producing {@link AccountNotFoundException} — 404, not
 * 403, so existence is never leaked (PRD §9).
 *
 * <p><b>There is no delete.</b> PRD §FR-2: "Accounts are never deleted."
 * Deactivation is the only way to retire an account, and deactivated
 * accounts remain visible in historical queries.
 */
public interface AccountService {

    /**
     * @throws AccountNameAlreadyExistsException if the name is taken within
     *         this household (compared case-insensitively)
     * @throws IllegalArgumentException if the name is blank
     */
    Account createAccount(UUID householdId, AccountType type, String name);

    /**
     * @throws AccountNotFoundException if the account is not in this household
     * @throws AccountNameAlreadyExistsException if the new name is taken by a
     *         different account in this household
     */
    Account renameAccount(UUID householdId, UUID accountId, String newName);

    /**
     * Activates or deactivates an account. A deactivated account rejects new
     * postings (enforced by {@code LedgerService.recordTransaction}) but is
     * still returned by {@link #listAccounts} and still contributes its
     * historical postings to balances — PRD §FR-2.
     *
     * @throws AccountNotFoundException if the account is not in this household
     */
    Account setAccountActive(UUID householdId, UUID accountId, boolean active);

    /** All accounts in the household, active and deactivated alike. */
    List<Account> listAccounts(UUID householdId);

    /**
     * Creates the starting chart of accounts for a newly created household
     * (PRD §FR-2: "Seeded on household creation: a default expense set, an
     * {@code Opening Balances} equity account, and a {@code Cash} asset
     * account").
     *
     * <p>Idempotent: seeding a household that already has accounts adds only
     * the missing names, so a partially-seeded household cannot end up
     * duplicated or half-built.
     *
     * @return the accounts that now exist for the household
     */
    List<Account> seedDefaultAccounts(UUID householdId);
}
