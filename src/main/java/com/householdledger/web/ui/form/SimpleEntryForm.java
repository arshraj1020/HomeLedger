package com.householdledger.web.ui.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The quick-entry form of PRD §FR-7 — "one source, one destination, one
 * unsigned amount", the path PRD §FR-3 says "handles well over 90% of real
 * entries".
 *
 * <p>The member never sees a sign, a debit or a credit. They say where the
 * money came from, where it went, and how much; the ledger derives the two
 * postings and their signs. That is the whole point of simple mode, and it is
 * why this form has no field that could express a direction inconsistent with
 * the two account choices.
 */
public class SimpleEntryForm {

    @NotNull(message = "Choose a date")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate occurredOn = LocalDate.now();

    @NotBlank(message = "Enter a description")
    @Size(max = 500, message = "Descriptions are at most 500 characters")
    private String description;

    @NotNull(message = "Choose where the money came from")
    private UUID fromAccountId;

    @NotNull(message = "Choose where the money went")
    private UUID toAccountId;

    @NotBlank(message = "Enter an amount")
    private String amount;

    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    public void setOccurredOn(LocalDate occurredOn) {
        this.occurredOn = occurredOn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getFromAccountId() {
        return fromAccountId;
    }

    public void setFromAccountId(UUID fromAccountId) {
        this.fromAccountId = fromAccountId;
    }

    public UUID getToAccountId() {
        return toAccountId;
    }

    public void setToAccountId(UUID toAccountId) {
        this.toAccountId = toAccountId;
    }

    public String getAmount() {
        return amount;
    }

    public void setAmount(String amount) {
        this.amount = amount;
    }
}
