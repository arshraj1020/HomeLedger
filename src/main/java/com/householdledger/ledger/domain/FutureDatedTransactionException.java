package com.householdledger.ledger.domain;

import java.time.LocalDate;

/**
 * Thrown when a transaction is dated further into the future than
 * {@link OccurredOnPolicy#FUTURE_TOLERANCE_DAYS} allows (PRD §FR-3).
 *
 * <p>Lives in {@code domain} alongside
 * {@link UnbalancedTransactionException} because it expresses a rule about
 * what a transaction may be, not about how it is delivered. The web layer
 * maps it to a 422 in the RFC 7807 error contract (PRD §6.4).
 */
public class FutureDatedTransactionException extends RuntimeException {

    private final LocalDate occurredOn;
    private final LocalDate latestAllowed;

    public FutureDatedTransactionException(LocalDate occurredOn, LocalDate latestAllowed, long daysAhead) {
        super("Transaction date " + occurredOn + " is " + daysAhead
                + " days in the future; the latest accepted date is " + latestAllowed);
        this.occurredOn = occurredOn;
        this.latestAllowed = latestAllowed;
    }

    public LocalDate occurredOn() {
        return occurredOn;
    }

    public LocalDate latestAllowed() {
        return latestAllowed;
    }
}
