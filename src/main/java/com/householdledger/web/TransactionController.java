package com.householdledger.web;

import com.householdledger.identity.api.AuthenticatedMember;
import com.householdledger.ledger.api.LedgerService;
import com.householdledger.ledger.api.PostingLine;
import com.householdledger.ledger.api.SplitLine;
import com.householdledger.ledger.api.TransactionDetail;
import com.householdledger.ledger.domain.Transaction;
import com.householdledger.web.dto.CreateTransactionRequest;
import com.householdledger.web.dto.TransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Transaction recording and reversal (PRD §FR-3, §FR-4, §6.4).
 *
 * <p>Authorisation differs from {@code AccountController} on purpose: PRD
 * §FR-1 gives MEMBER the right to "record transactions", reserving only
 * account and member management to ADMIN. So these endpoints require
 * authentication but not a role — a household where only the admin could
 * enter expenses would defeat the point.
 *
 * <p>Household scoping is structural: every call passes
 * {@code member.householdId()} from the verified JWT. Account ids in the
 * body and transaction ids in the path are looked up *within* that
 * household, so a forged id resolves to nothing and yields 404 rather than
 * 403 (PRD §9 — no existence leak).
 *
 * <p>Listing and filtering transactions ({@code GET /api/transactions}) is
 * Phase 5 and deliberately absent here.
 */
@RestController
@RequestMapping("/api/transactions")
@Tag(name = "Transactions", description = "Recording and reversing double-entry transactions")
class TransactionController {

    private final LedgerService ledgerService;

    TransactionController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /**
     * Records a transaction in simple, split or raw mode (PRD §FR-3).
     *
     * <p>All three modes converge on the same balanced posting set before
     * anything is written; the mode only decides how the caller describes
     * the movement, never whether the invariant applies.
     */
    @PostMapping
    @Operation(summary = "Record a transaction (mode: SIMPLE, SPLIT, or RAW)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transaction recorded"),
            @ApiResponse(responseCode = "400", description = "Malformed request for the chosen mode", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "404", description = "An account is not in this household", content = @Content),
            @ApiResponse(responseCode = "422", description = "Unbalanced, future-dated, or against a deactivated account", content = @Content)
    })
    ResponseEntity<TransactionResponse> record(@AuthenticationPrincipal AuthenticatedMember member,
                                               @Valid @RequestBody CreateTransactionRequest request) {

        request.validateShape();

        UUID householdId = member.householdId();
        UUID createdBy = member.memberId();

        Transaction recorded = switch (request.mode()) {
            case SIMPLE -> ledgerService.recordSimpleTransaction(
                    householdId, request.occurredOn(), request.description(), createdBy,
                    request.fromAccountId(), request.toAccountId(), request.amountMinor());

            case SPLIT -> ledgerService.recordSplitTransaction(
                    householdId, request.occurredOn(), request.description(), createdBy,
                    request.fromAccountId(),
                    request.destinations().stream()
                            .map(line -> new SplitLine(line.accountId(), line.amountMinor()))
                            .toList());

            case RAW -> ledgerService.recordTransaction(
                    householdId, request.occurredOn(), request.description(), createdBy,
                    request.postings().stream()
                            .map(line -> new PostingLine(line.accountId(), line.amountMinor()))
                            .toList());
        };

        // Re-read so the response carries resolved account names and the
        // reversed flag, exactly as GET returns them — one shape for a
        // transaction, however the client obtained it.
        TransactionDetail detail = ledgerService.getTransaction(householdId, recorded.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(detail));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Retrieve a single transaction with its full posting detail")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction found"),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "404", description = "No such transaction in this household", content = @Content)
    })
    TransactionResponse get(@AuthenticationPrincipal AuthenticatedMember member, @PathVariable UUID id) {
        return TransactionResponse.from(ledgerService.getTransaction(member.householdId(), id));
    }

    /**
     * Reverses a transaction (PRD §FR-4): creates the exact sign-inverse
     * transaction, linked to the original. Neither is deleted or edited —
     * both stay queryable, which is what makes the ledger a complete audit
     * log by construction (PRD §3.5).
     *
     * <p>Returns 201 with the *reversal*, since a new resource is created.
     */
    @PostMapping("/{id}/reverse")
    @Operation(summary = "Reverse a transaction, creating its exact inverse")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reversal created"),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "404", description = "No such transaction in this household", content = @Content),
            @ApiResponse(responseCode = "409", description = "Already reversed, or is itself a reversal", content = @Content)
    })
    ResponseEntity<TransactionResponse> reverse(@AuthenticationPrincipal AuthenticatedMember member,
                                                @PathVariable UUID id) {

        UUID householdId = member.householdId();
        Transaction reversal = ledgerService.reverseTransaction(householdId, id, member.memberId());
        TransactionDetail detail = ledgerService.getTransaction(householdId, reversal.id());

        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(detail));
    }
}
