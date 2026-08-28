package com.householdledger.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/auth/login} (PRD §6.4). */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password) {
}
