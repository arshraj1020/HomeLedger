package com.householdledger.reporting.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * An inclusive date range for a report (PRD §FR-5's expense summary, "over a
 * date range").
 *
 * <p>Inclusive at both ends, deliberately and explicitly. A range whose
 * endpoint semantics are implicit is where off-by-one reporting bugs live:
 * a month report for 1–31 March that silently excludes the 31st understates
 * the month, and nothing about the number looks wrong.
 *
 * <p>Both bounds are required. Unlike the transaction filter of Phase 5,
 * where an open-ended search is a reasonable thing to ask for, an expense
 * summary without a range is not a summary of anything in particular — and
 * PRD §6.4 spells the endpoint as {@code /api/reports/expenses?from=&to=},
 * with both present.
 *
 * <p>Pure and framework-free; the web layer maps the resulting
 * {@link IllegalArgumentException} to a 400 through the existing handler.
 */
public record DateRange(LocalDate from, LocalDate to) {

    public DateRange {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");

        // Reversed rather than silently swapped: swapping would answer a
        // question the caller did not ask, with a plausible-looking number.
        if (from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "Date range is reversed: 'from' (" + from + ") is after 'to' (" + to + ")");
        }
    }

    /** A single-day range. Valid: {@code from} equal to {@code to} is a range of one day, not an empty one. */
    public static DateRange singleDay(LocalDate day) {
        return new DateRange(day, day);
    }

    public boolean includes(LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    /** Length in days, counting both endpoints — so a single-day range is 1. */
    public long lengthInDays() {
        return java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
    }
}
