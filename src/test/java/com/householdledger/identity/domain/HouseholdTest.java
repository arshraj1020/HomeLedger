package com.householdledger.identity.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HouseholdTest {

    private final UUID id = UUID.randomUUID();

    @Test
    void exposesAllFields() {
        Household household = new Household(id, "Raj Family", "INR");

        assertThat(household.id()).isEqualTo(id);
        assertThat(household.name()).isEqualTo("Raj Family");
        assertThat(household.currency()).isEqualTo("INR");
    }

    @Test
    void nullFieldsAreRejected() {
        assertThatThrownBy(() -> new Household(null, "n", "INR")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Household(id, null, "INR")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Household(id, "n", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void blankNameIsRejected() {
        assertThatThrownBy(() -> new Household(id, "   ", "INR"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blank");
    }

    @Test
    void currencyMustBeThreeLetterCode() {
        // The schema stores CHAR(3) (PRD §6.3); rejecting early gives a clear
        // error instead of a truncation or constraint violation at flush.
        assertThatThrownBy(() -> new Household(id, "n", "RUPEES"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("3-letter");
        assertThatThrownBy(() -> new Household(id, "n", "IN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityIsByIdAlone() {
        Household first = new Household(id, "A", "INR");
        Household sameId = new Household(id, "B", "USD");
        Household otherId = new Household(UUID.randomUUID(), "A", "INR");

        assertThat(first).isEqualTo(sameId).hasSameHashCodeAs(sameId);
        assertThat(first).isNotEqualTo(otherId);
        assertThat(first).isEqualTo(first);
        assertThat(first).isNotEqualTo("not a household");
    }

    @Test
    void toStringContainsIdentifyingFields() {
        assertThat(new Household(id, "Raj Family", "INR").toString())
                .contains(id.toString()).contains("Raj Family").contains("INR");
    }
}
