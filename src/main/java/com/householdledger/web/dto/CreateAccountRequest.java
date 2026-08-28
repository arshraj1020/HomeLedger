package com.householdledger.web.dto;

import com.householdledger.ledger.domain.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/accounts} (PRD §6.4).
 *
 * <p>Note there is no {@code householdId} field, deliberately: the household
 * comes from the verified JWT, never from the request (PRD §FR-1). An
 * account cannot be created into someone else's household because there is
 * nowhere to say so.
 */
public record CreateAccountRequest(
        @NotNull AccountType type,
        @NotBlank @Size(max = 100) String name) {
}
