package com.householdledger.web.ui.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Raw entry: an arbitrary list of signed postings (PRD §FR-3).
 *
 * <p>PRD §FR-3 says raw mode is "exposed via API for completeness and used in
 * tests; not surfaced prominently in the UI". It is reachable here, from a
 * tab on the entry page rather than from the main navigation, because there
 * are legitimate entries that simple and split modes cannot express — an
 * opening-balance entry touching three accounts, a bank charge deducted from
 * the same payment — and the alternative is a member reaching for the API.
 *
 * <p>Unlike the other two modes, the amounts here are signed, and the member
 * is responsible for making them sum to zero. Nothing about that is relaxed
 * for the UI: the domain, the service and the database trigger all still
 * refuse an unbalanced entry (PRD §3.2), so a mistake produces an error, not
 * a broken ledger.
 */
public class RawEntryForm {

    /** Rows offered on a blank form; two is the minimum a transaction can have. */
    public static final int DEFAULT_ROWS = 4;

    @NotNull(message = "Choose a date")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate occurredOn = LocalDate.now();

    @NotBlank(message = "Enter a description")
    @Size(max = 500, message = "Descriptions are at most 500 characters")
    private String description;

    private List<EntryLine> postings = new ArrayList<>();

    public void padTo(int rows) {
        while (postings.size() < rows) {
            postings.add(new EntryLine());
        }
    }

    public List<EntryLine> filledPostings() {
        return postings.stream().filter(line -> !line.isBlank()).toList();
    }

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

    public List<EntryLine> getPostings() {
        return postings;
    }

    public void setPostings(List<EntryLine> postings) {
        this.postings = postings == null ? new ArrayList<>() : postings;
    }
}
