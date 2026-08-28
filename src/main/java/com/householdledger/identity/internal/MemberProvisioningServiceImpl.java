package com.householdledger.identity.internal;

import com.householdledger.identity.api.EmailAlreadyRegisteredException;
import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Member;
import com.householdledger.identity.domain.Role;
import com.householdledger.ledger.api.AccountService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Package-private implementation of {@link MemberProvisioningService}. */
@Service
class MemberProvisioningServiceImpl implements MemberProvisioningService {

    /** v1 is INR-only; stored per household for forward compatibility (PRD §3.3). */
    private static final String DEFAULT_CURRENCY = "INR";

    private final HouseholdRepository householdRepository;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountService accountService;

    MemberProvisioningServiceImpl(HouseholdRepository householdRepository, MemberRepository memberRepository,
                                   PasswordEncoder passwordEncoder, AccountService accountService) {
        this.householdRepository = householdRepository;
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.accountService = accountService;
    }

    /**
     * Creates the household and immediately seeds its starting chart of
     * accounts (PRD §FR-2: "Seeded on household creation").
     *
     * <p>This is the one place {@code identity} reaches into {@code ledger},
     * and it does so through {@code ledger.api} only — never
     * {@code ledger.internal}. The dependency is deliberately one-way: the
     * ledger core knows nothing about members or households-as-identity, and
     * {@code ModuleBoundaryArchTest.ledgerDoesNotDependOnIdentity} keeps it
     * that way, so the graph stays acyclic.
     *
     * <p>Both steps share one {@code @Transactional} boundary, so a household
     * can never be left persisted without its accounts.
     */
    @Override
    @Transactional
    public Household createHousehold(String name) {
        HouseholdEntity entity = new HouseholdEntity(UUID.randomUUID(), name, DEFAULT_CURRENCY);
        Household household = householdRepository.save(entity).toDomain();

        accountService.seedDefaultAccounts(household.id());

        return household;
    }

    @Override
    @Transactional
    public Member registerMember(UUID householdId, String name, String email, String rawPassword, Role role) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Password must not be blank");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email must not be blank");
        }
        if (memberRepository.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }
        householdRepository.findById(householdId)
                .orElseThrow(() -> new IllegalArgumentException("No such household: " + householdId));

        // bcrypt (PRD §FR-1). The raw password is never stored and never
        // returned — the domain Member type has no field for it.
        MemberEntity entity = new MemberEntity(
                UUID.randomUUID(), householdId, name, email, passwordEncoder.encode(rawPassword), role);

        return memberRepository.save(entity).toDomain();
    }

    @Override
    public List<Member> membersOf(UUID householdId) {
        return memberRepository.findByHouseholdId(householdId).stream()
                .map(MemberEntity::toDomain)
                .toList();
    }
}
