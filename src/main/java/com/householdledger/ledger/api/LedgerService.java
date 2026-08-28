package com.householdledger.ledger.api;

import com.householdledger.ledger.domain.Account;
import com.householdledger.ledger.domain.Transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The public interface the {@code ledger} module exposes to the rest of the
 * application (PRD §6.1). Other modules — identity, reporting, web — depend
 * only on this interface and the framework-free types in
 * {@code ledger.domain}; they never see {@code ledger.internal} directly
 * (enforced by {@code ModuleBoundaryArchTest}).
 *
 * <p>This is Phase 1's ledger core: recording and reversing transactions
 * from a raw posting list, and deriving account balances. Phase 4 adds
 * friendlier simple/split entry modes on top of {@link #recordTransaction};
 * Phase 5/6 add querying, filtering, and reporting on top of this.
 */
public interface LedgerService {

    /**
     * Records a new, balanced transaction. Enforces the invariant at the
     * service layer (layer 2 of PRD §3.2's three independent checks) before
     * even attempting persistence, in addition to the domain layer (layer 1,
     * inside {@link Transaction#of}) and the database trigger (layer 3,
     * V1 migration) that runs regardless of what happens here.
     *
     * @throws AccountNotFoundException if any posting references an account
     *         outside {@code householdId}
     * @throws InactiveAccountException if any posting references a
     *         deactivated account
     * @throws com.householdledger.ledger.domain.UnbalancedTransactionException
     *         if the postings do not sum to zero or number fewer than two
     */
    Transaction recordTransaction(UUID householdId, LocalDate occurredOn, String description,
                                   UUID createdBy, List<PostingLine> postings);

    /**
     * Reverses a transaction: creates and persists the exact sign-inverse
     * transaction, linked back to the original (PRD §FR-4).
     *
     * @throws TransactionNotFoundException if {@code transactionId} does not
     *         resolve within {@code householdId}
     * @throws TransactionAlreadyReversedException if it has already been
     *         reversed once
     * @throws ReversalTransactionCannotBeReversedException if
     *         {@code transactionId} is itself a reversal
     */
    Transaction reverseTransaction(UUID householdId, UUID transactionId, UUID reversedBy);

    /**
     * @throws AccountNotFoundException if {@code accountId} does not
     *         resolve within {@code householdId}
     */
    Account getAccount(UUID householdId, UUID accountId);

    /**
     * The account's balance, derived by summing its postings (PRD §3.4 —
     * never a stored value). {@code asOf == null} means "as of now" (all
     * postings, unbounded).
     */
    long accountBalanceMinor(UUID householdId, UUID accountId, LocalDate asOf);
}
