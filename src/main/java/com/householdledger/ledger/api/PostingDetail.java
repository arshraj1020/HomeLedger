package com.householdledger.ledger.api;

import java.util.UUID;

/**
 * One posting as it appears in an API response, matching the shape in
 * PRD §6.4's sample: account id, resolved account name, and signed amount
 * in minor units.
 *
 * <p>The account name is denormalised in here purely for presentation — a
 * client rendering a transaction should not have to fetch the chart of
 * accounts to label two rows.
 */
public record PostingDetail(UUID accountId, String accountName, long amountMinor) {
}
