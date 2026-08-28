package com.householdledger.ledger.api;

import java.util.List;

/**
 * One page of results.
 *
 * <p>Deliberately not Spring Data's {@code Page}. Every other type in
 * {@code ledger.api} and {@code ledger.domain} is framework-free, and
 * returning a Spring Data type here would drag the persistence framework
 * into the module's published contract — meaning the web layer, the Phase 7
 * UI, and any future consumer would all compile against Spring Data purely
 * because of how the ledger happens to store things today. The mapping from
 * {@code Page} happens inside {@code ledger.internal}, where Spring Data
 * already belongs.
 *
 * @param totalElements total matching rows across all pages, not just this one
 */
public record PageResult<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public PageResult {
        content = List.copyOf(content);
    }

    public boolean hasNext() {
        return page + 1 < totalPages;
    }

    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean isEmpty() {
        return content.isEmpty();
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), page, size, 0L, 0);
    }
}
