package com.householdledger.ledger.internal;

import com.householdledger.ledger.api.*;
import com.householdledger.ledger.domain.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final Clock clock;

    LedgerServiceImpl(AccountRepository accountRepository, TransactionRepository transactionRepository,
                       PostingRepository postingRepository, Clock clock) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.postingRepository = postingRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Transaction recordTransaction(UUID householdId, LocalDate occurredOn, String description,
                                          UUID createdBy, List<PostingLine> postingLines) {
        // Raw mode (PRD §FR-3): amounts arrive already signed.
        List<Posting> domainPostings = new ArrayList<>(postingLines.size());
        for (PostingLine line : postingLines) {
            domainPostings.add(Posting.of(line.accountId(), Money.ofMinor(line.amountMinor())));
        }

        return record(householdId, occurredOn, description, createdBy, EntryModes.raw(domainPostings));
    }

    @Override
    @Transactional
    public Transaction recordSimpleTransaction(UUID householdId, LocalDate occurredOn, String description,
                                                UUID createdBy, UUID fromAccountId, UUID toAccountId,
                                                long amountMinor) {
        // Sign derivation lives in the domain, so the same rules apply here
        // and in any future caller (the Phase 7 UI, an importer, a test).
        List<Posting> postings = EntryModes.simple(fromAccountId, toAccountId, Money.ofMinor(amountMinor));
        return record(householdId, occurredOn, description, createdBy, postings);
    }

    @Override
    @Transactional
    public Transaction recordSplitTransaction(UUID householdId, LocalDate occurredOn, String description,
                                               UUID createdBy, UUID fromAccountId, List<SplitLine> destinations) {
        Objects.requireNonNull(destinations, "destinations");

        List<EntryModes.SplitAllocation> allocations = destinations.stream()
                .map(line -> new EntryModes.SplitAllocation(line.accountId(), Money.ofMinor(line.amountMinor())))
                .toList();

        return record(householdId, occurredOn, description, createdBy,
                EntryModes.split(fromAccountId, allocations));
    }

    /**
     * The single path every entry mode funnels through, so the FR-3
     * validation list is applied identically no matter how a transaction was
     * described: date tolerance, then account ownership and activity, then
     * balance, then persistence.
     *
     * <p>Ordering is deliberate — the cheap, database-free checks run first,
     * and account lookups only happen for a request that is otherwise
     * plausible.
     */
    private Transaction record(UUID householdId, LocalDate occurredOn, String description,
                                UUID createdBy, List<Posting> postings) {

        OccurredOnPolicy.requireNotTooFarInFuture(occurredOn, LocalDate.now(clock));

        for (Posting posting : postings) {
            requireActiveAccount(householdId, posting.accountId());
        }

        requireBalanced(postings);

        UUID transactionId = UUID.randomUUID();
        Transaction transaction = Transaction.of(
                transactionId, householdId, occurredOn, description, createdBy, postings);

        persist(transaction, householdId, null);
        return transaction;
    }

    @Override
    public TransactionDetail getTransaction(UUID householdId, UUID transactionId) {
        TransactionEntity entity = transactionRepository.findByIdAndHouseholdId(transactionId, householdId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        return toDetails(List.of(entity)).get(0);
    }

    /**
     * Paged, filtered listing (PRD §FR-5).
     *
     * <p>The sort is fixed at date descending, as FR-5 requires, but with two
     * tiebreakers appended. That is not decoration: several transactions
     * routinely share one date, and a sort with ties is not a total order —
     * Postgres may return tied rows in a different order per query, so a row
     * can appear on both page 1 and page 2, or on neither. Adding
     * {@code createdAt DESC} (most recently entered first, which is what a
     * user expects within a day) and then {@code id} as a final,
     * guaranteed-unique tiebreaker makes paging deterministic.
     */
    @Override
    public PageResult<TransactionDetail> findTransactions(UUID householdId, TransactionFilter filter,
                                                          PageSpec pageSpec) {
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(pageSpec, "pageSpec");

        Pageable pageable = PageRequest.of(pageSpec.page(), pageSpec.size(),
                Sort.by(Sort.Order.desc("occurredOn"),
                        Sort.Order.desc("createdAt"),
                        Sort.Order.asc("id")));

        Page<TransactionEntity> page = transactionRepository.findAll(
                TransactionSpecifications.matching(householdId, filter), pageable);

        return new PageResult<>(
                toDetails(page.getContent()),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
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

        // Deliberately NOT re-checking that the accounts are still active.
        // PRD §FR-2 says deactivated accounts reject *new* postings, but
        // PRD §3.5 makes reversal the only way to correct a mistake — if
        // deactivating an account could strand an erroneous transaction as
        // permanently uncorrectable, the audit trail would be worse, not
        // better. Household scoping still applies via the lookup above.
        //
        // The reversal is dated today rather than inheriting the original's
        // date: it is a new event that happened now, and back-dating it would
        // silently rewrite historical as-of balances.
        UUID reversalId = UUID.randomUUID();
        Transaction reversal = original.reverse(reversalId, LocalDate.now(clock),
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
     * Builds the API view of a whole batch of transactions, resolving account
     * names and reversal state for presentation (PRD §6.4's response shape).
     *
     * <p><b>Three queries, regardless of batch size.</b> Done naively —
     * postings per transaction, account names per posting, a reversal check
     * per transaction — a page of 25 transactions would issue well over 75
     * queries. That is the N+1 pattern, and it is precisely what would break
     * the PRD §5 target ("p95 under 200ms for reads at 10k postings") as a
     * household's history grows. Phase 5's listing endpoint is what makes
     * this matter; the single-transaction read reuses the same path with a
     * batch of one rather than keeping a second, divergent implementation.
     *
     * <p>{@code reversed} is derived by asking which reversals point at these
     * transactions, rather than storing a flag — the same reasoning that
     * keeps balances derived rather than cached (PRD §3.4): a stored flag is
     * a second source of truth that can drift.
     */
    private List<TransactionDetail> toDetails(List<TransactionEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }

        List<UUID> transactionIds = entities.stream().map(TransactionEntity::getId).toList();

        // Query 1: every posting for the batch.
        Map<UUID, List<PostingEntity>> postingsByTransaction = postingRepository
                .findByTransactionIdIn(transactionIds).stream()
                .collect(Collectors.groupingBy(PostingEntity::getTransactionId));

        // Query 2: the account names those postings reference.
        List<UUID> accountIds = postingsByTransaction.values().stream()
                .flatMap(List::stream)
                .map(PostingEntity::getAccountId)
                .distinct()
                .toList();
        Map<UUID, String> namesByAccountId = new HashMap<>();
        for (AccountEntity account : accountRepository.findAllById(accountIds)) {
            namesByAccountId.put(account.getId(), account.getName());
        }

        // Query 3: which of these transactions have been reversed.
        Set<UUID> reversedIds = Set.copyOf(
                transactionRepository.findReversedTransactionIdsAmong(transactionIds));

        return entities.stream()
                .map(entity -> new TransactionDetail(
                        entity.getId(),
                        entity.getOccurredOn(),
                        entity.getDescription(),
                        entity.getCreatedBy(),
                        postingsByTransaction.getOrDefault(entity.getId(), List.of()).stream()
                                .map(posting -> new PostingDetail(
                                        posting.getAccountId(),
                                        namesByAccountId.get(posting.getAccountId()),
                                        posting.getAmountMinor()))
                                .toList(),
                        reversedIds.contains(entity.getId()),
                        entity.getReversesTransactionId()))
                .toList();
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
