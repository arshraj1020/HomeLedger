package com.householdledger.reporting.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Totals grouped by expense account over a date range (PRD §FR-5).
 *
 * <p>Only categories with at least one posting in the range appear — a
 * summary of what was spent, not a roll-call of every category that exists.
 * An empty {@code lines} list is a valid, successful answer meaning "nothing
 * was spent in this period", not an error.
 *
 * @param totalMinor sum of every line, so a client need not re-add them and
 *                   cannot disagree with the report about its own total
 */
public record ExpenseSummary(
        LocalDate from,
        LocalDate to,
        List<ExpenseLine> lines,
        long totalMinor) {

    public ExpenseSummary {
        lines = List.copyOf(lines);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }
}
