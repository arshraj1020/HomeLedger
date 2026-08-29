package com.householdledger.web.ui.view;

import java.util.UUID;

/**
 * One choice in an account dropdown.
 *
 * <p>{@code active} is carried so a filter dropdown, which lists every
 * account including retired ones (PRD §FR-2 keeps them in historical
 * queries), can mark them. Entry forms are built from active accounts only,
 * because a deactivated account cannot take a posting and offering it would
 * be offering a choice that is guaranteed to fail.
 */
public record AccountOption(UUID id, String name, boolean active) {
}
