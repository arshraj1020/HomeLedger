package com.householdledger.identity.api;

import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Member;
import com.householdledger.identity.domain.Role;

import java.util.List;
import java.util.UUID;

/**
 * Creating households and members.
 *
 * <p>Deliberately has NO REST surface in this phase. PRD §FR-1 says an
 * ADMIN "can manage accounts and members", but the API surface in §6.4
 * lists no member endpoints, and §6.4 is treated as authoritative rather
 * than inventing endpoints the PRD does not specify. This service is what
 * tests, the Phase 3 seeding step, and the Phase 9 demo-data step build on;
 * if member-management endpoints are added later they wrap this rather than
 * duplicating it.
 *
 * <p>Password hashing (bcrypt, PRD §FR-1) happens inside the implementation —
 * a raw password is passed in and is never stored or returned.
 */
public interface MemberProvisioningService {

    /** Creates a household with the v1 default currency (INR, PRD §3.3). */
    Household createHousehold(String name);

    /**
     * Adds a member to a household, hashing {@code rawPassword} with bcrypt.
     *
     * @throws EmailAlreadyRegisteredException if the email is taken
     *         (also enforced by the {@code member.email} unique constraint)
     */
    Member registerMember(UUID householdId, String name, String email, String rawPassword, Role role);

    List<Member> membersOf(UUID householdId);
}
