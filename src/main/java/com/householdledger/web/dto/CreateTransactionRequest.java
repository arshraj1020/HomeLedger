package com.householdledger.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/transactions}, covering all three entry
 * modes of PRD §FR-3 — matching the sample in PRD §6.4, where {@code mode}
 * selects which of the remaining fields apply.
 *
 * <p>One request type rather than three endpoints, because §6.4 specifies a
 * single {@code POST /api/transactions} handling "simple | split | raw".
 * Which fields are required therefore depends on the mode, and that is
 * checked in {@link #validateShape()} rather than by bean-validation
 * annotations, which cannot express "required only when mode is SPLIT".
 *
 * <p>There is no {@code householdId} field, deliberately: the household comes
 * from the verified JWT (PRD §FR-1), so a request cannot name one.
 */
public record CreateTransactionRequest(
        @NotNull EntryMode mode,
        @NotNull LocalDate occurredOn,
        @NotBlank @Size(max = 500) String description,

        // SIMPLE and SPLIT: the account money leaves.
        UUID fromAccountId,

        // SIMPLE only.
        UUID toAccountId,
        Long amountMinor,

        // SPLIT only.
        @Valid List<SplitLineRequest> destinations,

        // RAW only.
        @Valid List<PostingLineRequest> postings) {

    /** The three entry modes of PRD §FR-3. */
    public enum EntryMode {
        SIMPLE, SPLIT, RAW
    }

    /**
     * Mode-conditional shape checks. Kept here so the controller stays a
     * thin mapping layer and the failure is a clean 400 rather than a
     * NullPointerException deeper in the stack.
     *
     * @throws IllegalArgumentException if fields required by the chosen mode
     *         are missing
     */
    public void validateShape() {
        switch (mode) {
            case SIMPLE -> {
                require(fromAccountId != null, "SIMPLE mode requires 'fromAccountId'");
                require(toAccountId != null, "SIMPLE mode requires 'toAccountId'");
                require(amountMinor != null, "SIMPLE mode requires 'amountMinor'");
            }
            case SPLIT -> {
                require(fromAccountId != null, "SPLIT mode requires 'fromAccountId'");
                require(destinations != null && !destinations.isEmpty(),
                        "SPLIT mode requires a non-empty 'destinations' list");
            }
            case RAW -> require(postings != null && postings.size() >= 2,
                    "RAW mode requires a 'postings' list with at least two entries");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
