package com.householdledger.web.ui.view;

import java.util.UUID;

/**
 * A transaction whose postings do not sum to zero — which should never
 * exist (PRD §3.2). Shown with how far off it is, because an integrity
 * report that says only "something is wrong" leaves the reader no next step.
 */
public record UnbalancedRow(UUID transactionId, String offBy) {
}
