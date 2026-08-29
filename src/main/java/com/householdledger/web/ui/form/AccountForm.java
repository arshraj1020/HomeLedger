package com.householdledger.web.ui.form;

import com.householdledger.ledger.domain.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Backs both the create and the rename/deactivate account forms
 * (PRD §FR-2, §FR-7).
 *
 * <p>Constraints match {@code CreateAccountRequest} exactly — not blank, at
 * most 100 characters — so the two front doors agree on what a valid account
 * name is. They are a courtesy that produces a good error message; the
 * service still enforces the rules, and the database still holds the
 * {@code UNIQUE (household_id, name)} index from V1.
 *
 * <p>{@code type} is only meaningful when creating. PRD §FR-2 has no
 * operation for changing an account's type, and there should not be one: an
 * account's type determines the sign convention its historical balances are
 * read with, so changing it would silently rewrite the meaning of every
 * posting already made against it.
 */
public class AccountForm {

    @NotNull(message = "Choose an account type")
    private AccountType type;

    @NotBlank(message = "Enter a name")
    @Size(max = 100, message = "Names are at most 100 characters")
    private String name;

    private boolean active = true;

    public AccountType getType() {
        return type;
    }

    public void setType(AccountType type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
