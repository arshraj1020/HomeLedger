package com.householdledger.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/auth/refresh} and {@code /logout}. */
public record RefreshRequest(@NotBlank String refreshToken) {
}
