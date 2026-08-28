package com.householdledger.ledger.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The filter criteria of PRD §FR-5: "List transactions filtered by date
 * range, account, member, and free-text description match."
 *
 * <p>Every field is optional and null means "do not constrain on this" — the
 * criteria compose, so supplying none returns everything in the household
 * and supplying several narrows by all of them at once.
 *
 * <p>Pure and framework-free, in {@code domain}, so the normalisation and
 * validation rules are testable without a database, a Spring context, or any
 * knowledge of how the filter is eventually turned into SQL. The translation
 * into a JPA Specification lives in {@code ledger.internal}; this type knows
 * nothing about it.
 *
 * <p>Normalisation happens once, here, rather than being re-derived at each
 * use site: a blank or whitespace-only search term becomes {@code null}
 * (an empty search is not a search), and a term with surrounding whitespace
 * is trimmed. Without that, {@code q=" "} would silently become
 * {@code LIKE '%%'} and match everything — behaving as no filter at all while
 * looking like one.
 */
public record TransactionFilter(
        LocalDate from,
        LocalDate to,
        UUID accountId,
        UUID memberId,
        String descriptionContains) {

    /** Matches every transaction in the household. */
    public static final TransactionFilter UNFILTERED =
            new TransactionFilter(null, null, null, null, null);

    public TransactionFilter {
        // A reversed range is always a mistake, and silently swapping the
        // bounds would hide it — returning plausible-looking results for a
        // query the caller did not mean.
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "Date range is reversed: 'from' (" + from + ") is after 'to' (" + to + ")");
        }
        descriptionContains = normalise(descriptionContains);
    }

    private static String normalise(String term) {
        if (term == null) {
            return null;
        }
        String trimmed = term.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** True when no criterion is set, so the filter constrains nothing. */
    public boolean isUnfiltered() {
        return from == null && to == null && accountId == null
                && memberId == null && descriptionContains == null;
    }

    public Optional<LocalDate> fromDate() {
        return Optional.ofNullable(from);
    }

    public Optional<LocalDate> toDate() {
        return Optional.ofNullable(to);
    }

    public Optional<UUID> account() {
        return Optional.ofNullable(accountId);
    }

    public Optional<UUID> member() {
        return Optional.ofNullable(memberId);
    }

    public Optional<String> searchTerm() {
        return Optional.ofNullable(descriptionContains);
    }

    /**
     * Whether a given date falls inside this filter's range. Exposed so the
     * range semantics — inclusive at both ends — are stated once and can be
     * asserted directly, rather than being inferred from generated SQL.
     */
    public boolean includesDate(LocalDate date) {
        if (from != null && date.isBefore(from)) {
            return false;
        }
        return to == null || !date.isAfter(to);
    }
}
