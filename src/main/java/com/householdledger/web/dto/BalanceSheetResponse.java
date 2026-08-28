package com.householdledger.web.dto;

import com.householdledger.ledger.domain.AccountType;
import com.householdledger.reporting.api.BalanceSheet;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response for {@code GET /api/reports/balance-sheet?asOf=} (PRD §6.4).
 *
 * <p>Each line carries both the presentation figure and the raw signed one,
 * plus {@code signedTotalMinor} across the whole sheet — which must be zero.
 * That makes the balance sheet self-checking: a client can verify the report
 * against the same invariant the trial-balance endpoint reports on, without
 * a second call.
 */
public record BalanceSheetResponse(
        LocalDate asOf,
        List<Section> sections,
        long signedTotalMinor,
        boolean balanced) {

    public record Section(
            AccountType type,
            List<Line> accounts,
            long sectionTotalMinor,
            long signedSectionTotalMinor) {
    }

    public record Line(
            UUID accountId,
            String accountName,
            AccountType type,
            boolean active,
            long balanceMinor,
            long signedBalanceMinor,
            boolean signFlipped) {
    }

    public static BalanceSheetResponse from(BalanceSheet sheet) {
        return new BalanceSheetResponse(
                sheet.asOf(),
                sheet.sections().stream()
                        .map(section -> new Section(
                                section.type(),
                                section.accounts().stream()
                                        .map(line -> new Line(
                                                line.accountId(), line.accountName(), line.type(),
                                                line.active(), line.balanceMinor(),
                                                line.signedBalanceMinor(), line.signFlipped()))
                                        .toList(),
                                section.sectionTotalMinor(),
                                section.signedSectionTotalMinor()))
                        .toList(),
                sheet.signedTotalMinor(),
                sheet.balanced());
    }
}
