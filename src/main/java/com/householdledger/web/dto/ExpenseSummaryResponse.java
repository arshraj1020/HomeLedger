package com.householdledger.web.dto;

import com.householdledger.reporting.api.ExpenseSummary;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response for {@code GET /api/reports/expenses?from=&to=} (PRD §6.4).
 *
 * <p>An empty {@code lines} list with {@code totalMinor} zero is a valid
 * 200 response meaning "nothing was spent in this period".
 */
public record ExpenseSummaryResponse(
        LocalDate from,
        LocalDate to,
        List<Line> lines,
        long totalMinor) {

    public record Line(UUID accountId, String accountName, long totalMinor) {
    }

    public static ExpenseSummaryResponse from(ExpenseSummary summary) {
        return new ExpenseSummaryResponse(
                summary.from(),
                summary.to(),
                summary.lines().stream()
                        .map(line -> new Line(line.accountId(), line.accountName(), line.totalMinor()))
                        .toList(),
                summary.totalMinor());
    }
}
