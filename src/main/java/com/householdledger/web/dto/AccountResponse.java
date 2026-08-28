package com.householdledger.web.dto;

import com.householdledger.ledger.domain.Account;
import com.householdledger.ledger.domain.AccountType;

import java.util.UUID;

/** Response representation of an account (PRD §6.4). */
public record AccountResponse(UUID id, AccountType type, String name, boolean active) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.id(), account.type(), account.name(), account.active());
    }
}
