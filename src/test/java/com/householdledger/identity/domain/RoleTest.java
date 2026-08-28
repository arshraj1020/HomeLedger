package com.householdledger.identity.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRD §FR-1 defines exactly two roles with a specific privilege split.
 * Asserted rather than assumed, because a third role appearing — or ADMIN
 * quietly losing management rights — would silently change who can do what.
 */
class RoleTest {

    @Test
    void thereAreExactlyTwoRoles() {
        assertThat(Role.values()).containsExactlyInAnyOrder(Role.ADMIN, Role.MEMBER);
    }

    @Test
    void onlyAdminCanManageAccountsAndMembers() {
        assertThat(Role.ADMIN.canManageAccountsAndMembers()).isTrue();
        assertThat(Role.MEMBER.canManageAccountsAndMembers()).isFalse();
    }

    @Test
    void authorityUsesSpringSecurityRolePrefix() {
        assertThat(Role.ADMIN.authority()).isEqualTo("ROLE_ADMIN");
        assertThat(Role.MEMBER.authority()).isEqualTo("ROLE_MEMBER");
    }

    @Test
    void everyRoleProducesAnAuthority() {
        for (Role role : Role.values()) {
            assertThat(role.authority()).startsWith("ROLE_").hasSizeGreaterThan(5);
        }
    }
}
