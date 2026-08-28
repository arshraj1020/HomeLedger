package com.householdledger.ledger.domain;

/**
 * The five-value account classification from PRD §3.1. Each type carries its
 * normal balance side, used only at the reporting/presentation layer to
 * decide how a signed sum should read to a human ("credit card balance of
 * ₹4,200" vs "-4200"). The domain and database never apply this sign
 * conversion themselves — postings are stored and summed as plain signed
 * minor units throughout (PRD §10 risk: "store signed amounts only; apply
 * presentation sign at the reporting layer, never in the domain").
 */
public enum AccountType {
    ASSET(NormalBalance.DEBIT),
    LIABILITY(NormalBalance.CREDIT),
    INCOME(NormalBalance.CREDIT),
    EXPENSE(NormalBalance.DEBIT),
    EQUITY(NormalBalance.CREDIT);

    private final NormalBalance normalBalance;

    AccountType(NormalBalance normalBalance) {
        this.normalBalance = normalBalance;
    }

    public NormalBalance normalBalance() {
        return normalBalance;
    }

    public enum NormalBalance {
        DEBIT, CREDIT
    }
}
