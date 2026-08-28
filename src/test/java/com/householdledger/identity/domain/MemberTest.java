package com.householdledger.identity.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemberTest {

    private final UUID id = UUID.randomUUID();
    private final UUID householdId = UUID.randomUUID();

    private Member member(Role role) {
        return new Member(id, householdId, "Papa", "papa@example.com", role);
    }

    @Test
    void exposesAllFields() {
        Member member = member(Role.ADMIN);

        assertThat(member.id()).isEqualTo(id);
        assertThat(member.householdId()).isEqualTo(householdId);
        assertThat(member.name()).isEqualTo("Papa");
        assertThat(member.email()).isEqualTo("papa@example.com");
        assertThat(member.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void isAdminReflectsRole() {
        assertThat(member(Role.ADMIN).isAdmin()).isTrue();
        assertThat(member(Role.MEMBER).isAdmin()).isFalse();
    }

    @Test
    void nullFieldsAreRejected() {
        assertThatThrownBy(() -> new Member(null, householdId, "n", "e@x.com", Role.MEMBER))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("id");
        assertThatThrownBy(() -> new Member(id, null, "n", "e@x.com", Role.MEMBER))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("householdId");
        assertThatThrownBy(() -> new Member(id, householdId, null, "e@x.com", Role.MEMBER))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> new Member(id, householdId, "n", null, Role.MEMBER))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("email");
        assertThatThrownBy(() -> new Member(id, householdId, "n", "e@x.com", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("role");
    }

    @Test
    void blankNameAndEmailAreRejected() {
        assertThatThrownBy(() -> new Member(id, householdId, "  ", "e@x.com", Role.MEMBER))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> new Member(id, householdId, "n", "  ", Role.MEMBER))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("email");
    }

    @Test
    void equalityIsByIdAlone() {
        Member first = member(Role.ADMIN);
        Member sameIdOtherFields = new Member(id, UUID.randomUUID(), "Other", "other@example.com", Role.MEMBER);
        Member otherId = new Member(UUID.randomUUID(), householdId, "Papa", "papa@example.com", Role.ADMIN);

        assertThat(first).isEqualTo(sameIdOtherFields).hasSameHashCodeAs(sameIdOtherFields);
        assertThat(first).isNotEqualTo(otherId);
        assertThat(first).isEqualTo(first);
        assertThat(first).isNotEqualTo("not a member");
        assertThat(first).isNotEqualTo(null);
    }

    @Test
    void toStringCarriesNoCredentialMaterialButIdentifiesTheMember() {
        // The domain type has no password field at all; this guards against
        // one being added and leaking through logs.
        String rendered = member(Role.ADMIN).toString();

        assertThat(rendered).contains(id.toString()).contains("papa@example.com").contains("ADMIN");
        assertThat(rendered.toLowerCase()).doesNotContain("password").doesNotContain("hash");
    }
}
