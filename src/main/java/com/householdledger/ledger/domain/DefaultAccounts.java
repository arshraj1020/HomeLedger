package com.householdledger.ledger.domain;

import java.util.List;

/**
 * The chart of accounts a new household starts with (PRD §FR-2: "Seeded on
 * household creation: a default expense set, an {@code Opening Balances}
 * equity account, and a {@code Cash} asset account").
 *
 * <p>Two of these are named by the PRD and must not be renamed casually:
 * {@code Cash} (ASSET) and {@code Opening Balances} (EQUITY). The latter is
 * what makes it possible to open the ledger with existing balances without
 * inventing money — the counterpart posting goes to equity, so the invariant
 * in PRD §3.2 still holds on day one.
 *
 * <p>The "default expense set" is deliberately left unenumerated by the PRD.
 * The list below is drawn from the examples it does give (§2's electricity
 * and groceries, §3.1's Groceries / Electricity / School Fees) plus the
 * obvious remainder for an Indian household. It is data, not logic:
 * households rename and add accounts freely afterwards (PRD §FR-2), so this
 * is a starting point rather than a fixed taxonomy.
 *
 * <p>Framework-free by design — this lives in {@code domain} so the seeding
 * list is testable without Spring or a database.
 */
public final class DefaultAccounts {

    /** PRD-mandated: the asset account every household starts with. */
    public static final String CASH = "Cash";

    /** PRD-mandated: the equity counterpart for opening balances. */
    public static final String OPENING_BALANCES = "Opening Balances";

    private static final List<SeedAccount> SEED = List.of(
            new SeedAccount(AccountType.ASSET, CASH),
            new SeedAccount(AccountType.EQUITY, OPENING_BALANCES),
            new SeedAccount(AccountType.EXPENSE, "Groceries"),
            new SeedAccount(AccountType.EXPENSE, "Electricity"),
            new SeedAccount(AccountType.EXPENSE, "Rent"),
            new SeedAccount(AccountType.EXPENSE, "School Fees"),
            new SeedAccount(AccountType.EXPENSE, "Transport"),
            new SeedAccount(AccountType.EXPENSE, "Medical"),
            new SeedAccount(AccountType.EXPENSE, "Internet & Phone"),
            new SeedAccount(AccountType.EXPENSE, "Miscellaneous"));

    private DefaultAccounts() {
        // Constants holder.
    }

    /** The seed list, in creation order. Immutable. */
    public static List<SeedAccount> seedAccounts() {
        return SEED;
    }

    /** One account to create at household setup: its type and its name. */
    public record SeedAccount(AccountType type, String name) {
    }
}
