package com.householdledger.web.ui.form;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The transaction list's filter bar (PRD §FR-5, §FR-7).
 *
 * <p>Every field is optional and an empty form means "everything", which is
 * why there are no validation annotations: a search with nothing typed into
 * it is a valid search, not a failed one.
 *
 * <p>Notably absent is any household field. The household is taken from the
 * authenticated principal in the controller and cannot be influenced from
 * here (PRD §FR-1) — there is deliberately no property for a request
 * parameter to bind to, so no future edit to this form can accidentally
 * create one.
 *
 * <p>An {@code accountId} belonging to another household is not an error
 * either: it simply matches nothing, which is what a search for something
 * that is not there should do.
 */
public class TransactionFilterForm {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;

    private UUID accountId;

    /** Case-insensitive substring of the description; wildcard characters are literal. */
    private String q;

    public boolean isActive() {
        return from != null || to != null || accountId != null || (q != null && !q.isBlank());
    }

    /** True when the range reads backwards, which the list page reports rather than silently swapping. */
    public boolean isReversedRange() {
        return from != null && to != null && from.isAfter(to);
    }

    public LocalDate getFrom() {
        return from;
    }

    public void setFrom(LocalDate from) {
        this.from = from;
    }

    public LocalDate getTo() {
        return to;
    }

    public void setTo(LocalDate to) {
        this.to = to;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public String getQ() {
        return q;
    }

    public void setQ(String q) {
        this.q = q;
    }
}
