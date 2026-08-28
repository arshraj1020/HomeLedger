/**
 * Public interface the {@code ledger} module exposes to other modules
 * (identity, reporting, web). Other modules may depend only on classes in
 * this package and {@code ledger.domain}, never on {@code ledger.internal} —
 * enforced by an ArchUnit rule (see {@code ArchitectureTest}). Populated in
 * Phase 1.
 */
package com.householdledger.ledger.api;
