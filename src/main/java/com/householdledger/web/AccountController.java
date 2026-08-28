package com.householdledger.web;

import com.householdledger.identity.api.AuthenticatedMember;
import com.householdledger.ledger.api.AccountService;
import com.householdledger.ledger.api.LedgerService;
import com.householdledger.ledger.domain.Account;
import com.householdledger.web.dto.AccountBalanceResponse;
import com.householdledger.web.dto.AccountResponse;
import com.householdledger.web.dto.CreateAccountRequest;
import com.householdledger.web.dto.UpdateAccountRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The accounts endpoints from PRD §6.4.
 *
 * <p>Household scoping is structural rather than checked: every call passes
 * {@code member.householdId()} — taken from the verified JWT — into the
 * service. The {@code {id}} path variable identifies an account, never a
 * household, so a caller substituting another household's account id gets a
 * lookup that finds nothing and a 404 (PRD §9: 404 rather than 403, so
 * existence is not leaked).
 *
 * <p>Authorisation follows PRD §FR-1: ADMIN "can manage accounts", while
 * MEMBER "can record transactions, read everything" — so writes are
 * ADMIN-only and reads are open to any authenticated member.
 *
 * <p>Depends only on {@code ledger.api}, {@code ledger.domain} and
 * {@code identity.api} — never on any module's {@code internal} package
 * (PRD §6.1, enforced by {@code ModuleBoundaryArchTest}).
 */
@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Accounts", description = "Chart of accounts management")
class AccountController {

    private final AccountService accountService;
    private final LedgerService ledgerService;

    AccountController(AccountService accountService, LedgerService ledgerService) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
    }

    @GetMapping
    @Operation(summary = "List every account in the household, active and deactivated alike")
    @ApiResponse(responseCode = "200", description = "Accounts listed")
    List<AccountResponse> list(@AuthenticationPrincipal AuthenticatedMember member) {
        return accountService.listAccounts(member.householdId()).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create an account (ADMIN only)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created"),
            @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "409", description = "Name already used in this household", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    ResponseEntity<AccountResponse> create(@AuthenticationPrincipal AuthenticatedMember member,
                                           @Valid @RequestBody CreateAccountRequest request) {

        Account created = accountService.createAccount(member.householdId(), request.type(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(created));
    }

    /**
     * Rename and/or activate-deactivate. There is no delete counterpart —
     * PRD §FR-2: "Accounts are never deleted."
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Rename and/or activate/deactivate an account (ADMIN only)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account updated"),
            @ApiResponse(responseCode = "400", description = "No changes supplied", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "404", description = "No such account in this household", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "409", description = "Name already used in this household", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    AccountResponse update(@AuthenticationPrincipal AuthenticatedMember member,
                           @PathVariable UUID id,
                           @Valid @RequestBody UpdateAccountRequest request) {

        if (request.isEmpty()) {
            throw new IllegalArgumentException("Supply at least one of 'name' or 'active'");
        }

        UUID householdId = member.householdId();
        Account account = null;

        if (request.name() != null) {
            account = accountService.renameAccount(householdId, id, request.name());
        }
        if (request.active() != null) {
            account = accountService.setAccountActive(householdId, id, request.active());
        }

        return AccountResponse.from(account);
    }

    /**
     * Balance derived by summing postings, never read from a stored column
     * (PRD §3.4). {@code asOf} bounds it to postings on or before that date.
     */
    @GetMapping("/{id}/balance")
    @Operation(summary = "Derived balance for an account, optionally as of a date")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Balance computed"),
            @ApiResponse(responseCode = "404", description = "No such account in this household", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    AccountBalanceResponse balance(@AuthenticationPrincipal AuthenticatedMember member,
                                   @PathVariable UUID id,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        UUID householdId = member.householdId();
        // Resolves the account first so an id from another household 404s
        // before any balance is computed.
        Account account = ledgerService.getAccount(householdId, id);
        long balanceMinor = ledgerService.accountBalanceMinor(householdId, id, asOf);

        return new AccountBalanceResponse(account.id(), account.name(), balanceMinor, asOf);
    }
}
