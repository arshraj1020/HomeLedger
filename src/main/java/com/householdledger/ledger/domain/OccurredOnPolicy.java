package com.householdledger.ledger.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * PRD §FR-3's last validation rule: "Date not in the future beyond a small
 * tolerance."
 *
 * <p>Back-dating is unrestricted and deliberately so — households enter last
 * week's receipts all the time, and the ledger is an audit log of when money
 * moved, not of when someone got round to typing it in.
 *
 * <p>Forward-dating is bounded rather than forbidden. A hard "never in the
 * future" rule would reject legitimate entries for anyone whose device clock
 * or timezone is ahead of the server's: a member in Asia/Kolkata recording a
 * late-evening expense is already on tomorrow's date in UTC terms. One day of
 * slack absorbs every real timezone offset (the widest in use is UTC+14)
 * while still rejecting the mistake this rule exists to catch — a typo'd year
 * or a genuinely speculative future entry, which would silently distort every
 * as-of balance until that date arrived.
 *
 * <p>Pure and framework-free so the boundary is unit-testable without a
 * clock, a context, or a database; the caller supplies "today".
 */
public final class OccurredOnPolicy {

    /** How far ahead of today a transaction date may legitimately sit. */
    public static final int FUTURE_TOLERANCE_DAYS = 1;

    private OccurredOnPolicy() {
        // Policy holder.
    }

    /**
     * @throws FutureDatedTransactionException if {@code occurredOn} is more
     *         than {@link #FUTURE_TOLERANCE_DAYS} beyond {@code today}
     */
    public static void requireNotTooFarInFuture(LocalDate occurredOn, LocalDate today) {
        Objects.requireNonNull(occurredOn, "occurredOn");
        Objects.requireNonNull(today, "today");

        LocalDate latestAllowed = today.plusDays(FUTURE_TOLERANCE_DAYS);
        if (occurredOn.isAfter(latestAllowed)) {
            throw new FutureDatedTransactionException(occurredOn, latestAllowed,
                    ChronoUnit.DAYS.between(today, occurredOn));
        }
    }

    /** Non-throwing form, for callers that want to ask rather than catch. */
    public static boolean isAcceptable(LocalDate occurredOn, LocalDate today) {
        return !occurredOn.isAfter(today.plusDays(FUTURE_TOLERANCE_DAYS));
    }
}
