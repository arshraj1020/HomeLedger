package com.householdledger.web;

import com.householdledger.ledger.api.AccountNameAlreadyExistsException;
import com.householdledger.ledger.api.AccountNotFoundException;
import com.householdledger.ledger.api.InactiveAccountException;
import com.householdledger.ledger.api.TransactionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Maps ledger errors to RFC 7807 Problem Details (PRD §6.4's error
 * contract).
 *
 * <p>The important mapping is {@link AccountNotFoundException} and
 * {@link TransactionNotFoundException} to <b>404, not 403</b>. Those
 * exceptions are thrown both when a resource genuinely does not exist and
 * when it belongs to another household — PRD §9 requires the two be
 * indistinguishable, because a 403 would confirm that someone else's
 * account exists.
 *
 * <p>Scope stays limited to what Phase 3 introduces; PRD §8 assigns the
 * general error contract to Phase 8, where this and
 * {@code AuthExceptionHandler} are expected to consolidate.
 */
@RestControllerAdvice
class LedgerExceptionHandler {

    private static final String ERROR_BASE = "https://household-ledger/errors/";

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail handleAccountNotFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Account not found", "account-not-found", e.getMessage());
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    ProblemDetail handleTransactionNotFound(TransactionNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Transaction not found", "transaction-not-found", e.getMessage());
    }

    @ExceptionHandler(AccountNameAlreadyExistsException.class)
    ProblemDetail handleDuplicateName(AccountNameAlreadyExistsException e) {
        return problem(HttpStatus.CONFLICT, "Account name already in use", "account-name-conflict", e.getMessage());
    }

    @ExceptionHandler(InactiveAccountException.class)
    ProblemDetail handleInactiveAccount(InactiveAccountException e) {
        // 422: the request is well-formed but the ledger refuses it, in the
        // same family as the unbalanced-transaction error in PRD §6.4.
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Account is deactivated", "inactive-account", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "invalid-request", e.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String title, String type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(ERROR_BASE + type));
        problem.setTitle(title);
        return problem;
    }
}
