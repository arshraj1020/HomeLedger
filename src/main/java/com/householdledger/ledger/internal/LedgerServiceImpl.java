package com.householdledger.ledger.internal;

import com.householdledger.ledger.api.*;
import com.householdledger.ledger.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Package-private implementation of {@link LedgerService} — not reachable
 * from outside {@code ledger.internal} even though it is a Spring
 * {@code @Service} bean; Spring's component scan can instantiate
 * package-private classes, it does not require public visibility (PRD
 * §6.1 module boundary).
 *
 * <p>Layer 2 of the invariant (PRD §3.2): {@link #requireBalanced} runs
 * before {@link Transaction#of} is ever called, so a bad posting set is
 * rejected here with a clean, service-level exception even though the
 * domain layer (layer 1) would also refuse to construct it. Layer 3 — the
 * deferred Postgres trigger in the V1 migration — is what actually holds
 * regardless of this class's correctness; see {@code TransactionPersistenceIT}.
 *
 * <p>Postings are saved one row at a time in the loop below, deliberately —
 * this exercises exactly the Hibernate flush pattern the PRD's highest-risk
 * item (§10) is concerned with. The whole method runs inside one
 * {@code @Transactional} boundary, so the deferred trigger sees the
 * complete posting set at commit regardless of insert order.
 */
@Service
class LedgerServiceImpl implements LedgerService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final PostingRepository postingRepository;

    LedgerServiceImpl(AccountRepository accountRepository, TransactionRepository transactionRepository,
                       PostingRepository postingRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.postingRepository = postingRepository;
    }

    @Override
    @Transactional
    public Transaction recordTransaction(UUID householdId, LocalDate occurredOn, String description,
                                          UUID createdBy, List<PostingLine> postingLines) {
        List<Posting> domainPostings = new ArrayList<>(postingLines.size());
        for (PostingLine line : postingLines) {
            AccountEntity account = requireActiveAccount(householdId, line.accountId());
            domainPostings.add(Posting.of(account.getId(), Money.ofMinor(line.amountMinor())));
        }

        requireBalanced(domainPostings);

        UUID transactionId = UUID.randomUUID();
        Transaction transaction = Transaction.of(transactionId, householdId, occurredOn, description, createdBy, domainPostings);

        persist(transaction, householdId, null);
        return transaction;
    }

    @Override
    @Transactional
    public Transaction reverseTransaction(UUID householdId, UUID transactionId, UUID reversedBy) {
        TransactionEntity originalEntity = transactionRepository.findByIdAndHouseholdId(transactionId, householdId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        if (originalEntity.getReversesTransactionId() != null) {
            throw new ReversalTransactionCannotBeReversedException(transactionId);
        }
        if (transactionRepository.existsByReversesTransactionId(transactionId)) {
            throw new TransactionAlreadyReversedException(transactionId);
        }

        List<PostingEntity> postingEntities = postingRepository.findByTransactionId(transactionId);
        List<Posting> originalPostings = new ArrayList<>(postingEntities.size());
        for (PostingEntity pe : postingEntities) {
            originalPostings.add(Posting.of(pe.getAccountId(), Money.ofMinor(pe.getAmountMinor())));
        }

        Transaction original = Transaction.of(originalEntity.getId(), originalEntity.getHouseholdId(),
                originalEntity.getOccurredOn(), originalEntity.getDescription(), originalEntity.getCreatedBy(),
                originalPostings);

        UUID reversalId = UUID.randomUUID();
        Transaction reversal = original.reverse(reversalId, LocalDate.now(),
                "Reversal of: " + original.description(), reversedBy);

        persist(reversal, householdId, original.id());
        return reversal;
    }

    @Override
    public Account getAccount(UUID householdId, UUID accountId) {
        return accountRepository.findByIdAndHouseholdId(accountId, householdId)
                .map(AccountEntity::toDomain)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    @Override
    public long accountBalanceMinor(UUID householdId, UUID accountId, LocalDate asOf) {
        // Household-scoping check first, so an id from another household 404s
        // rather than silently returning a (correctly zero, but leaking) balance.
        accountRepository.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        return asOf == null
                ? postingRepository.sumAmountMinorByAccountId(accountId)
                : postingRepository.sumAmountMinorByAccountIdAsOf(accountId, asOf);
    }

    private AccountEntity requireActiveAccount(UUID householdId, UUID accountId) {
        AccountEntity account = accountRepository.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!account.isActive()) {
            throw new InactiveAccountException(accountId);
        }
        return account;
    }

    /**
     * Layer 2 of the invariant: an explicit, independent balance check
     * before persistence is attempted, distinct from the one inside
     * {@link Transaction#of} (layer 1). Deliberately duplicated per PRD
     * §3.2 rather than relying solely on the domain layer.
     */
    private void requireBalanced(List<Posting> postings) {
        if (postings.size() < 2) {
            throw new UnbalancedTransactionException(
                    "Transaction must have at least two postings; got " + postings.size());
        }
        Money total = Money.ZERO;
        for (Posting p : postings) {
            total = total.plus(p.amount());
        }
        if (!total.isZero()) {
            throw new UnbalancedTransactionException(
                    "Postings sum to " + total.minorUnits() + " minor units; expected 0");
        }
    }

    private void persist(Transaction transaction, UUID householdId, UUID reversesTransactionId) {
        TransactionEntity entity = new TransactionEntity(transaction.id(), householdId, transaction.occurredOn(),
                transaction.description(), transaction.createdBy(), reversesTransactionId);
        transactionRepository.save(entity);

        // One row at a time, deliberately — see class Javadoc.
        for (Posting posting : transaction.postings()) {
            PostingEntity postingEntity = new PostingEntity(UUID.randomUUID(), transaction.id(),
                    posting.accountId(), posting.amount().minorUnits());
            postingRepository.save(postingEntity);
        }
    }
}
