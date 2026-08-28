package com.householdledger.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * One destination of a split (PRD §FR-3). {@code amountMinor} is an unsigned
 * magnitude — {@code @Positive} enforces FR-3's "amount strictly positive"
 * at the API edge, before the request reaches the domain.
 */
public record SplitLineRequest(
        @NotNull UUID accountId,
        @NotNull @Positive Long amountMinor) {
}
