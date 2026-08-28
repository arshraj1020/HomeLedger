package com.householdledger.ledger.api;

import java.util.UUID;

/**
 * A transaction whose postings do not sum to zero — which, if the invariant
 * is holding, should never exist (PRD §3.2, §FR-6).
 *
 * <p>{@code offByMinor} is how far off the sum is, carried alongside the id
 * because a scheduled integrity check that logs only "something is wrong"
 * is far less useful at 3am than one that says by how much.
 */
public record UnbalancedTransaction(UUID transactionId, long offByMinor) {
}
