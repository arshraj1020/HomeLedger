package com.householdledger.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * One leg of a raw-mode transaction (PRD §FR-3). Unlike
 * {@link SplitLineRequest} the amount is already signed and so is NOT
 * constrained positive — raw mode exists precisely to let a caller state
 * both sides explicitly. Zero is still rejected, by {@code Posting} itself.
 */
public record PostingLineRequest(
        @NotNull UUID accountId,
        @NotNull Long amountMinor) {
}
