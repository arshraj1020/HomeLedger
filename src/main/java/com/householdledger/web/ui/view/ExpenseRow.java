package com.householdledger.web.ui.view;

import java.util.UUID;

/**
 * One category on the expense summary (PRD §FR-5).
 *
 * <p>{@code sharePercent} is a whole-number share of the period's total,
 * carried so the bar next to each row has a width. It is presentation only:
 * the percentages are rounded and need not add to 100, which is why the
 * report's own total comes from {@code totalMinor} and never from re-adding
 * these.
 *
 * <p>{@code barClass} is that share snapped to the nearest five percent and
 * expressed as a CSS class name. The width has to arrive as a class rather
 * than as a {@code style} attribute because the UI is served with a Content
 * Security Policy that forbids inline styles — a policy the application can
 * afford precisely because nothing here needs inline anything.
 */
public record ExpenseRow(
        UUID accountId,
        String accountName,
        String total,
        long totalMinor,
        int sharePercent,
        String barClass,
        boolean negative) {
}
