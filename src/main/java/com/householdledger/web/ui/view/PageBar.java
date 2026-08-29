package com.householdledger.web.ui.view;

/**
 * Everything the pagination control needs, worked out in Java rather than in
 * the template.
 *
 * <p>Page numbers are the classic place for an off-by-one: the ledger pages
 * from zero (PRD §FR-5's API), people count from one, and the arithmetic for
 * "showing 26–50 of 137" is easy to get subtly wrong in a template where
 * nothing can test it. So it is done once here, and the template only prints.
 *
 * @param displayPage    one-based, for reading
 * @param firstItem      one-based index of the first row on this page; zero
 *                       when the page is empty
 */
public record PageBar(
        int page,
        int displayPage,
        int size,
        long totalElements,
        int totalPages,
        boolean hasPrevious,
        boolean hasNext,
        int previousPage,
        int nextPage,
        long firstItem,
        long lastItem,
        boolean empty) {
}
