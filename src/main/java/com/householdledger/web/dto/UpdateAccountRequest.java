package com.householdledger.web.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /api/accounts/{id}} — "rename,
 * activate/deactivate" (PRD §6.4).
 *
 * <p>Both fields are optional, which is what makes this a PATCH rather than
 * a PUT: send {@code name} alone to rename, {@code active} alone to
 * deactivate, or both. A null field means "leave unchanged" — it never means
 * "clear".
 *
 * <p>There is no delete endpoint, by design: PRD §FR-2 says accounts are
 * never deleted, so deactivation via this endpoint is the only retirement
 * path.
 */
public record UpdateAccountRequest(
        @Size(max = 100) String name,
        Boolean active) {

    public boolean isEmpty() {
        return name == null && active == null;
    }
}
