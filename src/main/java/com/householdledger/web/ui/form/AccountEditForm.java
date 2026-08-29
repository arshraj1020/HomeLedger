package com.householdledger.web.ui.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Renaming and activating/deactivating an existing account (PRD §FR-2).
 *
 * <p>Separate from {@link AccountForm} because it has no type field, and the
 * absence is the point. PRD §FR-2 offers no way to change an account's type,
 * and it should not: the type decides the sign convention its balance is read
 * with (PRD §10), so changing it would silently restate every posting ever
 * made against the account. A shared form with the type marked read-only in
 * the template would still bind a submitted value, and the protection would
 * live in markup. Leaving the field out means there is nothing to submit.
 *
 * <p>There is no delete, either — PRD §FR-2: "Accounts are never deleted."
 * Deactivating stops new postings while keeping the account and its history
 * in every report.
 */
public class AccountEditForm {

    @NotBlank(message = "Enter a name")
    @Size(max = 100, message = "Names are at most 100 characters")
    private String name;

    private boolean active = true;

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
