package com.householdledger.web.ui.view;

import com.householdledger.ledger.domain.AccountType;

import java.util.List;

/**
 * All accounts of one type with their subtotal, mirroring the reporting
 * module's {@code BalanceSheetSection} (PRD §FR-5: "all accounts grouped by
 * type with balances").
 *
 * <p>Empty sections are kept rather than dropped. A household with no
 * liabilities should see an empty Liabilities heading, because "you have no
 * debts" and "this application has no concept of debts" look identical when
 * the section is simply absent.
 */
public record AccountSection(
        AccountType type,
        String heading,
        List<AccountRow> accounts,
        String total,
        boolean empty) {

    public AccountSection {
        accounts = List.copyOf(accounts);
    }
}
