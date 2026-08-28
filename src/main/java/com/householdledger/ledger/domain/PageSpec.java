package com.householdledger.ledger.domain;

/**
 * A requested page of results (PRD §FR-5: "Paginated, sorted by date
 * descending").
 *
 * <p>Both values are <b>clamped rather than rejected</b>. A client asking
 * for page {@code -3} or size {@code 1000000} has made a mistake, but
 * failing the whole request over it helps nobody — and an unbounded page
 * size is the more serious problem: it lets a single request pull every
 * transaction a household has ever recorded into memory, which is exactly
 * how the PRD §5 latency target ("p95 under 200ms for reads at 10k
 * postings") gets missed. Clamping makes that impossible by construction, so
 * no caller can opt out of it.
 *
 * <p>Sort order is deliberately not a parameter. PRD §FR-5 fixes it at date
 * descending, and a client-controlled sort would let a caller choose an
 * unindexed ordering.
 */
public record PageSpec(int page, int size) {

    /** Chosen to fill a screen without a scroll marathon. */
    public static final int DEFAULT_SIZE = 25;

    /** Upper bound on rows per request; see the class note on §5 latency. */
    public static final int MAX_SIZE = 200;

    public static final int MIN_SIZE = 1;

    public PageSpec {
        page = Math.max(0, page);
        size = Math.clamp(size, MIN_SIZE, MAX_SIZE);
    }

    /** The default first page. */
    public static PageSpec first() {
        return new PageSpec(0, DEFAULT_SIZE);
    }

    /**
     * Builds a spec from optional client input, applying the default size
     * when none was supplied.
     */
    public static PageSpec of(Integer page, Integer size) {
        return new PageSpec(
                page == null ? 0 : page,
                size == null ? DEFAULT_SIZE : size);
    }

    /** Zero-based index of the first row on this page. */
    public long offset() {
        return (long) page * size;
    }
}
