package com.householdledger.web;

import com.householdledger.identity.api.EmailAlreadyRegisteredException;
import com.householdledger.identity.api.InvalidCredentialsException;
import com.householdledger.identity.api.InvalidRefreshTokenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Maps authentication failures to RFC 7807 Problem Details (PRD §6.4's
 * error contract).
 *
 * <p>Scope is deliberately limited to identity errors: PRD §8 assigns the
 * general error contract to Phase 8, so this handles what Phase 2
 * introduces and no more. When Phase 8 generalises it, these handlers move
 * into the shared advice rather than being duplicated.
 *
 * <p>Both credential failures return 401 with the same generic title. The
 * detail messages never distinguish "no such account" from "wrong
 * password", so the API cannot be used to enumerate registered addresses.
 */
/**
 * Scoped to {@code @RestController} classes as of Phase 7.
 *
 * <p>Until the UI existed, an unqualified {@code @RestControllerAdvice}
 * applied to the only controllers there were, all of them REST. Phase 7 adds
 * Thymeleaf controllers that throw the very same exceptions, and an
 * unqualified advice would answer a browser navigation with an RFC 7807 JSON
 * body instead of a page. Restricting the advice by controller annotation
 * rather than by package keeps that from depending on which
 * {@code @ControllerAdvice} happens to be consulted first, and changes
 * nothing for the API: every API controller is annotated
 * {@code @RestController}, so the same exceptions still map to the same
 * statuses and the same problem documents.
 */
@RestControllerAdvice(annotations = RestController.class)
class AuthExceptionHandler {

    private static final String ERROR_BASE = "https://household-ledger/errors/";

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail handleInvalidCredentials(InvalidCredentialsException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setType(URI.create(ERROR_BASE + "invalid-credentials"));
        problem.setTitle("Authentication failed");
        return problem;
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setType(URI.create(ERROR_BASE + "invalid-refresh-token"));
        problem.setTitle("Refresh token rejected");
        return problem;
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    ProblemDetail handleEmailAlreadyRegistered(EmailAlreadyRegisteredException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setType(URI.create(ERROR_BASE + "email-already-registered"));
        problem.setTitle("Email already registered");
        return problem;
    }
}
