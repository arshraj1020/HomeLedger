package com.householdledger.web.dto;

import com.householdledger.ledger.api.PageResult;

import java.util.List;
import java.util.function.Function;

/**
 * Envelope for a paged API response (PRD §6.4:
 * {@code GET /api/transactions  # filtered, paginated}).
 *
 * <p>{@code totalElements} and {@code totalPages} are included so a client
 * can render "page 2 of 7" without probing for the end by requesting pages
 * until one comes back empty. {@code hasNext} is redundant with those two but
 * spares every caller from re-deriving the off-by-one.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious) {

    /** Maps a service-layer page into its API representation. */
    public static <S, T> PageResponse<T> from(PageResult<S> result, Function<S, T> mapper) {
        return new PageResponse<>(
                result.content().stream().map(mapper).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages(),
                result.hasNext(),
                result.hasPrevious());
    }
}
