package com.householdledger.web;

import com.householdledger.identity.api.IdentityService;
import com.householdledger.identity.api.TokenPair;
import com.householdledger.web.dto.LoginRequest;
import com.householdledger.web.dto.RefreshRequest;
import com.householdledger.web.dto.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The three authentication endpoints from PRD §6.4.
 *
 * <p>Depends only on {@code identity.api} — never on {@code identity.internal}
 * (PRD §6.1, enforced by {@code ModuleBoundaryArchTest}).
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Login, token refresh, and logout")
class AuthController {

    private final IdentityService identityService;

    AuthController(IdentityService identityService) {
        this.identityService = identityService;
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange email and password for an access/refresh token pair")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated"),
            @ApiResponse(responseCode = "401", description = "Invalid email or password", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        TokenPair pair = identityService.login(request.email(), request.password());
        return ResponseEntity.ok(TokenResponse.from(pair));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new pair; the presented token is revoked")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rotated"),
            @ApiResponse(responseCode = "401", description = "Refresh token invalid, expired, or revoked", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        TokenPair pair = identityService.refresh(request.refreshToken());
        return ResponseEntity.ok(TokenResponse.from(pair));
    }

    /**
     * Idempotent by design: logging out with an unknown or already-revoked
     * token still returns 204, so a client can always reach a signed-out
     * state and the endpoint cannot be used to probe which tokens exist.
     */
    @PostMapping("/logout")
    @Operation(summary = "Revoke a refresh token (idempotent)")
    @ApiResponse(responseCode = "204", description = "Token revoked, or was already unusable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshRequest request) {
        identityService.logout(request.refreshToken());
    }
}
