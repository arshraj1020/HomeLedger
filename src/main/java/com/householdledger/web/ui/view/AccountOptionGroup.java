package com.householdledger.web.ui.view;

import java.util.List;

/**
 * Account choices under one type heading, for an {@code <optgroup>}.
 *
 * <p>A household's chart of accounts is a mix of asset, expense, income,
 * liability and equity accounts, and a flat alphabetical list makes choosing
 * the right one harder than it needs to be — "Fuel" and "Fuel Card" sit
 * together but mean opposite sides of an entry. Grouping by type puts the
 * accounting structure in front of the person making the choice.
 */
public record AccountOptionGroup(String heading, List<AccountOption> options) {

    public AccountOptionGroup {
        options = List.copyOf(options);
    }
}
