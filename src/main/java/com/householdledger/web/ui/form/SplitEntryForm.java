package com.householdledger.web.ui.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Split entry (PRD §FR-3): "one source funding several destinations, each
 * with its own amount... for a single bill covering several categories".
 *
 * <p>The destination rows are a fixed list, pre-sized by the controller and
 * padded with blanks. Rows the member left empty are dropped before
 * recording. This is deliberately how the form grows instead of a button that
 * clones a row with JavaScript: PRD §FR-7 asks for a server-rendered UI with
 * no build step, and a form that needs scripting to accept a fourth line is a
 * form that silently loses a line when scripting fails.
 *
 * <p>There is no remainder field, because there is no remainder to allocate —
 * the source is credited the exact sum of the destinations (PRD §FR-3), so
 * the entry is balanced by construction rather than by the member's
 * arithmetic.
 */
public class SplitEntryForm {

    /** Rows offered on a blank form. Generous enough for a normal bill, short enough to read. */
    public static final int DEFAULT_ROWS = 5;

    @NotNull(message = "Choose a date")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate occurredOn = LocalDate.now();

    @NotBlank(message = "Enter a description")
    @Size(max = 500, message = "Descriptions are at most 500 characters")
    private String description;

    @NotNull(message = "Choose where the money came from")
    private UUID fromAccountId;

    private List<EntryLine> destinations = new ArrayList<>();

    /** Pads the list to at least {@code rows} entries so binding has somewhere to put each field. */
    public void padTo(int rows) {
        while (destinations.size() < rows) {
            destinations.add(new EntryLine());
        }
    }

    /** The rows the member actually filled in, in the order they appear. */
    public List<EntryLine> filledDestinations() {
        return destinations.stream().filter(line -> !line.isBlank()).toList();
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

    public UUID getFromAccountId() {
        return fromAccountId;
    }

    public void setFromAccountId(UUID fromAccountId) {
        this.fromAccountId = fromAccountId;
    }

    public List<EntryLine> getDestinations() {
        return destinations;
    }

    public void setDestinations(List<EntryLine> destinations) {
        this.destinations = destinations == null ? new ArrayList<>() : destinations;
    }
}
