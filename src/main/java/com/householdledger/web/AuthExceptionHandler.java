package com.householdledger.web;

import com.householdledger.identity.api.EmailAlreadyRegisteredException;
import com.householdledger.identity.api.InvalidCredentialsException;
import com.householdledger.identity.api.InvalidRefreshTokenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
@RestControllerAdvice
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
