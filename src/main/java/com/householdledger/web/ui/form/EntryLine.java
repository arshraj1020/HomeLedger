package com.householdledger.web.ui.form;

import java.util.UUID;

/**
 * One editable row of a split or raw entry form: an account and an amount
 * still in the shape the member typed it.
 *
 * <p>The amount stays a {@code String} all the way through binding and is
 * converted by {@code MoneyInput} in the controller. Binding it straight to a
 * {@code long} would hand type conversion to the framework, and a typo would
 * come back as a generic conversion failure with no field context — or, in
 * the worst version, as a silently truncated number. Keeping it text means a
 * bad amount is an ordinary field error next to the field, and the member
 * sees back exactly what they typed.
 *
 * <p>A row with both fields empty is a blank line on the form and is dropped
 * before anything is recorded, which is what lets the form offer more rows
 * than most entries need without requiring JavaScript to add them.
 */
public class EntryLine {

    private UUID accountId;
    private String amount;

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public String getAmount() {
        return amount;
    }

    public void setAmount(String amount) {
        this.amount = amount;
    }

    /** True when the member left this row alone entirely. */
    public boolean isBlank() {
        return accountId == null && (amount == null || amount.isBlank());
    }
}
