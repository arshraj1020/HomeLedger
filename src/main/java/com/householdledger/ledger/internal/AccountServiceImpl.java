package com.householdledger.ledger.internal;

import com.householdledger.ledger.api.AccountNameAlreadyExistsException;
import com.householdledger.ledger.api.AccountNotFoundException;
import com.householdledger.ledger.api.AccountService;
import com.householdledger.ledger.domain.Account;
import com.householdledger.ledger.domain.AccountType;
import com.householdledger.ledger.domain.DefaultAccounts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Package-private implementation of {@link AccountService} (PRD §FR-2).
 *
 * <p>Note what is absent: any delete or remove method. PRD §FR-2 states
 * "Accounts are never deleted", so the capability simply does not exist in
 * the code — the same reasoning that keeps {@code UPDATE}/{@code DELETE} off
 * postings (PRD §3.5).
 */
@Service
class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;

    AccountServiceImpl(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional
    public Account createAccount(UUID householdId, AccountType type, String name) {
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(type, "type");
        String trimmed = requireUsableName(name);

        if (accountRepository.existsByHouseholdIdAndNameIgnoreCase(householdId, trimmed)) {
            throw new AccountNameAlreadyExistsException(trimmed);
        }

        AccountEntity entity = new AccountEntity(UUID.randomUUID(), householdId, type, trimmed);
        return accountRepository.save(entity).toDomain();
    }

    @Override
    @Transactional
    public Account renameAccount(UUID householdId, UUID accountId, String newName) {
        String trimmed = requireUsableName(newName);
        AccountEntity account = requireAccount(householdId, accountId);

        // Renaming an account to the name it already has is a no-op rather
        // than a conflict — otherwise a PATCH that also toggles active state
        // would spuriously fail.
        if (!account.getName().equalsIgnoreCase(trimmed)
                && accountRepository.existsByHouseholdIdAndNameIgnoreCase(householdId, trimmed)) {
            throw new AccountNameAlreadyExistsException(trimmed);
        }

        account.rename(trimmed);
        return accountRepository.save(account).toDomain();
    }

    @Override
    @Transactional
    public Account setAccountActive(UUID householdId, UUID accountId, boolean active) {
        AccountEntity account = requireAccount(householdId, accountId);

        if (active) {
            account.activate();
        } else {
            account.deactivate();
        }

        return accountRepository.save(account).toDomain();
    }

    @Override
    public List<Account> listAccounts(UUID householdId) {
        // Deactivated accounts are included deliberately: PRD §FR-2 says they
        // "remain in historical queries", and a UI needs them to render past
        // transactions and balances.
        return accountRepository.findByHouseholdId(householdId).stream()
                .map(AccountEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public List<Account> seedDefaultAccounts(UUID householdId) {
        Objects.requireNonNull(householdId, "householdId");

        // Idempotent: only names not already present are created, so calling
        // this twice (or on a partially-seeded household) converges rather
        // than colliding with UNIQUE (household_id, name).
        Set<String> existing = accountRepository.findByHouseholdId(householdId).stream()
                .map(entity -> entity.getName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        List<Account> result = new ArrayList<>();
        for (DefaultAccounts.SeedAccount seed : DefaultAccounts.seedAccounts()) {
            if (existing.contains(seed.name().toLowerCase(Locale.ROOT))) {
                continue;
            }
            AccountEntity entity = new AccountEntity(
                    UUID.randomUUID(), householdId, seed.type(), seed.name());
            result.add(accountRepository.save(entity).toDomain());
        }

        return listAccounts(householdId);
    }

    /**
     * Household scoping (PRD §FR-1): an account id belonging to a different
     * household does not resolve, and the caller gets not-found rather than
     * forbidden so existence is not leaked (PRD §9).
     */
    private AccountEntity requireAccount(UUID householdId, UUID accountId) {
        return accountRepository.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private String requireUsableName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Account name must not be blank");
        }
        return name.trim();
    }
}
